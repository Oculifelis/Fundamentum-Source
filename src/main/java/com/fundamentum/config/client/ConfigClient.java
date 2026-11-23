package com.fundamentum.config.client;

import com.fundamentum.config.FConfig;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;

/** Client side of {@link FConfig}: forgets the server's values when the player leaves it. */
public final class ConfigClient {
    private ConfigClient() { }

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientPlayerNetworkEvent.LoggingOut.class, e -> FConfig.clientDisconnected());
    }
}
