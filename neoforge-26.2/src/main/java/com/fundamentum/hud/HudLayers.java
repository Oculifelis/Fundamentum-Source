package com.fundamentum.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Order and on/off switches for HUD overlays from every mod, so a pack can line up five mods' bars without each mod
 * guessing. A layer is an id with an order (lower draws first, so higher ends up on top; ties go by id) and a default on
 * or off. Code ({@link #setEnabled}, {@link #setOrder}) and the player's {@code config/fundamentum-hud-layout.json} change
 * both. The same file holds each element's place on screen ({@link HudLayout}), which the in-game HUD editor writes:
 * <pre>{@code {"hide_all": false, "layers": {"mymod:mana_bar": {"enabled": false},
 *   "othermod:heat": {"order": 50, "anchor": "top_right", "x": -4, "y": 4, "scale": 1.5, "opacity": 0.8}}}}</pre>
 * The older {@code config/fundamentum-hud.json} (order and switches only) is read once if the new file is missing, then
 * renamed to {@code fundamentum-hud.json.old}.
 * Settings for a layer that is not registered yet are kept and apply when it is. This class holds no client code; the
 * drawing is in {@code com.fundamentum.hud.client.HudLayerRenderer}, which draws {@link #visible()} in order.
 */
public final class HudLayers {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** A registered layer as declared by its mod. */
    public record Layer(Identifier id, int order, boolean defaultOn) {
    }

    private static final Map<Identifier, Layer> LAYERS = new ConcurrentHashMap<>();
    private static final Map<Identifier, Boolean> ENABLED = new ConcurrentHashMap<>();
    private static final Map<Identifier, Integer> ORDER = new ConcurrentHashMap<>();
    private static volatile boolean hideAll;

    private HudLayers() {
    }

    /** Declares a layer, on by default. Registering the same id again replaces its order and default. */
    public static Layer register(Identifier id, int order) {
        return register(id, order, true);
    }

    public static Layer register(Identifier id, int order, boolean defaultOn) {
        Layer layer = new Layer(id, order, defaultOn);
        LAYERS.put(id, layer);
        HudLayout.touch();
        return layer;
    }

    public static boolean unregister(Identifier id) {
        HudLayout.touch();
        return LAYERS.remove(id) != null;
    }

    public static Optional<Layer> layer(Identifier id) {
        return Optional.ofNullable(LAYERS.get(id));
    }

    /** The order in use: a setting if there is one, else the registered order (0 for an unknown id). */
    public static int order(Identifier id) {
        Integer o = ORDER.get(id);
        if (o != null) return o;
        Layer l = LAYERS.get(id);
        return l == null ? 0 : l.order();
    }

    /** Whether the layer is on (ignores {@link #hideAll}); unknown ids are off. */
    public static boolean enabled(Identifier id) {
        Layer l = LAYERS.get(id);
        if (l == null) return false;
        return ENABLED.getOrDefault(id, l.defaultOn());
    }

    /** Turns a layer on or off; null goes back to its default. Kept for ids not registered yet. */
    public static void setEnabled(Identifier id, Boolean on) {
        if (on == null) ENABLED.remove(id);
        else ENABLED.put(id, on);
        HudLayout.touch();
    }

    /** Overrides a layer's order; null goes back to the registered one. */
    public static void setOrder(Identifier id, Integer order) {
        if (order == null) ORDER.remove(id);
        else ORDER.put(id, order);
        HudLayout.touch();
    }

    /** Hides every layer at once (the layers keep their own switches). */
    public static void setHideAll(boolean hide) {
        hideAll = hide;
        HudLayout.touch();
    }

    public static boolean hideAll() {
        return hideAll;
    }

    /** Drops every setting from code and config (the registered layers stay). */
    public static void resetSettings() {
        ENABLED.clear();
        ORDER.clear();
        hideAll = false;
        HudLayout.resetAll();
    }

    /** Every registered layer id in draw order, on or off. */
    public static List<Identifier> ordered() {
        List<Identifier> ids = new ArrayList<>(LAYERS.keySet());
        ids.sort(Comparator.<Identifier>comparingInt(HudLayers::order).thenComparing(Identifier::toString));
        return ids;
    }

    /** The layers to draw now, in draw order: empty while {@link #hideAll}. */
    public static List<Identifier> visible() {
        if (hideAll) return List.of();
        List<Identifier> ids = ordered();
        ids.removeIf(id -> !enabled(id));
        return ids;
    }

    // ---- config file ------------------------------------------------------------------------------------------------

    /** The player's file, {@code config/fundamentum-hud-layout.json} under the game folder. */
    public static Path defaultConfig() {
        return Path.of("config", "fundamentum-hud-layout.json");
    }

    /** The older file with order and switches only, read once by {@link #loadDefault()} when the new one is missing. */
    public static Path legacyConfig() {
        return Path.of("config", "fundamentum-hud.json");
    }

    /**
     * Reads {@link #defaultConfig()}; when it is missing but {@link #legacyConfig()} exists, reads that, writes the new
     * file and renames the old one so it is not read again.
     */
    public static boolean loadDefault() {
        Path file = defaultConfig();
        Path old = legacyConfig();
        if (Files.isRegularFile(file) || !Files.isRegularFile(old)) return loadConfig(file);
        boolean ok = loadConfig(old);
        if (!ok) return false;
        try {
            saveConfig(file);
            Files.move(old, old.resolveSibling(old.getFileName() + ".old"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOG.info("fundamentum-hud: moved {} into {}", old, file);
        } catch (IOException e) {
            LOG.warn("fundamentum-hud: could not move {} into {}: {}", old, file, e.toString());
        }
        return true;
    }

    /** Saves to {@link #defaultConfig()}, logging a failure instead of throwing. */
    public static void saveDefault() {
        try {
            saveConfig(defaultConfig());
        } catch (IOException e) {
            LOG.warn("fundamentum-hud: could not save {}: {}", defaultConfig(), e.toString());
        }
    }

    /** Reads a settings file over the current settings; a missing file changes nothing. Returns false on a bad file. */
    public static boolean loadConfig(Path file) {
        if (!Files.isRegularFile(file)) return true;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) throw new IllegalStateException("not an object");
            JsonObject o = root.getAsJsonObject();
            if (o.has("hide_all")) hideAll = o.get("hide_all").getAsBoolean();
            if (o.has("layers")) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("layers").entrySet()) {
                    Identifier id = Identifier.tryParse(e.getKey());
                    if (id == null || !e.getValue().isJsonObject()) {
                        LOG.warn("fundamentum-hud: skipping layer entry {}", e.getKey());
                        continue;
                    }
                    JsonObject l = e.getValue().getAsJsonObject();
                    if (l.has("enabled")) ENABLED.put(id, l.get("enabled").getAsBoolean());
                    if (l.has("order")) ORDER.put(id, l.get("order").getAsInt());
                    HudLayout.load(id.toString(), l);
                }
            }
            HudLayout.touch();
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("fundamentum-hud: could not read {}: {}", file, e.toString());
            return false;
        }
    }

    /** Writes every registered layer with its current order and switch, plus any settings for unregistered ids. */
    public static void saveConfig(Path file) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("hide_all", hideAll);
        JsonObject layers = new JsonObject();
        List<Identifier> ids = ordered();
        for (Identifier id : ENABLED.keySet()) if (!ids.contains(id)) ids.add(id);
        for (Identifier id : ORDER.keySet()) if (!ids.contains(id)) ids.add(id);
        Map<String, JsonObject> pending = HudLayout.pending();
        for (Identifier id : ids) {
            JsonObject l = new JsonObject();
            Layer layer = LAYERS.get(id);
            l.addProperty("order", order(id));
            l.addProperty("enabled", layer != null ? enabled(id) : ENABLED.getOrDefault(id, true));
            String key = id.toString();
            if (HudLayout.changed(key)) HudLayout.write(HudLayout.get(key), l);
            else if (pending.containsKey(key)) for (Map.Entry<String, JsonElement> f : pending.get(key).entrySet()) if (!l.has(f.getKey())) l.add(f.getKey(), f.getValue());
            layers.add(key, l);
        }
        for (Map.Entry<String, HudLayout.Placement> e : HudLayout.changes().entrySet()) {
            if (layers.has(e.getKey())) continue;
            JsonObject l = new JsonObject();
            HudLayout.write(e.getValue(), l);
            layers.add(e.getKey(), l);
        }
        root.add("layers", layers);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
    }
}
