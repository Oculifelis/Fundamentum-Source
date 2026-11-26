package com.fundamentum.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** Plays {@link Shake} on the camera: pitch, yaw and roll wobble, fading each tick. */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class ShakeClient {
    private ShakeClient() { }

    @SubscribeEvent
    public static void camera(ViewportEvent.ComputeCameraAngles event) {
        float s = Shake.strength;
        if (s <= 0.01F) return;
        ClientLevel level = Minecraft.getInstance().level;
        double t = event.getPartialTick() + (level == null ? 0 : level.getGameTime());
        event.setPitch(event.getPitch() + (float) (Math.sin(t * 9.1) * 0.8 * s));
        event.setYaw(event.getYaw() + (float) (Math.sin(t * 6.7 + 1.3) * 0.5 * s));
        event.setRoll(event.getRoll() + (float) (Math.sin(t * 7.3) * 0.6 * s));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (Shake.strength <= 0.01F) {
            Shake.strength = 0;
            return;
        }
        Shake.strength *= Shake.decay;
    }
}
