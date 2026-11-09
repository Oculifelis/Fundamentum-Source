package com.fundamentum.mixin;

import com.fundamentum.biome.PossibleBiomesReset;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Lets biome slices make a source count its possible biomes again (features are placed only in those). Named twice (dev
 * name and SRG alias, unremapped) so Fundamentum needs no refmap.
 */
@Mixin(BiomeSource.class)
public abstract class BiomeSourceMixin implements PossibleBiomesReset {
    @Shadow(remap = false, aliases = "f_47891_") @Final @Mutable public Supplier<Set<Holder<Biome>>> possibleBiomes;

    @Override
    public void fundamentum$resetPossibleBiomes(Supplier<Stream<Holder<Biome>>> biomes) {
        this.possibleBiomes = Suppliers.memoize(() -> biomes.get().distinct().collect(ImmutableSet.toImmutableSet()));
    }
}
