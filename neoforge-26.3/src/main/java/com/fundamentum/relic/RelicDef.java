package com.fundamentum.relic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One relic's declaration, read from {@code data/<namespace>/fundamentum/relics/<name>.json} (the block and item are
 * {@code <namespace>:<name>}). Plain data with no game classes in it, so it is read the same way at registration (from
 * the owning mod's own jar) and on every data reload (where a datapack may retune it). Ids are kept as strings. Lengths
 * are in sixteenths of a block (pixels) unless a field says otherwise. Every field but {@code boss} is optional; the
 * defaults are Ultima Ratio's relics. The fields are listed in the README's Relics section.
 */
public final class RelicDef {
    public enum Size { SINGLE, GRAND }

    /** How a relic reaches the player: in the boss's drops, straight to the killer's inventory, or not at all (the mod hands it out). */
    public enum Drop { DROPS, KILLER, NONE }

    /** The ramp a figure's textures are gilded on, dark to light (RelicGold), and how far up it the darkest texel starts. */
    public static final int[] GOLD = {0x5A3A12, 0x9A6A1E, 0xD6A238, 0xF2CC5A, 0xFFF0A8};
    public static final float GOLD_FLOOR = 0.7F;

    /** The base: drawn by the block's own model, or by the renderer from an entity model layer. */
    public static final class Base {
        /** Model layer ({@code ns:path} or {@code ns:path#layer}); null: the block model is the base. */
        public String layer;
        public String texture;
        public String glow;
        /** Where the figure's float starts, over the block's floor. */
        public float top;
    }

    /** The figure: the boss's own renderer in gold (entity mode), or a ready-made model with its own textures (model mode). */
    public static final class Figure {
        /** Entity mode: the entity drawn (default the boss) and the phases shown, one figure each. */
        public String entity;
        public int[] phases = {1};
        /** The circle the figure sweeps as it turns and its height, in pixels. */
        public float wide;
        public float tall;
        /** The figure's lowest point floats this far over the base's top. */
        public float floatBy = 2.5F;
        /** With several phases: how far from the middle each figure stands, in blocks. */
        public float radius = 0.92F;
        /** Measured bounds override, in blocks at scale 1: min x, y, z, max x, y, z. Null: measured on the model. */
        public float[] bounds;
        /** Scale override (entity mode) or the model's scale (model mode); NaN: fit to wide and tall. */
        public float scale = Float.NaN;
        /** Model mode: the layer, its gold texture and optional glow texture, and the root offset in model pixels (y down). */
        public String layer;
        public String texture;
        public String glow;
        public float[] offset = {0F, 0F, 0F};

        public boolean model() {
            return this.layer != null;
        }
    }

    public String boss;
    public Size size = Size.SINGLE;
    /** Grand relics: the filler block's name, in the relic's namespace. */
    public String part;
    public String rarity;
    public int stack = 16;
    public boolean fireResistant = true;
    public float strength = 2.0F;
    public float resistance = 6.0F;
    public int light = 9;
    public int partLight = 6;
    /** Outline boxes, in pixels: x1, y1, z1, x2, y2, z2 each. */
    public List<float[]> shape = new ArrayList<>();
    public List<float[]> partShape = new ArrayList<>();
    public Drop drop = Drop.NONE;
    /** Translation keys; null: the defaults ({@code <block key>.tooltip}, {@code jei.<ns>.info.<name>}) when the lang has them. */
    public String description;
    public String jei;
    public final Base base = new Base();
    public final Figure figure = new Figure();
    public int[] tint = GOLD.clone();
    public float tintFloor = GOLD_FLOOR;
    /** Bob: how high (pixels) and how long one bob takes (ticks); centred bobs swing round the rest height instead of over it. */
    public float bobHeight = 1.0F;
    public float bobPeriod = 100.0F;
    public boolean bobCentred;
    /** Bob: ticks added to the clock, to shift where in its swing it starts. */
    public float bobPhase;
    /** One slow turn, in ticks. */
    public float turnTicks = RelicSpin.TURN_TICKS;

    public boolean grand() {
        return this.size == Size.GRAND;
    }

    /** Reads a declaration; {@code name} is the relic's block name (for the default part name). Throws on a bad file. */
    public static RelicDef parse(JsonObject json, String name) {
        RelicDef d = new RelicDef();
        if (!json.has("boss")) {
            throw new IllegalArgumentException("relic " + name + ": no boss");
        }
        d.boss = json.get("boss").getAsString();
        d.size = Size.valueOf(str(json, "size", "single").toUpperCase(Locale.ROOT));
        boolean grand = d.grand();
        d.part = str(json, "part", grand ? name + "_part" : null);
        d.rarity = str(json, "rarity", grand ? "epic" : "rare").toLowerCase(Locale.ROOT);
        d.stack = num(json, "stack", d.stack).intValue();
        d.fireResistant = json.has("fire_resistant") ? json.get("fire_resistant").getAsBoolean() : d.fireResistant;
        d.strength = num(json, "strength", d.strength).floatValue();
        d.resistance = num(json, "resistance", d.resistance).floatValue();
        d.light = num(json, "light", d.light).intValue();
        d.partLight = num(json, "part_light", d.partLight).intValue();
        d.shape = boxes(json, "shape", grand ? List.of(new float[] {0, 0, 0, 16, 4.5F, 16}, new float[] {1, 0, 1, 15, 15, 15})
            : List.of(new float[] {1, 0, 1, 15, 9, 15}));
        d.partShape = boxes(json, "part_shape", List.of(new float[] {0, 0, 0, 16, 4.5F, 16}));
        d.drop = Drop.valueOf(str(json, "drop", "none").toUpperCase(Locale.ROOT));
        d.description = str(json, "description", null);
        d.jei = str(json, "jei", null);

        JsonObject base = json.has("base") ? json.getAsJsonObject("base") : new JsonObject();
        d.base.layer = str(base, "layer", null);
        d.base.texture = str(base, "texture", null);
        d.base.glow = str(base, "glow", null);
        d.base.top = num(base, "top", grand ? 14.8F : 8.6F).floatValue();
        if (d.base.layer != null && d.base.texture == null) {
            throw new IllegalArgumentException("relic " + name + ": base.layer needs base.texture");
        }

        JsonObject fig = json.has("figure") ? json.getAsJsonObject("figure") : new JsonObject();
        Figure f = d.figure;
        f.layer = str(fig, "layer", null);
        f.texture = str(fig, "texture", null);
        f.glow = str(fig, "glow", null);
        if (f.layer != null && f.texture == null) {
            throw new IllegalArgumentException("relic " + name + ": figure.layer needs figure.texture");
        }
        f.entity = str(fig, "entity", d.boss);
        if (fig.has("phases")) {
            JsonArray a = fig.getAsJsonArray("phases");
            f.phases = new int[a.size()];
            for (int i = 0; i < a.size(); i++) {
                f.phases[i] = a.get(i).getAsInt();
            }
            if (f.phases.length == 0) {
                throw new IllegalArgumentException("relic " + name + ": figure.phases is empty");
            }
        }
        boolean trio = f.phases.length > 1;
        f.wide = num(fig, "wide", grand ? (trio ? 26F : 46F) : 18F).floatValue();
        f.tall = num(fig, "tall", grand ? (trio ? 36F : 50F) : 21F).floatValue();
        f.floatBy = num(fig, "float", f.floatBy).floatValue();
        f.radius = num(fig, "radius", f.radius).floatValue();
        f.scale = num(fig, "scale", Float.NaN).floatValue();
        if (fig.has("bounds")) {
            f.bounds = floats(fig.get("bounds"), 6, "figure.bounds");
        }
        if (fig.has("offset")) {
            f.offset = floats(fig.get("offset"), 3, "figure.offset");
        }
        if (f.model() && Float.isNaN(f.scale)) {
            f.scale = 1F;
        }

        if (json.has("tint")) {
            JsonArray a = json.getAsJsonArray("tint");
            if (a.size() < 2) {
                throw new IllegalArgumentException("relic " + name + ": tint needs at least two colours");
            }
            d.tint = new int[a.size()];
            for (int i = 0; i < a.size(); i++) {
                d.tint[i] = colour(a.get(i).getAsString());
            }
        }
        d.tintFloor = num(json, "tint_floor", d.tintFloor).floatValue();
        JsonObject bob = json.has("bob") ? json.getAsJsonObject("bob") : new JsonObject();
        d.bobHeight = num(bob, "height", d.bobHeight).floatValue();
        d.bobPeriod = Math.max(1F, num(bob, "period", d.bobPeriod).floatValue());
        d.bobCentred = bob.has("centred") && bob.get("centred").getAsBoolean();
        d.bobPhase = num(bob, "phase", 0F).floatValue();
        d.turnTicks = Math.max(20F, num(json, "turn_ticks", d.turnTicks).floatValue());
        return d;
    }

    /** The bob over the rest height at {@code time} ticks, in blocks. */
    public float bob(float time) {
        float h = this.bobHeight / 16F;
        float v = h * 0.5F * (1F - (float) Math.cos((time + this.bobPhase) * 2 * Math.PI / this.bobPeriod));
        return this.bobCentred ? v - h * 0.5F : v;
    }

    /** {@code #RRGGBB}, {@code RRGGBB} or {@code 0xRRGGBB}. */
    static int colour(String s) {
        String t = s.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        } else if (t.startsWith("0x") || t.startsWith("0X")) {
            t = t.substring(2);
        }
        return Integer.parseInt(t, 16) & 0xFFFFFF;
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    private static Number num(JsonObject o, String key, Number fallback) {
        return o.has(key) ? o.get(key).getAsNumber() : fallback;
    }

    private static float[] floats(JsonElement e, int n, String what) {
        JsonArray a = e.getAsJsonArray();
        if (a.size() != n) {
            throw new IllegalArgumentException(what + " needs " + n + " numbers");
        }
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = a.get(i).getAsFloat();
        }
        return out;
    }

    private static List<float[]> boxes(JsonObject o, String key, List<float[]> fallback) {
        if (!o.has(key)) {
            return new ArrayList<>(fallback);
        }
        List<float[]> out = new ArrayList<>();
        JsonArray a = o.getAsJsonArray(key);
        // one box ([x1, y1, z1, x2, y2, z2]) or a list of them
        if (a.size() > 0 && a.get(0).isJsonPrimitive()) {
            out.add(floats(a, 6, key));
        } else {
            for (JsonElement e : a) {
                out.add(floats(e, 6, key));
            }
        }
        return out;
    }
}
