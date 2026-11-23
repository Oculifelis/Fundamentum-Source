package com.fundamentum.biome;

import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jspecify.annotations.Nullable;

/** Added to {@code NoiseGeneratorSettings} by a mixin: surface rules tried before the settings' own (null: none). */
public interface SlicedSurfaceRules {
    void fundamentum$prependSurfaceRule(SurfaceRules.@Nullable RuleSource rule);
}
