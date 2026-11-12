package com.fundamentum.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Several {@link FluidTank}s seen as one store, the way a machine with input and output tanks is exposed. Inserting
 * fills tanks that already hold the fluid first, then the first empty tank that takes it; extracting drains tanks in
 * order.
 */
public final class FluidTanks implements FluidStore {
    private final List<FluidTank> tanks;

    private FluidTanks(List<FluidTank> tanks) {
        this.tanks = Collections.unmodifiableList(new ArrayList<>(tanks));
    }

    public static FluidTanks of(FluidTank... tanks) {
        return new FluidTanks(List.of(tanks));
    }

    public static FluidTanks of(List<FluidTank> tanks) {
        return new FluidTanks(tanks);
    }

    public List<FluidTank> list() {
        return tanks;
    }

    public FluidTank tank(int i) {
        return tanks.get(i);
    }

    @Override
    public int tanks() {
        return tanks.size();
    }

    @Override
    public Fluid fluid(int tank) {
        return tank >= 0 && tank < tanks.size() ? tanks.get(tank).fluid() : Fluids.EMPTY;
    }

    @Override
    public int amount(int tank) {
        return tank >= 0 && tank < tanks.size() ? tanks.get(tank).amount() : 0;
    }

    @Override
    public int capacity(int tank) {
        return tank >= 0 && tank < tanks.size() ? tanks.get(tank).capacity() : 0;
    }

    @Override
    public int insert(Fluid fluid, int amount, boolean simulate) {
        if (amount <= 0 || fluid == null || fluid == Fluids.EMPTY) return 0;
        int left = amount;
        for (FluidTank t : tanks) {
            if (left <= 0) break;
            if (!t.empty() && t.fluid() == fluid) left -= t.insert(fluid, left, simulate);
        }
        // one empty tank at most, so a simulated insert never counts the same fluid into two empty tanks twice
        for (FluidTank t : tanks) {
            if (left <= 0) break;
            if (!t.empty()) continue;
            int n = t.insert(fluid, left, simulate);
            if (n > 0) {
                left -= n;
                break;
            }
        }
        return amount - left;
    }

    @Override
    public int extract(Fluid fluid, int amount, boolean simulate) {
        if (amount <= 0 || fluid == null || fluid == Fluids.EMPTY) return 0;
        int left = amount;
        for (FluidTank t : tanks) {
            if (left <= 0) break;
            left -= t.extract(fluid, left, simulate);
        }
        return amount - left;
    }
}
