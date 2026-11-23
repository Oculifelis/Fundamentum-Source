package com.fundamentum.port;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

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
    static final List<Consumer<ServerPlayer>> INVENTORY = new ArrayList<>();
    static final List<BlockChange> BLOCK = new ArrayList<>();
    static final List<BiConsumer<ServerPlayer, FId>> ADVANCEMENT = new ArrayList<>();
    static final List<Consumer<LivingEntity>> EFFECTS = new ArrayList<>();
    static final List<BiConsumer<Mob, LivingEntity>> TARGET = new ArrayList<>();
    static final List<Consumer<MinecraftServer>> RELOADED = new ArrayList<>();
    static final List<ChunkWatch> CHUNK_WATCH = new ArrayList<>();
    static final List<Consumer<ServerPlayer>> HANDS = new ArrayList<>();
    /** Players whose held items changed this tick, handed to {@link #HANDS} once at the end of the tick. */
    private static final java.util.Set<ServerPlayer> HANDS_PENDING = new java.util.LinkedHashSet<>();

    static {
        TICK.add(s -> {
            if (HANDS_PENDING.isEmpty()) return;
            List<ServerPlayer> now = new ArrayList<>(HANDS_PENDING);
            HANDS_PENDING.clear();
            for (ServerPlayer p : now) {
                if (p.isRemoved()) continue;
                for (int i = 0; i < HANDS.size(); i++) HANDS.get(i).accept(p);
            }
        });
    }

    /** A player started receiving a chunk (it came into their view distance). */
    @FunctionalInterface
    public interface ChunkWatch {
        void watched(ServerPlayer player, ServerLevel level, int chunkX, int chunkZ);
    }

    /** A block in a server level was set to a new state. */
    @FunctionalInterface
    public interface BlockChange {
        void changed(ServerLevel level, BlockPos pos);
    }

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

    // ---- game events, so systems react instead of checking on a timer

    /**
     * Runs when a player's inventory changed (picked up, crafted, moved, used up or dropped an item, armour included),
     * on the server thread. Many changes in one tick may each call it.
     */
    public static void inventoryChanged(Consumer<ServerPlayer> action) {
        INVENTORY.add(action);
    }

    /** Runs whenever a block in a server level is set to a new state (placed, broken, moved, or changed by a command). Keep it cheap. */
    public static void blockChanged(BlockChange action) {
        BLOCK.add(action);
    }

    /** Runs when a player earns an advancement, with its id. */
    public static void advancementEarned(BiConsumer<ServerPlayer, FId> action) {
        ADVANCEMENT.add(action);
    }

    /** Runs when a living entity gains, loses or runs out of a status effect. */
    public static void effectsChanged(Consumer<LivingEntity> action) {
        EFFECTS.add(action);
    }

    /** Runs when a mob picks a new target (or drops it, with null). */
    public static void targetChanged(BiConsumer<Mob, LivingEntity> action) {
        TARGET.add(action);
    }

    /** Runs once the server has started and after every /reload, once recipes, tags and data are in place. */
    public static void dataReloaded(Consumer<MinecraftServer> action) {
        RELOADED.add(action);
    }

    /** Runs when a player starts receiving a chunk, after the chunk itself was sent. */
    public static void chunkWatched(ChunkWatch action) {
        CHUNK_WATCH.add(action);
    }

    public static void fireChunkWatched(ServerPlayer player, ServerLevel level, int chunkX, int chunkZ) {
        for (int i = 0; i < CHUNK_WATCH.size(); i++) CHUNK_WATCH.get(i).watched(player, level, chunkX, chunkZ);
    }

    /**
     * Runs at the end of a tick in which a player's main or off hand item changed (a new item, a switched hotbar slot,
     * a used up stack), after the item's attribute modifiers were applied. Once per player per tick.
     */
    public static void handsChanged(Consumer<ServerPlayer> action) {
        HANDS.add(action);
    }

    public static void fireHandChanged(ServerPlayer player) {
        HANDS_PENDING.add(player);
    }

    public static void fireInventoryChanged(ServerPlayer player) {
        for (int i = 0; i < INVENTORY.size(); i++) INVENTORY.get(i).accept(player);
    }

    public static void fireBlockChanged(ServerLevel level, BlockPos pos) {
        for (int i = 0; i < BLOCK.size(); i++) BLOCK.get(i).changed(level, pos);
    }

    public static void fireAdvancementEarned(ServerPlayer player, FId id) {
        for (int i = 0; i < ADVANCEMENT.size(); i++) ADVANCEMENT.get(i).accept(player, id);
    }

    public static void fireEffectsChanged(LivingEntity entity) {
        for (int i = 0; i < EFFECTS.size(); i++) EFFECTS.get(i).accept(entity);
    }

    public static void fireTargetChanged(Mob mob, LivingEntity target) {
        for (int i = 0; i < TARGET.size(); i++) TARGET.get(i).accept(mob, target);
    }

    public static void fireDataReloaded(MinecraftServer server) {
        for (int i = 0; i < RELOADED.size(); i++) RELOADED.get(i).accept(server);
    }
}
