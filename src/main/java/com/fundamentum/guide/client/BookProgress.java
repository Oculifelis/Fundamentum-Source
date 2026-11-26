package com.fundamentum.guide.client;

import com.fundamentum.guide.book.BookData;
import com.fundamentum.port.FId;
import com.fundamentum.port.FVanilla;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A player's guide book progress, worked out on the client: kills and pick-ups from the vanilla statistics, unlocks
 * from Fundamentum's unlocks, and "seen" from a check every second for mobs within 24 blocks. Seen mobs and the stamps
 * already pressed are kept per world in {@code config/fundamentum-book-progress.json}. Client only, shared.
 */
public final class BookProgress {
    private BookProgress() { }

    private static final Path FILE = Path.of("config", "fundamentum-book-progress.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double SEEN_RANGE = 24;
    private static JsonObject root;
    private static String worldKey;
    private static final Set<String> SEEN = new LinkedHashSet<>();
    private static final Set<String> PRESSED = new LinkedHashSet<>();
    private static boolean dirty;
    private static int ticks;

    /** Called every client tick: notes mobs in view once a second and saves now and then. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (worldKey != null) save();
            worldKey = null;
            return;
        }
        if (++ticks % 20 != 0) return;
        world();
        double r2 = SEEN_RANGE * SEEN_RANGE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || e.distanceToSqr(mc.player) > r2) continue;
            String id = FVanilla.idOf(e.getType()).toString();
            if (SEEN.add(id)) dirty = true;
        }
        if (dirty && ticks % 200 == 0) save();
    }

    /** The world's key: its save name in single player, the server address otherwise. */
    private static void world() {
        Minecraft mc = Minecraft.getInstance();
        String key;
        if (mc.getSingleplayerServer() != null) key = "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        else key = "mp:" + (mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "unknown");
        if (key.equals(worldKey)) return;
        if (worldKey != null) save();
        worldKey = key;
        load();
    }

    private static void load() {
        SEEN.clear();
        PRESSED.clear();
        if (root == null) {
            root = new JsonObject();
            try {
                if (Files.isRegularFile(FILE)) root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception ex) {
                root = new JsonObject();
            }
        }
        JsonObject w = root.has(worldKey) ? root.getAsJsonObject(worldKey) : null;
        if (w == null) return;
        if (w.has("seen")) w.getAsJsonArray("seen").forEach(e -> SEEN.add(e.getAsString()));
        if (w.has("pressed")) w.getAsJsonArray("pressed").forEach(e -> PRESSED.add(e.getAsString()));
    }

    public static void save() {
        if (worldKey == null || root == null) return;
        JsonObject w = new JsonObject();
        JsonArray seen = new JsonArray();
        SEEN.forEach(seen::add);
        JsonArray pressed = new JsonArray();
        PRESSED.forEach(pressed::add);
        w.add("seen", seen);
        w.add("pressed", pressed);
        root.add(worldKey, w);
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Exception ex) {
            // a read-only config folder only loses the seen list
        }
    }

    /** Asks the server for fresh statistics (sent when a book opens). */
    public static void requestStats() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
                net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.REQUEST_STATS));
        }
        world();
    }

    /** How far along a stamp is: {done so far, needed}. */
    public static int[] count(BookData.Stamp s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return new int[] {0, s.count()};
        int have = 0;
        switch (s.kind()) {
            case "kill" -> {
                EntityType<?> t = FVanilla.entityType(s.target());
                if (t != null) have = mc.player.getStats().getValue(net.minecraft.stats.Stats.ENTITY_KILLED.get(t));
            }
            case "seen" -> have = SEEN.contains(s.target().toString()) ? s.count() : 0;
            case "obtain" -> {
                Item item = FVanilla.item(s.target());
                if (item != null) {
                    have = mc.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_PICKED_UP.get(item))
                        + mc.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_CRAFTED.get(item));
                    if (have < s.count()) {
                        int held = 0;
                        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
                            ItemStack st = mc.player.getInventory().getItem(i);
                            if (st.is(item)) held += st.getCount();
                        }
                        have = Math.max(have, held);
                    }
                }
            }
            case "unlock" -> have = com.fundamentum.unlock.Unlocks.clientHas(s.target()) ? s.count() : 0;
            default -> { }
        }
        return new int[] {Math.min(have, s.count()), s.count()};
    }

    public static boolean done(BookData.Stamp s) {
        int[] c = count(s);
        return c[0] >= c[1];
    }

    /** Whether the player has seen this mob in this world. */
    public static boolean seen(FId entity) {
        if (Minecraft.getInstance().level != null) world();
        return entity != null && SEEN.contains(entity.toString());
    }

    public static int kills(FId entity) {
        Minecraft mc = Minecraft.getInstance();
        EntityType<?> t = entity == null ? null : FVanilla.entityType(entity);
        return mc.player == null || t == null ? 0 : mc.player.getStats().getValue(net.minecraft.stats.Stats.ENTITY_KILLED.get(t));
    }

    /** Whether this stamp's press was already shown; marks it shown. */
    public static boolean firstPress(FId entry, int index) {
        String key = entry + "#" + index;
        if (worldKey == null || !PRESSED.add(key)) return false;
        dirty = true;
        save();
        return true;
    }
}
