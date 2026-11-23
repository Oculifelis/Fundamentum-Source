package com.fundamentum.mixin;

import com.fundamentum.moon.MoonsClient;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A moon's and a night event's sky tint on the sunrise and sunset glow (a copy: vanilla hands out one shared array). */
@Mixin(DimensionSpecialEffects.class)
public abstract class DimensionSpecialEffectsMixin {
    @Inject(method = {"getSunriseColor", "m_7518_"}, at = @At("RETURN"), cancellable = true, remap = false)
    private void fundamentum$moonSunrise(float timeOfDay, float partialTick, CallbackInfoReturnable<float[]> cir) {
        float[] glow = cir.getReturnValue();
        if (glow == null) {
            return;
        }
        int rgb = (int) (glow[0] * 255) << 16 | (int) (glow[1] * 255) << 8 | (int) (glow[2] * 255);
        int tinted = MoonsClient.tintSky(rgb, partialTick);
        if (tinted != rgb) {
            cir.setReturnValue(new float[] {(tinted >> 16 & 255) / 255.0F, (tinted >> 8 & 255) / 255.0F, (tinted & 255) / 255.0F, glow[3]});
        }
    }
}
