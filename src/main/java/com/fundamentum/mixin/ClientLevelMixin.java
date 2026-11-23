package com.fundamentum.mixin;

import com.fundamentum.moon.MoonsClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A moon's and a night event's sky tint ({@link MoonsClient#tintSky}) on the sky colour of the level it shines on. Named twice (dev name and SRG name, unremapped) so Fundamentum needs no refmap. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = {"getSkyColor", "m_171660_"}, at = @At("RETURN"), cancellable = true, remap = false)
    private void fundamentum$moonSky(Vec3 pos, float partialTick, CallbackInfoReturnable<Vec3> cir) {
        // the moon's tint, then a night event's, as the 26.x sky renderer mixin does (MoonsClient.tintSky)
        Vec3 sky = cir.getReturnValue();
        int rgb = (int) (Math.min(1.0, sky.x) * 255) << 16 | (int) (Math.min(1.0, sky.y) * 255) << 8 | (int) (Math.min(1.0, sky.z) * 255);
        int tinted = MoonsClient.tintSky(rgb, partialTick);
        if (tinted != rgb) {
            cir.setReturnValue(new Vec3((tinted >> 16 & 255) / 255.0, (tinted >> 8 & 255) / 255.0, (tinted & 255) / 255.0));
        }
    }
}
