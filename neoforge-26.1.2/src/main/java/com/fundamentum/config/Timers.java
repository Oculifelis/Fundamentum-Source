package com.fundamentum.config;

import com.fundamentum.port.FId;
import java.util.List;
import net.minecraft.server.MinecraftServer;

/**
 * The {@code fundamentum:performance} config: how often each system's safety-net check runs, in seconds. The systems
 * react to game events (inventory changes, placed and broken blocks, advancements, logins and so on) and only fall
 * back to a slow full check, spread over the players, so anything an event cannot report is still caught. 0 turns a
 * safety net off. The same file on every version.
 */
public final class Timers {
    private Timers() { }

    public static final FId CONFIG_ID = FId.of("fundamentum", "performance");
    private static FConfig config;

    /** Declares one safety-net interval in the performance config. Call while the mod loads. */
    public static synchronized ConfigValue<Integer> seconds(String key, int def) {
        if (config == null) config = FConfig.create(CONFIG_ID);
        return config.integer(key, def, 0, 86400);
    }

    /** The interval in ticks, or 0 when the safety net is off. */
    public static int ticks(ConfigValue<Integer> seconds, int def) {
        int s = seconds == null ? def : seconds.getOr(def);
        return Math.max(0, s) * 20;
    }

    /**
     * Whether the safety net is due for {@code key} on this tick. Each key gets its own tick in the interval, so a
     * hundred players are spread over it instead of all being checked at once.
     */
    public static boolean due(ConfigValue<Integer> seconds, int def, MinecraftServer server, Object key) {
        int period = ticks(seconds, def);
        return period > 0 && Math.floorMod(server.getTickCount() + (key == null ? 0 : key.hashCode()), period) == 0;
    }

    /**
     * Declares a per-player safety net: {@code check} runs for each online player once every {@code key} seconds
     * (default {@code def}), each player on its own tick of that interval. Call while the mod loads.
     */
    public static void perPlayer(String key, int def, java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> check) {
        ConfigValue<Integer> cv = seconds(key, def);
        com.fundamentum.port.FLife.serverTick(s -> {
            int period = ticks(cv, def);
            if (period <= 0) return;
            for (net.minecraft.server.level.ServerPlayer p : List.copyOf(s.getPlayerList().getPlayers())) {
                if (due(period, s, p.getUUID())) check.accept(p);
            }
        });
    }

    /** {@link #due} with the interval already read, for a loop over many keys in one tick. */
    public static boolean due(int periodTicks, MinecraftServer server, Object key) {
        return periodTicks > 0 && Math.floorMod(server.getTickCount() + (key == null ? 0 : key.hashCode()), periodTicks) == 0;
    }
}
