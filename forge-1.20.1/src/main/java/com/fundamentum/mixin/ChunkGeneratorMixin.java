package com.fundamentum.mixin;

import com.fundamentum.biome.FeaturesPerStepReset;
import com.google.common.base.Suppliers;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 1.20.1 has no refreshFeaturesPerStep: biome slices rebuild the per-step feature order here, the way the constructor
 * builds it, after a source's biomes change. Named twice (dev name and SRG alias, unremapped) so Fundamentum needs no refmap.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin implements FeaturesPerStepReset {
    @Shadow(remap = false, aliases = "f_223020_") @Final @Mutable private Supplier<List<FeatureSorter.StepFeatureData>> featuresPerStep;
    @Shadow(remap = false, aliases = "f_223021_") @Final private Function<Holder<Biome>, BiomeGenerationSettings> generationSettingsGetter;

    @Override
    public void fundamentum$refreshFeaturesPerStep() {
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        Function<Holder<Biome>, BiomeGenerationSettings> settings = this.generationSettingsGetter;
        this.featuresPerStep = Suppliers.memoize(() -> FeatureSorter.buildFeaturesPerStep(
            List.copyOf(self.getBiomeSource().possibleBiomes()), b -> settings.apply(b).features(), true));
    }
}
