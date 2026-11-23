package com.fundamentum.hud;

import com.fundamentum.client.HudBox;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where each HUD element sits: an anchor (one of nine screen points, or beside the hotbar), an x/y offset in GUI
 * pixels, a scale and an opacity. Every element has a default from the mod that draws it, and the player's changes
 * (from the HUD editor or {@code config/fundamentum-hud-layout.json}) override it. On and off and the draw order live in
 * {@link HudLayers}. Elements are keyed by their id as text ({@code "mymod:mana_bar"}). No Minecraft classes, so it is
 * unit tested.
 *
 * <p>{@link #version()} goes up on every change, so a drawer can keep its layout cached until the number moves.
 */
public final class HudLayout {
    /** Smallest and largest scale and opacity a placement can have. */
    public static final float MIN_SCALE = 0.25F;
    public static final float MAX_SCALE = 4.0F;
    public static final float MIN_OPACITY = 0.1F;

    /** Where one element goes. Scale and opacity are kept in range. */
    public record Placement(HudBox.Anchor anchor, int x, int y, float scale, float opacity) {
        public Placement {
            if (anchor == null) anchor = HudBox.Anchor.TOP_LEFT;
            scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Float.isFinite(scale) ? scale : 1));
            opacity = Math.max(MIN_OPACITY, Math.min(1, Float.isFinite(opacity) ? opacity : 1));
        }

        public Placement(HudBox.Anchor anchor, int x, int y) {
            this(anchor, x, y, 1, 1);
        }

        public Placement moved(int dx, int dy) {
            return new Placement(this.anchor, this.x + dx, this.y + dy, this.scale, this.opacity);
        }

        public Placement withScale(float s) {
            return new Placement(this.anchor, this.x, this.y, Math.round(s * 20) / 20F, this.opacity);
        }

        public Placement withOpacity(float o) {
            return new Placement(this.anchor, this.x, this.y, this.scale, Math.round(o * 20) / 20F);
        }

        public Placement withAnchor(HudBox.Anchor a, int nx, int ny) {
            return new Placement(a, nx, ny, this.scale, this.opacity);
        }
    }

    /** The placement of an element nobody declared. */
    public static final Placement FALLBACK = new Placement(HudBox.Anchor.TOP_LEFT, 0, 0);

    private static final Map<String, Placement> DEFAULTS = new ConcurrentHashMap<>();
    private static final Map<String, Placement> CHANGED = new ConcurrentHashMap<>();
    private static final Map<String, JsonObject> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger VERSION = new AtomicInteger();

    private HudLayout() {
    }

    /** Sets the element's default placement (what reset goes back to). */
    public static void setDefault(String id, Placement p) {
        DEFAULTS.put(id, p);
        JsonObject saved = PENDING.remove(id);
        if (saved != null) {
            Placement mine = read(saved, p);
            if (mine != null && !mine.equals(p)) CHANGED.put(id, mine);
        }
        VERSION.incrementAndGet();
    }

    public static Placement defaultOf(String id) {
        return DEFAULTS.getOrDefault(id, FALLBACK);
    }

    /** The placement in use: the player's if there is one, else the default. */
    public static Placement get(String id) {
        Placement p = CHANGED.get(id);
        return p != null ? p : defaultOf(id);
    }

    /** Whether the player moved, scaled or faded this element. */
    public static boolean changed(String id) {
        return CHANGED.containsKey(id);
    }

    /** Sets the player's placement; null (or the default itself) goes back to the default. */
    public static void set(String id, Placement p) {
        if (p == null || p.equals(DEFAULTS.get(id))) CHANGED.remove(id);
        else CHANGED.put(id, p);
        VERSION.incrementAndGet();
    }

    /**
     * Takes the player's saved fields for an element. Fields left out come from the element's default, so an element
     * that is not declared yet keeps them until it is.
     */
    public static void load(String id, JsonObject saved) {
        if (DEFAULTS.containsKey(id)) {
            set(id, read(saved, DEFAULTS.get(id)));
        } else if (read(saved, FALLBACK) != null) {
            PENDING.put(id, saved);
        }
    }

    /** Saved fields for elements not declared yet, kept so saving doesn't lose them. */
    public static Map<String, JsonObject> pending() {
        return Map.copyOf(PENDING);
    }

    /** Drops every player placement. */
    public static void resetAll() {
        CHANGED.clear();
        PENDING.clear();
        VERSION.incrementAndGet();
    }

    /** The player's placements, for saving. */
    public static Map<String, Placement> changes() {
        return Map.copyOf(CHANGED);
    }

    /** Goes up on every change to a placement, a default or a switch, so cached layouts know to redo themselves. */
    public static int version() {
        return VERSION.get();
    }

    /** Marks a change made elsewhere (an element turned on or off, the order changed). */
    public static void touch() {
        VERSION.incrementAndGet();
    }

    /**
     * The element's top left on screen, {x, y}, for a box {@code w} by {@code h} before scaling, kept on screen.
     * {@code hotbarX} is the first free x right of the hotbar.
     */
    public static int[] place(Placement p, int guiW, int guiH, int w, int h, int hotbarX) {
        int sw = (int) Math.ceil(w * p.scale());
        int sh = (int) Math.ceil(h * p.scale());
        return HudBox.place(p.anchor(), guiW, guiH, Math.min(sw, guiW), Math.min(sh, guiH), hotbarX, p.x(), p.y());
    }

    /**
     * The offset that puts a box (already scaled) with its top left at {@code left}, {@code top} when it sits against
     * {@code anchor}: the editor uses it to keep an element where it is when its anchor changes.
     */
    public static int[] offsetFor(HudBox.Anchor anchor, int guiW, int guiH, int sw, int sh, int hotbarX, int left, int top) {
        int[] zero = HudBox.place(anchor, guiW, guiH, sw, sh, hotbarX, 0, 0);
        return new int[] {left - zero[0], top - zero[1]};
    }

    /** The nearest of the nine screen anchors to a box's centre. */
    public static HudBox.Anchor nearest(int guiW, int guiH, int left, int top, int sw, int sh) {
        float cx = (left + sw / 2F) / Math.max(1, guiW);
        float cy = (top + sh / 2F) / Math.max(1, guiH);
        float fx = cx < 1 / 3F ? 0 : cx > 2 / 3F ? 1 : 0.5F;
        float fy = cy < 1 / 3F ? 0 : cy > 2 / 3F ? 1 : 0.5F;
        for (HudBox.Anchor a : HudBox.Anchor.values()) {
            if (a != HudBox.Anchor.HOTBAR_RIGHT && a.fx == fx && a.fy == fy) return a;
        }
        return HudBox.Anchor.TOP_LEFT;
    }

    // ---- JSON -------------------------------------------------------------------------------------------------------

    /** Writes a placement's fields into an element's JSON object. */
    public static void write(Placement p, JsonObject o) {
        o.addProperty("anchor", p.anchor().name().toLowerCase(java.util.Locale.ROOT));
        o.addProperty("x", p.x());
        o.addProperty("y", p.y());
        o.addProperty("scale", p.scale());
        o.addProperty("opacity", p.opacity());
    }

    /** Reads the placement fields of an element's JSON object over {@code base}; null when it has none. */
    public static Placement read(JsonObject o, Placement base) {
        if (!o.has("anchor") && !o.has("x") && !o.has("y") && !o.has("scale") && !o.has("opacity")) return null;
        HudBox.Anchor anchor = base.anchor();
        if (o.has("anchor")) {
            try {
                anchor = HudBox.Anchor.valueOf(o.get("anchor").getAsString().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // keeps the default anchor
            }
        }
        return new Placement(anchor,
            o.has("x") ? o.get("x").getAsInt() : base.x(),
            o.has("y") ? o.get("y").getAsInt() : base.y(),
            o.has("scale") ? o.get("scale").getAsFloat() : base.scale(),
            o.has("opacity") ? o.get("opacity").getAsFloat() : base.opacity());
    }
}
