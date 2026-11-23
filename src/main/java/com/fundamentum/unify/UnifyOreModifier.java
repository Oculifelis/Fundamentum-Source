package com.fundamentum.unify;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

/**
 * Removes ore features whose every target is a duplicate ore that unification replaces ({@code fundamentum:unify_ores},
 * used by {@code data/fundamentum/neoforge/biome_modifier/unify_ores.json}). Does nothing with the {@code ores} config
 * off or a material's {@code "ores": false}.
 */
public record UnifyOreModifier() implements BiomeModifier {
    public static final UnifyOreModifier INSTANCE = new UnifyOreModifier();
    public static final MapCodec<UnifyOreModifier> CODEC = MapCodec.unit(INSTANCE);

    @Override
    public void modify(RegistryAccess registries, Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase != Phase.REMOVE || !Unify.ENABLED.get() || !Unify.ORES.get() || Unify.map().isEmpty()) return;
        for (GenerationStep.Decoration step : GenerationStep.Decoration.values()) {
            List<Holder<PlacedFeature>> features = builder.getGenerationSettings().getFeatures(step);
            features.removeIf(h -> duplicate(h.value()));
        }
    }

    /** Whether a placed feature only places ores that unification replaces. */
    public static boolean duplicate(PlacedFeature feature) {
        if (!(feature.feature().value() instanceof AbstractOreFeature ore) || ore.targetStates().isEmpty()) return false;
        for (BlockReplacement t : ore.targetStates()) {
            if (!UnifyHooks.oreRemoved(t.state().getBlock())) return false;
        }
        return true;
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec() {
        return UnifyHooks.ORE_MODIFIER.get();
    }
}
