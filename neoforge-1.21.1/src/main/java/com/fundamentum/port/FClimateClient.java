package com.fundamentum.port;

import net.minecraft.client.Minecraft;

/** Client climate calls that differ by version: redrawing the world after the season's tint changed. */
public final class FClimateClient {
    private FClimateClient() { }

    /** Clears the cached biome colours and rebuilds the loaded chunks, on the client thread. */
    public static void refreshTints() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.level == null) return;
            mc.level.clearTintCaches();
            mc.levelRenderer.allChanged();
        });
    }
}
