package com.fundamentum.port;

import com.fundamentum.transfer.EnergyBuffer;
import com.fundamentum.transfer.EnergyStore;
import com.fundamentum.transfer.FluidStore;
import com.fundamentum.transfer.FluidTank;
import com.fundamentum.transfer.FluidTanks;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * The loader side of {@code com.fundamentum.transfer}. On NeoForge 26.x this is the transfer API: blocks are read
 * through {@code Capabilities.Energy.BLOCK} and {@code Capabilities.Fluid.BLOCK}, each call runs in its own
 * transaction (nested in one that is already open), and exposed buffers journal their changes so an aborted
 * transaction puts them back. Mods use {@code Transfer}; this class is the porting layer.
 */
public final class FTransfer {
    private FTransfer() { }

    private record Exposed<S>(Supplier<? extends BlockEntityType<?>> type, BiFunction<BlockEntity, Direction, S> provider) { }

    private static final List<Exposed<EnergyBuffer>> ENERGY = new ArrayList<>();
    private static final List<Exposed<FluidTanks>> FLUIDS = new ArrayList<>();
    private static boolean registered;

    public static void init(IEventBus modBus) {
        modBus.addListener(RegisterCapabilitiesEvent.class, FTransfer::register);
    }

    public static synchronized void exposeEnergy(Supplier<? extends BlockEntityType<?>> type, BiFunction<BlockEntity, Direction, EnergyBuffer> provider) {
        if (registered) throw new IllegalStateException("Transfer.exposeEnergy called too late; call it while the mod loads");
        ENERGY.add(new Exposed<>(type, provider));
    }

