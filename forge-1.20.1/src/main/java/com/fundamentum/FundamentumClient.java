package com.fundamentum;

import com.fundamentum.portal.PortalCraftingClient;

/**
 * Client side of {@link Fundamentum}: the client's copy of the portal recipes. Called from the mod's constructor on a
 * client only. 1.20.1 Forge has no generic config screen, so there is no Config button in the mod list here; the client
 * settings (whips.flatHandles, the sky switches) are edited in {@code config/fundamentum-client.toml} and
 * {@code config/fundamentum-sky-client.toml}.
 */
public final class FundamentumClient {
    static void init() {
        PortalCraftingClient.init();
    }

    private FundamentumClient() {}
}
