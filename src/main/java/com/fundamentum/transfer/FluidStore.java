package com.fundamentum.transfer;

import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Something that holds fluids in one or more tanks, seen the same way on every version and loader. Amounts are
 * millibuckets (1000 per bucket). {@link Transfer#fluids} hands one out for any block that exposes the loader's fluid
 * capability; {@link FluidTank} and {@link FluidTanks} are the ready-made ones. Fluids carrying extra data (NBT or
 * components) are not moved by these helpers.
 */
public interface FluidStore {
    /** How many tanks. */
    int tanks();

    /** The fluid in a tank, {@link Fluids#EMPTY} when it is empty. */
    Fluid fluid(int tank);

    /** Millibuckets in a tank. */
    int amount(int tank);

    /** Most millibuckets a tank holds. */
    int capacity(int tank);

    /** Adds up to {@code amount} of a fluid, wherever it fits; returns how much went in (or would have). */
    int insert(Fluid fluid, int amount, boolean simulate);

    /** Takes up to {@code amount} of a fluid; returns how much came out (or would have). */
    int extract(Fluid fluid, int amount, boolean simulate);

    /** Millibuckets of one fluid across all tanks. */
    default int amountOf(Fluid fluid) {
        int n = 0;
        for (int i = 0; i < tanks(); i++) if (fluid(i) == fluid) n += amount(i);
        return n;
    }

    /** True when every tank is empty. */
    default boolean isEmpty() {
        for (int i = 0; i < tanks(); i++) if (amount(i) > 0 && fluid(i) != Fluids.EMPTY) return false;
        return true;
    }
}
