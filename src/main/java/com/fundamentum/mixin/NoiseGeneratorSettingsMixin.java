package com.fundamentum.mixin;

import com.fundamentum.biome.SlicedSurfaceRules;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** The {@code fundamentum:surface_rules} hook without TerraBlender: data rules run before the settings' own. */
@Mixin(NoiseGeneratorSettings.class)
public abstract class NoiseGeneratorSettingsMixin implements SlicedSurfaceRules {
    @Shadow @Final private SurfaceRules.RuleSource surfaceRule;
    @Unique private SurfaceRules.RuleSource fundamentum$combined;

    @ModifyReturnValue(method = "surfaceRule", at = @At("RETURN"))
    private SurfaceRules.RuleSource fundamentum$prepended(SurfaceRules.RuleSource original) {
        SurfaceRules.RuleSource combined = this.fundamentum$combined;
        return combined != null ? combined : original;
    }

    @Override
    public void fundamentum$prependSurfaceRule(SurfaceRules.RuleSource rule) {
        this.fundamentum$combined = rule == null ? null : SurfaceRules.sequence(rule, this.surfaceRule);
    }
}
