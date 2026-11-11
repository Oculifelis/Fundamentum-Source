package com.fundamentum.biome;

import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.jspecify.annotations.Nullable;

/**
 * Added to {@code NoiseGeneratorSettings} by a mixin: material rules tried before the settings' own (null: none). On
 * 26.3 the surface rules of 26.1.2 are material rules ({@code NoiseGeneratorSettings.materialRule}), with the same rule
 * and condition types and fields, so the {@code fundamentum/surface_rules} files go in here.
 */
public interface SlicedSurfaceRules {
    void fundamentum$prependSurfaceRule(@Nullable MaterialRule rule);
}
