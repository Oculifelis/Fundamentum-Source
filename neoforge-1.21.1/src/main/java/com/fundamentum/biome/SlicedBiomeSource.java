package com.fundamentum.biome;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.jetbrains.annotations.Nullable;

/** Added to {@code MultiNoiseBiomeSource} by a mixin: the list it was made with, and the sliced list that replaces it. */
public interface SlicedBiomeSource {
    Climate.ParameterList<Holder<Biome>> fundamentum$original();

    @Nullable Climate.ParameterList<Holder<Biome>> fundamentum$sliced();

    void fundamentum$setSliced(@Nullable Climate.ParameterList<Holder<Biome>> list);
}
