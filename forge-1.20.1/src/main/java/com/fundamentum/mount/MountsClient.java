package com.fundamentum.mount;

import com.fundamentum.client.Keys;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Sends the mount hotkey ({@link Keys#MOUNT}) to the server (see {@link Mounts}). */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MountsClient {
    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        while (Keys.MOUNT.consumeClick()) {
            if (Minecraft.getInstance().player != null) {
                Mounts.CHANNEL.sendToServer(Mounts.TogglePayload.INSTANCE);
            }
        }
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        Mounts.clearClient();
    }

    private MountsClient() {}
}
