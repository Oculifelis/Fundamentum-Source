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
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * The loader side of {@code com.fundamentum.transfer}. On NeoForge 1.21.1 blocks are read through
 * {@code Capabilities.EnergyStorage.BLOCK} (IEnergyStorage) and {@code Capabilities.FluidHandler.BLOCK} (IFluidHandler),
 * which simulate with a flag, so our stores map onto them directly. Mods use {@code Transfer}; this class is the
 * porting layer.
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
            event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, type, (be, side) -> {
                EnergyBuffer b = p.apply((BlockEntity) be, (Direction) side);
                return b == null ? null : new EnergyAdapter(b);
            });
        }
        for (Exposed<FluidTanks> e : FLUIDS) {
            BlockEntityType type = e.type().get();
            if (type == null) continue;
            BiFunction<BlockEntity, Direction, FluidTanks> p = e.provider();
            event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, type, (be, side) -> {
                FluidTanks t = p.apply((BlockEntity) be, (Direction) side);
                return t == null ? null : new FluidAdapter(t);
            });
        }
    }

    // ---- lookups ----

    public static EnergyStore energy(Level level, BlockPos pos, Direction side) {
        IEnergyStorage h = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, side);
        return h == null ? null : new LoaderEnergy(h);
    }

    public static FluidStore fluids(Level level, BlockPos pos, Direction side) {
        IFluidHandler h = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
        return h == null ? null : new LoaderFluids(h);
    }

    public static Fluid fluid(FId id) {
        Fluid f = BuiltInRegistries.FLUID.get(id.vanilla());
        return f == null ? Fluids.EMPTY : f;
    }

    public static FId idOf(Fluid fluid) {
        return FId.from(BuiltInRegistries.FLUID.getKey(fluid));
    }

    public static BlockEntityType<?> blockEntityType(FId id) {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id.vanilla());
    }

    /** True when a store came from another mod's (or our own) loader handler rather than being one of ours directly. */
    public static boolean isLoaderView(Object store) {
        return store instanceof LoaderEnergy || store instanceof LoaderFluids;
    }

    private static IFluidHandler.FluidAction action(boolean simulate) {
        return simulate ? IFluidHandler.FluidAction.SIMULATE : IFluidHandler.FluidAction.EXECUTE;
    }

    // ---- loader handler seen as our store ----

    private record LoaderEnergy(IEnergyStorage h) implements EnergyStore {
        @Override
        public int stored() {
            return h.getEnergyStored();
        }

        @Override
        public int capacity() {
            return h.getMaxEnergyStored();
        }

        @Override
        public int insert(int amount, boolean simulate) {
            return amount <= 0 ? 0 : h.receiveEnergy(amount, simulate);
        }

        @Override
        public int extract(int amount, boolean simulate) {
            return amount <= 0 ? 0 : h.extractEnergy(amount, simulate);
        }
    }

    private record LoaderFluids(IFluidHandler h) implements FluidStore {
        @Override
        public int tanks() {
            return h.getTanks();
        }

        @Override
        public Fluid fluid(int tank) {
            FluidStack s = h.getFluidInTank(tank);
            return s.isEmpty() ? Fluids.EMPTY : s.getFluid();
        }

        @Override
        public int amount(int tank) {
            return h.getFluidInTank(tank).getAmount();
        }

        @Override
        public int capacity(int tank) {
            return h.getTankCapacity(tank);
        }

        @Override
        public int insert(Fluid fluid, int amount, boolean simulate) {
            if (amount <= 0 || fluid == null || fluid == Fluids.EMPTY) return 0;
            return h.fill(new FluidStack(fluid, amount), action(simulate));
        }

        @Override
        public int extract(Fluid fluid, int amount, boolean simulate) {
            if (amount <= 0 || fluid == null || fluid == Fluids.EMPTY) return 0;
            return h.drain(new FluidStack(fluid, amount), action(simulate)).getAmount();
        }
    }

    // ---- our stores seen as loader handlers ----

    private record EnergyAdapter(EnergyBuffer b) implements IEnergyStorage {
        @Override
        public int receiveEnergy(int amount, boolean simulate) {
            return b.insert(amount, simulate);
        }

        @Override
        public int extractEnergy(int amount, boolean simulate) {
            return b.extract(amount, simulate);
        }

        @Override
        public int getEnergyStored() {
            return b.stored();
        }

        @Override
        public int getMaxEnergyStored() {
            return b.capacity();
        }

        @Override
        public boolean canExtract() {
            return b.maxExtract() > 0;
        }

        @Override
        public boolean canReceive() {
            return b.maxInsert() > 0;
        }
    }

    private record FluidAdapter(FluidTanks t) implements IFluidHandler {
        @Override
        public int getTanks() {
            return t.tanks();
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            Fluid f = t.fluid(tank);
            int amount = t.amount(tank);
            return f == Fluids.EMPTY || amount <= 0 ? FluidStack.EMPTY : new FluidStack(f, amount);
        }

        @Override
        public int getTankCapacity(int tank) {
            return t.capacity(tank);
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            if (stack.isEmpty() || !stack.isComponentsPatchEmpty() || tank < 0 || tank >= t.tanks()) return false;
            FluidTank k = t.tank(tank);
            return k.accepts(stack.getFluid()) || k.fluid() == stack.getFluid();
        }

        @Override
        public int fill(FluidStack stack, FluidAction action) {
            if (stack.isEmpty() || !stack.isComponentsPatchEmpty()) return 0;
            return t.insert(stack.getFluid(), stack.getAmount(), action.simulate());
        }

        @Override
        public FluidStack drain(FluidStack stack, FluidAction action) {
            if (stack.isEmpty() || !stack.isComponentsPatchEmpty()) return FluidStack.EMPTY;
            int n = t.extract(stack.getFluid(), stack.getAmount(), action.simulate());
            return n <= 0 ? FluidStack.EMPTY : new FluidStack(stack.getFluid(), n);
        }

        @Override
        public FluidStack drain(int max, FluidAction action) {
            if (max <= 0) return FluidStack.EMPTY;
            for (FluidTank k : t.list()) {
                if (!k.canDrain() || k.empty()) continue;
                Fluid f = k.fluid();
                int n = t.extract(f, max, action.simulate());
                if (n > 0) return new FluidStack(f, n);
            }
            return FluidStack.EMPTY;
        }
    }
}
