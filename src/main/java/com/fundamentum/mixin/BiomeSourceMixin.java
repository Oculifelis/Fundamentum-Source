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

/** Lets biome slices make a source count its possible biomes again (features are placed only in those). */
@Mixin(BiomeSource.class)
public abstract class BiomeSourceMixin implements PossibleBiomesReset {
    @Shadow @Final @Mutable private Supplier<Set<Holder<Biome>>> possibleBiomes;

    @Shadow protected abstract Stream<Holder<Biome>> collectPossibleBiomes();

    @Override
    public void fundamentum$resetPossibleBiomes() {
        this.possibleBiomes = Suppliers.memoize(() -> this.collectPossibleBiomes().distinct().collect(ImmutableSet.toImmutableSet()));
    }
}
