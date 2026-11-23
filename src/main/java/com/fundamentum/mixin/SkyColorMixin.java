package com.fundamentum.mixin;

import com.fundamentum.moon.MoonsClient;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A moon's sky tint ({@link MoonsClient#tintSky}) on the sky colour and the sunrise/sunset glow the client's camera
 * reads (1.25.3; before, the tint was laid on the sky renderer's state only). Shader packs read the same value (Iris's
 * {@code skyColor} uniform comes from the camera's attribute probe), so the moon's sky shows under a pack too, pushed
 * twice as far there.
 */
@Mixin(EnvironmentAttributeProbe.class)
public abstract class SkyColorMixin {
    @Shadow
    private Level level;

    @SuppressWarnings("unchecked")
    @Inject(method = "getValue", at = @At("RETURN"), cancellable = true)
    private <V> void fundamentum$moonSky(EnvironmentAttribute<V> attribute, float partialTicks, CallbackInfoReturnable<V> cir) {
        if ((attribute == EnvironmentAttributes.SKY_COLOR || attribute == EnvironmentAttributes.SUNRISE_SUNSET_COLOR)
            && this.level != null && this.level.isClientSide() && cir.getReturnValue() instanceof Integer argb) {
            int tinted = MoonsClient.tintSky(argb, partialTicks);
            if (tinted != argb && com.fundamentum.client.Emissive.shadersOn()) {
                tinted = stronger(argb, tinted);
            }
            cir.setReturnValue((V) (Integer) tinted);
        }
    }

    /**
     * Under a shader pack, twice as far toward the tint (1.25.3): packs like Complementary build their night sky from
     * this colour but darken and desaturate it, so the moon's colour read faint at the same strength.
     */
    private static int stronger(int from, int to) {
        int out = from & 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = from >> shift & 255, b = to >> shift & 255;
            out |= Math.max(0, Math.min(255, a + (b - a) * 2)) << shift;
        }
        return out;
    }
}
