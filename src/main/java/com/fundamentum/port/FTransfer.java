package com.fundamentum.port;

import com.fundamentum.transfer.EnergyBuffer;
import com.fundamentum.transfer.EnergyStore;
import com.fundamentum.transfer.FluidStore;
import com.fundamentum.transfer.FluidTank;
import com.fundamentum.transfer.FluidTanks;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

/**
 * The loader side of {@code com.fundamentum.transfer}. On Forge 1.20.1 blocks are read through
 * {@code ForgeCapabilities.ENERGY} and {@code FLUID_HANDLER} on the block entity, and exposed stores are attached with
 * {@code AttachCapabilitiesEvent<BlockEntity>}. The provider is asked on every lookup, so a fresh optional is handed
 * out each time. Mods use {@code Transfer}; this class is the porting layer.
 */
public final class FTransfer {
    private FTransfer() { }

    private record Exposed<S>(Supplier<? extends BlockEntityType<?>> type, BiFunction<BlockEntity, Direction, S> provider) { }

    private static final List<Exposed<EnergyBuffer>> ENERGY = new CopyOnWriteArrayList<>();
    private static final List<Exposed<FluidTanks>> FLUIDS = new CopyOnWriteArrayList<>();
    private static final ResourceLocation KEY = new ResourceLocation("fundamentum", "transfer");

    public static void init(IEventBus modBus) {
        MinecraftForge.EVENT_BUS.addGenericListener(BlockEntity.class, (AttachCapabilitiesEvent<BlockEntity> e) -> attach(e));
    }

    public static void exposeEnergy(Supplier<? extends BlockEntityType<?>> type, BiFunction<BlockEntity, Direction, EnergyBuffer> provider) {
        ENERGY.add(new Exposed<>(type, provider));
    }

    public static void exposeFluids(Supplier<? extends BlockEntityType<?>> type, BiFunction<BlockEntity, Direction, FluidTanks> provider) {
        FLUIDS.add(new Exposed<>(type, provider));
    }

    private static <S> BiFunction<BlockEntity, Direction, S> find(List<Exposed<S>> list, BlockEntityType<?> type) {
        for (Exposed<S> e : list) if (e.type().get() == type) return e.provider();
        return null;
    }

    private static void attach(AttachCapabilitiesEvent<BlockEntity> event) {
        BlockEntity be = event.getObject();
        BiFunction<BlockEntity, Direction, EnergyBuffer> energy = find(ENERGY, be.getType());
        BiFunction<BlockEntity, Direction, FluidTanks> fluids = find(FLUIDS, be.getType());
        if (energy == null && fluids == null) return;
        event.addCapability(KEY, new ICapabilityProvider() {
            @Override
            public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                if (cap == ForgeCapabilities.ENERGY && energy != null) {
                    EnergyBuffer b = energy.apply(be, side);
                    if (b != null) return LazyOptional.of(() -> new EnergyAdapter(b)).cast();
                } else if (cap == ForgeCapabilities.FLUID_HANDLER && fluids != null) {
                    FluidTanks t = fluids.apply(be, side);
                    if (t != null) return LazyOptional.of(() -> new FluidAdapter(t)).cast();
                }
                return LazyOptional.empty();
            }
        });
    }

    // ---- lookups ----

    public static EnergyStore energy(Level level, BlockPos pos, Direction side) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        IEnergyStorage h = be.getCapability(ForgeCapabilities.ENERGY, side).resolve().orElse(null);
        return h == null ? null : new LoaderEnergy(h);
    }

    public static FluidStore fluids(Level level, BlockPos pos, Direction side) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        IFluidHandler h = be.getCapability(ForgeCapabilities.FLUID_HANDLER, side).resolve().orElse(null);
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
            if (stack.isEmpty() || stack.hasTag() || tank < 0 || tank >= t.tanks()) return false;
            FluidTank k = t.tank(tank);
            return k.accepts(stack.getFluid()) || k.fluid() == stack.getFluid();
        }

        @Override
        public int fill(FluidStack stack, FluidAction action) {
            if (stack.isEmpty() || stack.hasTag()) return 0;
            return t.insert(stack.getFluid(), stack.getAmount(), action.simulate());
        }

        @Override
        public FluidStack drain(FluidStack stack, FluidAction action) {
            if (stack.isEmpty() || stack.hasTag()) return FluidStack.EMPTY;
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
