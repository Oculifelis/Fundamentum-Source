package com.fundamentum.recipeview;

import net.minecraft.world.level.material.Fluid;

/** A fluid amount shown in a recipe slot, in millibuckets. Every viewer turns it into its own fluid stack. */
public record ViewFluid(Fluid fluid, long amount) {
    public ViewFluid {
        if (fluid == null) throw new IllegalArgumentException("fluid is null");
        if (amount <= 0) throw new IllegalArgumentException("fluid amount must be above 0");
    }
}
