package com.fundamentum.port;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server lifecycle hooks with the same calls on every version, so code that only needs "when the server is up" does
 * not have to name the loader's events. Register while the mod is being constructed.
 */
public final class FLife {
    private FLife() { }

    static final List<Consumer<MinecraftServer>> ABOUT_TO_START = new ArrayList<>();
    static final List<Consumer<MinecraftServer>> STARTED = new ArrayList<>();
    static final List<Consumer<MinecraftServer>> STOPPING = new ArrayList<>();
    static final List<Consumer<MinecraftServer>> STOPPED = new ArrayList<>();
    static final List<Consumer<MinecraftServer>> TICK = new ArrayList<>();
    static final List<Consumer<ServerPlayer>> LOGGED_IN = new ArrayList<>();
    static final List<DimensionChange> DIMENSION = new ArrayList<>();
    static final List<JsonData> JSON_DATA = new ArrayList<>();

    /** A player moved from one dimension to another. */
    @FunctionalInterface
    public interface DimensionChange {
        void changed(ServerPlayer player, FId from, FId to);
    }

    /** One JSON data folder read on world load and /reload. */
    public record JsonData(FId name, String folder, Consumer<Map<FId, List<JsonElement>>> apply) { }

    /** Runs before the worlds load; the server object and its world folder exist, the levels do not yet. */
    public static void serverAboutToStart(Consumer<MinecraftServer> action) {
        ABOUT_TO_START.add(action);
    }

    /** Runs once the server has started and the worlds are loaded. */
    public static void serverStarted(Consumer<MinecraftServer> action) {
        STARTED.add(action);
    }

    public static void serverStopping(Consumer<MinecraftServer> action) {
        STOPPING.add(action);
    }

    /** Runs after the server has stopped and saved. */
    public static void serverStopped(Consumer<MinecraftServer> action) {
        STOPPED.add(action);
    }

    /** Runs at the end of every server tick, on the server thread. */
    public static void serverTick(Consumer<MinecraftServer> action) {
        TICK.add(action);
    }

    public static void playerLoggedIn(Consumer<ServerPlayer> action) {
        LOGGED_IN.add(action);
    }

    /** Runs after a player has changed dimension, with the dimension ids it left and entered. */
    public static void playerChangedDimension(DimensionChange action) {
        DIMENSION.add(action);
    }

    /**
     * Reads every {@code data/<ns>/<folder>/**.json} on world load and /reload and hands over each file id with its
     * copies from every enabled pack, lowest priority first, already parsed. A file that does not parse is left out
     * and logged. {@code apply} runs on the server thread. {@code name} names the reload listener.
     */
    public static void jsonData(FId name, String folder, Consumer<Map<FId, List<JsonElement>>> apply) {
        JSON_DATA.add(new JsonData(name, folder, apply));
    }
}
