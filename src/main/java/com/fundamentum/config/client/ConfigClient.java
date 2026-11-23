package com.fundamentum.config.client;

import com.fundamentum.config.FConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client side of {@link FConfig}: forgets the server's values when the player leaves it. */
public final class ConfigClient {
    private ConfigClient() { }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> FConfig.clientDisconnected());
    }
}
