package com.fundamentum.biome;

import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.Nullable;

/** Added to {@code NoiseGeneratorSettings} by a mixin: surface rules tried before the settings' own (null: none). */
public interface SlicedSurfaceRules {
    void fundamentum$prependSurfaceRule(@Nullable SurfaceRules.RuleSource rule);
}
