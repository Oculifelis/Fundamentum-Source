package com.fundamentum.mixin;

import com.fundamentum.biome.SlicedSurfaceRules;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The {@code fundamentum:surface_rules} hook on 26.3: data rules run before the settings' own material rule. The
 * chunk generator reads {@code materialRule()} once per chunk for the surface and the carvers, as 26.1.2 read
 * {@code surfaceRule()}.
 */
@Mixin(NoiseGeneratorSettings.class)
public abstract class NoiseGeneratorSettingsMixin implements SlicedSurfaceRules {
    @Shadow @Final private Holder<MaterialRule> materialRule;
    @Unique private Holder<MaterialRule> fundamentum$combined;

    @ModifyReturnValue(method = "materialRule", at = @At("RETURN"))
    private Holder<MaterialRule> fundamentum$prepended(Holder<MaterialRule> original) {
        Holder<MaterialRule> combined = this.fundamentum$combined;
        return combined != null ? combined : original;
    }

    @Override
    public void fundamentum$prependSurfaceRule(MaterialRule rule) {
        // the settings' own rule goes in as its holder, so a datapack reload of the rule registry is still followed
        this.fundamentum$combined = rule == null ? null
            : Holder.direct(new SequenceRule(List.of(rule, new MaterialRule.HolderHolder(this.materialRule))));
    }
}
