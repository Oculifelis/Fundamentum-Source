package com.fundamentum.mount;

import com.fundamentum.client.Keys;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Sends the mount hotkey ({@link Keys#MOUNT}) to the server (see {@link Mounts}). */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class MountsClient {
    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        while (Keys.MOUNT.consumeClick()) {
            if (Minecraft.getInstance().player != null) {
                PacketDistributor.sendToServer(Mounts.TogglePayload.INSTANCE);
            }
        }
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        Mounts.clearClient();
    }

    private MountsClient() {}
}
