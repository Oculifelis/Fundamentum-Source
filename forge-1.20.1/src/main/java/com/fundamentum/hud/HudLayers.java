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
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Order and on/off switches for HUD overlays from every mod, so a pack can line up five mods' bars without each mod
 * guessing. A layer is an id with an order (lower draws first, so higher ends up on top; ties go by id) and a default on
 * or off. Code ({@link #setEnabled}, {@link #setOrder}) and the player's {@code config/fundamentum-hud.json} change both:
 * <pre>{@code {"hide_all": false, "layers": {"mymod:mana_bar": {"enabled": false}, "othermod:heat": {"order": 50}}}}</pre>
 * Settings for a layer that is not registered yet are kept and apply when it is. This class holds no client code; the
 * drawing is in {@code com.fundamentum.hud.client.HudLayerRenderer}, which draws {@link #visible()} in order.
 */
public final class HudLayers {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** A registered layer as declared by its mod. */
    public record Layer(ResourceLocation id, int order, boolean defaultOn) {
    }

    private static final Map<ResourceLocation, Layer> LAYERS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Boolean> ENABLED = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Integer> ORDER = new ConcurrentHashMap<>();
    private static volatile boolean hideAll;

    private HudLayers() {
    }

    /** Declares a layer, on by default. Registering the same id again replaces its order and default. */
    public static Layer register(ResourceLocation id, int order) {
        return register(id, order, true);
    }

    public static Layer register(ResourceLocation id, int order, boolean defaultOn) {
        Layer layer = new Layer(id, order, defaultOn);
        LAYERS.put(id, layer);
        return layer;
    }

    public static boolean unregister(ResourceLocation id) {
        return LAYERS.remove(id) != null;
    }

    public static Optional<Layer> layer(ResourceLocation id) {
        return Optional.ofNullable(LAYERS.get(id));
    }

    /** The order in use: a setting if there is one, else the registered order (0 for an unknown id). */
    public static int order(ResourceLocation id) {
        Integer o = ORDER.get(id);
        if (o != null) return o;
        Layer l = LAYERS.get(id);
        return l == null ? 0 : l.order();
    }

    /** Whether the layer is on (ignores {@link #hideAll}); unknown ids are off. */
    public static boolean enabled(ResourceLocation id) {
        Layer l = LAYERS.get(id);
        if (l == null) return false;
        return ENABLED.getOrDefault(id, l.defaultOn());
    }

    /** Turns a layer on or off; null goes back to its default. Kept for ids not registered yet. */
    public static void setEnabled(ResourceLocation id, Boolean on) {
        if (on == null) ENABLED.remove(id);
        else ENABLED.put(id, on);
    }

    /** Overrides a layer's order; null goes back to the registered one. */
    public static void setOrder(ResourceLocation id, Integer order) {
        if (order == null) ORDER.remove(id);
        else ORDER.put(id, order);
    }

    /** Hides every layer at once (the layers keep their own switches). */
    public static void setHideAll(boolean hide) {
        hideAll = hide;
    }

    public static boolean hideAll() {
        return hideAll;
    }

    /** Drops every setting from code and config (the registered layers stay). */
    public static void resetSettings() {
        ENABLED.clear();
        ORDER.clear();
        hideAll = false;
    }

    /** Every registered layer id in draw order, on or off. */
    public static List<ResourceLocation> ordered() {
        List<ResourceLocation> ids = new ArrayList<>(LAYERS.keySet());
        ids.sort(Comparator.<ResourceLocation>comparingInt(HudLayers::order).thenComparing(ResourceLocation::toString));
        return ids;
    }

    /** The layers to draw now, in draw order: empty while {@link #hideAll}. */
    public static List<ResourceLocation> visible() {
        if (hideAll) return List.of();
        List<ResourceLocation> ids = ordered();
        ids.removeIf(id -> !enabled(id));
        return ids;
    }

    // ---- config file ------------------------------------------------------------------------------------------------

    /** The player's file, {@code config/fundamentum-hud.json} under the game folder. */
    public static Path defaultConfig() {
        return Path.of("config", "fundamentum-hud.json");
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
                    ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                    if (id == null || !e.getValue().isJsonObject()) {
                        LOG.warn("fundamentum-hud: skipping layer entry {}", e.getKey());
                        continue;
                    }
                    JsonObject l = e.getValue().getAsJsonObject();
                    if (l.has("enabled")) ENABLED.put(id, l.get("enabled").getAsBoolean());
                    if (l.has("order")) ORDER.put(id, l.get("order").getAsInt());
                }
            }
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
        List<ResourceLocation> ids = ordered();
        for (ResourceLocation id : ENABLED.keySet()) if (!ids.contains(id)) ids.add(id);
        for (ResourceLocation id : ORDER.keySet()) if (!ids.contains(id)) ids.add(id);
        for (ResourceLocation id : ids) {
            JsonObject l = new JsonObject();
            Layer layer = LAYERS.get(id);
            l.addProperty("order", order(id));
            l.addProperty("enabled", layer != null ? enabled(id) : ENABLED.getOrDefault(id, true));
            layers.add(id.toString(), l);
        }
        root.add("layers", layers);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
    }
}
