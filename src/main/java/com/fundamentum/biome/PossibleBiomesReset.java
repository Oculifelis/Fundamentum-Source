package com.fundamentum.biome;

import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/** Added to {@code BiomeSource} by a mixin: replaces the remembered set of possible biomes, so a sliced list counts. */
public interface PossibleBiomesReset {
    void fundamentum$resetPossibleBiomes(Supplier<Stream<Holder<Biome>>> biomes);
}
