package com.fundamentum.biome;

/** Added to {@code BiomeSource} by a mixin: forgets the remembered set of possible biomes, so a sliced list counts. */
public interface PossibleBiomesReset {
    void fundamentum$resetPossibleBiomes();
}
