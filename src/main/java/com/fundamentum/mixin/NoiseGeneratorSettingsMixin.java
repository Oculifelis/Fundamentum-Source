package com.fundamentum.mixin;

import com.fundamentum.biome.SlicedSurfaceRules;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The {@code fundamentum:surface_rules} hook without TerraBlender: data rules run before the settings' own. Named twice
 * (dev name and SRG name, unremapped) so Fundamentum needs no refmap.
 */
@Mixin(NoiseGeneratorSettings.class)
public abstract class NoiseGeneratorSettingsMixin implements SlicedSurfaceRules {
    @Shadow(remap = false, aliases = "f_188871_") @Final private SurfaceRules.RuleSource surfaceRule;
    @Unique private SurfaceRules.RuleSource fundamentum$combined;

    @Inject(method = {"surfaceRule()Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;",
        "f_188871_()Lnet/minecraft/world/level/levelgen/SurfaceRules$RuleSource;"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void fundamentum$prepended(CallbackInfoReturnable<SurfaceRules.RuleSource> cir) {
        SurfaceRules.RuleSource combined = this.fundamentum$combined;
        if (combined != null) cir.setReturnValue(combined);
    }

    @Override
    public void fundamentum$prependSurfaceRule(SurfaceRules.RuleSource rule) {
        this.fundamentum$combined = rule == null ? null : SurfaceRules.sequence(rule, this.surfaceRule);
    }
}
