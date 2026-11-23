package com.fundamentum.mixin;

import com.fundamentum.moon.MoonsClient;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
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
 * 26.3: both attributes are float vectors (0..1), {@code Vector3fc} and {@code Vector4fc}, not packed ints.
 */
@Mixin(EnvironmentAttributeProbe.class)
public abstract class SkyColorMixin {
    @Shadow
    private Level level;

    @SuppressWarnings("unchecked")
    @Inject(method = "getValue", at = @At("RETURN"), cancellable = true)
    private <V> void fundamentum$moonSky(EnvironmentAttribute<V> attribute, float partialTicks, CallbackInfoReturnable<V> cir) {
        if (this.level == null || !this.level.isClientSide()) {
            return;
        }
        Object value = cir.getReturnValue();
        if (attribute == EnvironmentAttributes.SKY_COLOR && value instanceof Vector3fc rgb) {
            Vector3fc tinted = MoonsClient.tintSky(rgb, partialTicks);
            if (tinted != rgb && com.fundamentum.client.Emissive.shadersOn()) {
                tinted = new Vector3f(stronger(rgb.x(), tinted.x()), stronger(rgb.y(), tinted.y()), stronger(rgb.z(), tinted.z()));
            }
            cir.setReturnValue((V) tinted);
        } else if (attribute == EnvironmentAttributes.SUNRISE_SUNSET_COLOR && value instanceof Vector4fc rgba) {
            Vector4fc tinted = MoonsClient.tintSky(rgba, partialTicks);
            if (tinted != rgba && com.fundamentum.client.Emissive.shadersOn()) {
                tinted = new Vector4f(stronger(rgba.x(), tinted.x()), stronger(rgba.y(), tinted.y()), stronger(rgba.z(), tinted.z()), tinted.w());
            }
            cir.setReturnValue((V) tinted);
        }
    }

    /**
     * Under a shader pack, twice as far toward the tint (1.25.3): packs like Complementary build their night sky from
     * this colour but darken and desaturate it, so the moon's colour read faint at the same strength.
     */
    private static float stronger(float from, float to) {
        return Math.max(0.0F, Math.min(1.0F, from + (to - from) * 2.0F));
    }
}