    public static synchronized void exposeFluids(Supplier<? extends BlockEntityType<?>> type, BiFunction<BlockEntity, Direction, FluidTanks> provider) {
        if (registered) throw new IllegalStateException("Transfer.exposeFluids called too late; call it while the mod loads");
        FLUIDS.add(new Exposed<>(type, provider));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static synchronized void register(RegisterCapabilitiesEvent event) {
        registered = true;
        for (Exposed<EnergyBuffer> e : ENERGY) {
            BlockEntityType type = e.type().get();
            if (type == null) continue;
            BiFunction<BlockEntity, Direction, EnergyBuffer> p = e.provider();
            event.registerBlockEntity(Capabilities.Energy.BLOCK, type, (be, side) -> {
                EnergyBuffer b = p.apply((BlockEntity) be, (Direction) side);
                return b == null ? null : new EnergyAdapter(b);
            });
        }
        for (Exposed<FluidTanks> e : FLUIDS) {
            BlockEntityType type = e.type().get();
            if (type == null) continue;
            BiFunction<BlockEntity, Direction, FluidTanks> p = e.provider();
            event.registerBlockEntity(Capabilities.Fluid.BLOCK, type, (be, side) -> {
                FluidTanks t = p.apply((BlockEntity) be, (Direction) side);
                return t == null ? null : new FluidAdapter(t);
            });
        }
    }

    // ---- lookups ----

    public static EnergyStore energy(Level level, BlockPos pos, Direction side) {
        EnergyHandler h = level.getCapability(Capabilities.Energy.BLOCK, pos, side);
        return h == null ? null : new LoaderEnergy(h);
    }

    public static FluidStore fluids(Level level, BlockPos pos, Direction side) {
        ResourceHandler<FluidResource> h = level.getCapability(Capabilities.Fluid.BLOCK, pos, side);
        return h == null ? null : new LoaderFluids(h);
    }

    public static Fluid fluid(FId id) {
        Fluid f = BuiltInRegistries.FLUID.getValue(id.vanilla());
        return f == null ? Fluids.EMPTY : f;
    }

    public static FId idOf(Fluid fluid) {
        return FId.from(BuiltInRegistries.FLUID.getKey(fluid));
    }

    public static BlockEntityType<?> blockEntityType(FId id) {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(id.vanilla());
    }

    /** True when a store came from another mod's (or our own) loader handler rather than being one of ours directly. */
    public static boolean isLoaderView(Object store) {
        return store instanceof LoaderEnergy || store instanceof LoaderFluids;
    }

    // ---- transactions ----

    private static Transaction open() {
        return Transaction.getLifecycle() == Transaction.Lifecycle.OPEN
            ? Transaction.open(Transaction.getCurrentOpenedTransaction())
            : Transaction.openRoot();
    }

    private static int clamp(long v) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, v));
    }

    // ---- loader handler seen as our store ----

    private record LoaderEnergy(EnergyHandler h) implements EnergyStore {
        @Override
        public int stored() {
            return clamp(h.getAmountAsLong());
        }

        @Override
        public int capacity() {
            return clamp(h.getCapacityAsLong());
        }

        @Override
        public int insert(int amount, boolean simulate) {
            if (amount <= 0) return 0;
            try (Transaction tx = open()) {
                int n = h.insert(amount, tx);
                if (!simulate) tx.commit();
                return n;
            }
        }

        @Override
        public int extract(int amount, boolean simulate) {
            if (amount <= 0) return 0;
            try (Transaction tx = open()) {
                int n = h.extract(amount, tx);
                if (!simulate) tx.commit();
                return n;
            }
        }
    }

    private record LoaderFluids(ResourceHandler<FluidResource> h) implements FluidStore {
        @Override
        public int tanks() {
            return h.size();
        }

        @Override
        public Fluid fluid(int tank) {
            FluidResource r = h.getResource(tank);
            return r.isEmpty() ? Fluids.EMPTY : r.getFluid();
        }

        @Override
        public int amount(int tank) {
            return clamp(h.getAmountAsLong(tank));
        }

        @Override
        public int capacity(int tank) {
            return clamp(h.getCapacityAsLong(tank, h.getResource(tank)));
        }

        @Override
        public int insert(Fluid fluid, int amount, boolean simulate) {
            if (amount <= 0 || fluid == null || fluid == Fluids.EMPTY) return 0;
            try (Transaction tx = open()) {
                int n = h.insert(FluidResource.of(fluid), amount, tx);
                if (!simulate) tx.commit();
                return n;
            }
        }

        @Override
        public int extract(Fluid fluid, int amount, boolean simulate) {
            if (amount <= 0 || fluid == null || fluid == Fluids.EMPTY) return 0;
            try (Transaction tx = open()) {
                int n = h.extract(FluidResource.of(fluid), amount, tx);
                if (!simulate) tx.commit();
                return n;
            }
        }
    }

    // ---- our stores seen as loader handlers ----

    private static final class EnergyAdapter implements EnergyHandler {
        private final EnergyBuffer b;
        private final SnapshotJournal<Integer> journal = new SnapshotJournal<>() {
            @Override
            protected Integer createSnapshot() {
                return b.stored();
            }

            @Override
            protected void revertToSnapshot(Integer snapshot) {
                b.restore(snapshot);
            }

            @Override
            protected void onRootCommit(Integer original) {
                if (b.stored() != original) b.changed();
            }
        };

        EnergyAdapter(EnergyBuffer b) {
            this.b = b;
        }

        @Override
        public long getAmountAsLong() {
            return b.stored();
        }

        @Override
        public long getCapacityAsLong() {
            return b.capacity();
        }

        @Override
        public int insert(int amount, TransactionContext tx) {
            if (amount < 0) throw new IllegalArgumentException("negative amount");
            int n = b.insert(amount, true);
            if (n > 0) {
                journal.updateSnapshots(tx);
                b.restore(b.stored() + n);
            }
            return n;
        }

        @Override
        public int extract(int amount, TransactionContext tx) {
            if (amount < 0) throw new IllegalArgumentException("negative amount");
            int n = b.extract(amount, true);
            if (n > 0) {
                journal.updateSnapshots(tx);
                b.restore(b.stored() - n);
            }
            return n;
        }
    }

    private static final class FluidAdapter implements ResourceHandler<FluidResource> {
        private final FluidTanks t;
        private final SnapshotJournal<FluidTank.Contents[]> journal = new SnapshotJournal<>() {
            @Override
            protected FluidTank.Contents[] createSnapshot() {
                FluidTank.Contents[] s = new FluidTank.Contents[t.tanks()];
                for (int i = 0; i < s.length; i++) s[i] = t.tank(i).contents();
                return s;
            }

            @Override
            protected void revertToSnapshot(FluidTank.Contents[] s) {
                for (int i = 0; i < s.length; i++) t.tank(i).restore(s[i].toFluid(), s[i].amount());
            }

            @Override
            protected void onRootCommit(FluidTank.Contents[] original) {
                for (int i = 0; i < original.length; i++) {
                    if (!t.tank(i).contents().equals(original[i])) t.tank(i).changed();
                }
            }
        };

        FluidAdapter(FluidTanks t) {
            this.t = t;
        }

        @Override
        public int size() {
            return t.tanks();
        }

        @Override
        public FluidResource getResource(int index) {
            Fluid f = t.fluid(index);
            return f == Fluids.EMPTY || t.amount(index) <= 0 ? FluidResource.EMPTY : FluidResource.of(f);
        }

        @Override
        public long getAmountAsLong(int index) {
            return t.amount(index);
        }

        @Override
        public long getCapacityAsLong(int index, FluidResource resource) {
            return t.capacity(index);
        }

        @Override
        public boolean isValid(int index, FluidResource resource) {
            if (resource.isEmpty() || !resource.isComponentsPatchEmpty() || index < 0 || index >= t.tanks()) return false;
            FluidTank tank = t.tank(index);
            return tank.accepts(resource.getFluid()) || tank.fluid() == resource.getFluid();
        }

        @Override
        public int insert(int index, FluidResource resource, int amount, TransactionContext tx) {
            if (amount < 0) throw new IllegalArgumentException("negative amount");
            if (resource.isEmpty() || !resource.isComponentsPatchEmpty() || index < 0 || index >= t.tanks()) return 0;
            FluidTank tank = t.tank(index);
            Fluid f = resource.getFluid();
            int n = tank.insert(f, amount, true);
            if (n > 0) {
                journal.updateSnapshots(tx);
                tank.restore(f, tank.amount() + n);
            }
            return n;
        }

        @Override
        public int extract(int index, FluidResource resource, int amount, TransactionContext tx) {
            if (amount < 0) throw new IllegalArgumentException("negative amount");
            if (resource.isEmpty() || !resource.isComponentsPatchEmpty() || index < 0 || index >= t.tanks()) return 0;
            FluidTank tank = t.tank(index);
            int n = tank.extract(resource.getFluid(), amount, true);
            if (n > 0) {
                journal.updateSnapshots(tx);
                tank.restore(tank.fluid(), tank.amount() - n);
            }
            return n;
        }
    }
}
