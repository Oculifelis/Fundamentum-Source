package com.fundamentum.unify;

import com.mojang.serialization.Codec;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.common.world.ModifiableBiomeInfo;

/**
 * Removes ore features whose every target is a duplicate ore that unification replaces ({@code fundamentum:unify_ores},
 * used by {@code data/fundamentum/forge/biome_modifier/unify_ores.json}). Does nothing with the {@code ores} config
 * off or a material's {@code "ores": false}.
 */
public record UnifyOreModifier() implements BiomeModifier {
    public static final UnifyOreModifier INSTANCE = new UnifyOreModifier();
    public static final Codec<UnifyOreModifier> CODEC = Codec.unit(INSTANCE);

    @Override
    public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase != Phase.REMOVE || !Unify.ENABLED.get() || !Unify.ORES.get() || Unify.map().isEmpty()) return;
        for (GenerationStep.Decoration step : GenerationStep.Decoration.values()) {
            List<Holder<PlacedFeature>> features = builder.getGenerationSettings().getFeatures(step);
            features.removeIf(h -> duplicate(h.value()));
        }
    }

    /** Whether a placed feature only places ores that unification replaces. */
    public static boolean duplicate(PlacedFeature feature) {
        ConfiguredFeature<?, ?> cf = feature.feature().value();
        if (!(cf.config() instanceof OreConfiguration ore) || ore.targetStates.isEmpty()) return false;
        for (OreConfiguration.TargetBlockState t : ore.targetStates) {
            if (!UnifyHooks.oreRemoved(t.state.getBlock())) return false;
        }
        return true;
    }

    @Override
    public Codec<? extends BiomeModifier> codec() {
        return UnifyHooks.ORE_MODIFIER.get();
    }
}
