package com.fundamentum.flail;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * One flail, resolved: its kind preset (head layout), its material preset (how the head and chain behave), and the
 * flail's own fields laid over both. Read from {@code data/<ns>/fundamentum/flails/<name>.json}; the presets from
 * {@code fundamentum/flail_kinds} and {@code fundamentum/flail_materials}. Every field is listed in the README's flail
 * section. No game types here, so it parses and validates in unit tests and is the same on every version.
 *
 * <p>Fields resolve kind first, then material, then the flail's own file, so a flail can override any preset field.
 * A preset may only hold its own fields (a material can't set {@code head_count}); an unknown field anywhere is an
 * error, so a typo never passes silently.
 */
public final class FlailSpec {
    /** Fields a material preset sets: what the head and chain are made of. */
    public static final Set<String> MATERIAL_KEYS = Set.of("head_mass", "inertia", "stiffness", "damping", "bounce", "impact", "sound",
        "head_color", "chain_color");
    /** Fields a kind preset sets: the head layout. */
    public static final Set<String> KIND_KEYS = Set.of("head_count", "chain_length", "chain_segments", "impact_radius", "spread",
        "chain_hits", "double_ended", "armor_pierce", "wraps_target");
    /** Fields only a flail's own file sets. */
    public static final Set<String> OWN_KEYS = Set.of("item", "physics", "material", "kind", "damage", "spin_up", "max_spin",
        "knockback", "ground_slam", "launch", "cooldown", "swing_sound", "on_hit");

    public static final String DEFAULT_MATERIAL = "fundamentum:iron";
    public static final String DEFAULT_KIND = "fundamentum:single";

    /** A status effect a hit gives: its id, how long, and its level (0 is level I). */
    public record Effect(String id, int seconds, int amplifier) {}

    /** What a head does to what it hits, on top of the damage. */
    public record Impact(List<Effect> effects, int fireSeconds, int freezeTicks, String particle) {
        public static final Impact NONE = new Impact(List.of(), 0, 0, "");
    }

    /**
     * A ground slam: when a head comes down on the ground hard enough (a released spin at {@code minCharge} or more of a
     * full spin, or any head hitting the ground at full speed), creatures within {@code radius} take {@code damage}
     * (less toward the edge) and are thrown up.
     */
    public record Slam(double radius, double damage, double minCharge) {}

    /**
     * Throwing the head out ({@link FlailPhysics#launches}): it flies along the look at {@code speed} blocks a tick (half
     * of that untapped, the full speed after a full spin) up to {@code range} blocks, then the chain reels it back in
     * {@code reelTicks}. {@code pull} is how hard a wrapped target is dragged in with it (0 leaves it where it is).
     */
    public record Launch(double range, double speed, int reelTicks, double pull) {
        public static final Launch DEFAULT = new Launch(8.0, 1.6, 10, 0.0);
    }

    public final String item;
    public final FlailPhysics physics;
    public final String material, kind;
    // The kind's fields.
    public final int headCount;
    public final double chainLength;
    public final int chainSegments;
    public final double impactRadius, spread;
    public final boolean chainHits, doubleEnded;
    public final double armorPierce;
    public final int wrapsTarget;
    // The material's fields.
    public final double headMass, inertia, stiffness, damping, bounce;
    public final Impact impact;
    public final String sound;
    public final int headColor, chainColor;
    // The flail's own fields.
    public final double damage;
    public final int spinUp;
    public final double maxSpin, knockback;
    public final Slam groundSlam;
    public final Launch launch;
    public final int cooldown;
    public final String swingSound;
    public final List<String> onHit;

    private FlailSpec(Reader r, String material, String kind) {
        this.item = r.string("item", "");
        this.physics = FlailPhysics.parse(r.string("physics", "links"));
        this.material = material;
        this.kind = kind;
        this.headCount = r.integer("head_count", 1, 1, 4);
        this.chainLength = r.number("chain_length", 2.0, 0.5, 12.0);
        this.chainSegments = physics.rigid() ? 1 : r.integer("chain_segments", 8, 1, 24);
        this.impactRadius = r.number("impact_radius", 0.35, 0.1, 2.0);
        this.spread = r.number("spread", 20.0, 0.0, 180.0);
        this.chainHits = r.bool("chain_hits", false);
        this.doubleEnded = r.bool("double_ended", false);
        this.armorPierce = r.number("armor_pierce", 0.0, 0.0, 1.0);
        this.wrapsTarget = r.integer("wraps_target", 0, 0, 200);
        this.headMass = r.number("head_mass", 1.0, 0.05, 20.0);
        this.inertia = r.number("inertia", 1.0, 0.25, 4.0);
        this.stiffness = r.number("stiffness", 0.2, 0.0, 1.0);
        this.damping = r.number("damping", 0.06, 0.0, 0.5);
        this.bounce = r.number("bounce", 0.3, 0.0, 1.0);
        this.impact = r.impact("impact");
        this.sound = r.string("sound", "minecraft:entity.player.attack.strong");
        this.headColor = r.color("head_color", 0xFF8A8F96);
        this.chainColor = r.color("chain_color", 0xFF5A5E66);
        this.damage = r.number("damage", 6.0, 0.0, 1000.0);
        this.spinUp = r.integer("spin_up", 20, 0, 200);
        this.maxSpin = r.number("max_spin", 30.0, 1.0, 90.0);
        this.knockback = r.number("knockback", 0.6, 0.0, 10.0);
        this.groundSlam = r.slam("ground_slam");
        this.cooldown = r.integer("cooldown", 14, 0, 400);
        this.swingSound = r.string("swing_sound", "minecraft:entity.player.attack.sweep");
        this.onHit = r.strings("on_hit");
        Launch l = r.launch("launch");
        if (l != null && !physics.launches()) {
            throw new IllegalArgumentException("\"launch\" needs physics launch or hybrid, not " + physics.id());
        }
        this.launch = physics.launches() ? (l == null ? Launch.DEFAULT : l) : null;
        if (wrapsTarget > 0 && physics.rigid()) {
            throw new IllegalArgumentException("\"wraps_target\" needs a chain: a pendulum flail's arm is rigid and can't wrap a target");
        }
        if (doubleEnded && headCount != 2) {
            throw new IllegalArgumentException("\"double_ended\" needs head_count 2 (one head on each end of the chain), not " + headCount);
        }
        if (launch != null && launch.range < chainLength) {
            throw new IllegalArgumentException("launch range " + launch.range + " is shorter than chain_length " + chainLength);
        }
    }

    /** The max spin in radians a tick. */
    public double maxSpinRadians() {
        return Math.toRadians(maxSpin);
    }

    /**
     * The head speed (blocks a tick) that deals the full damage: a head at full spin on the whole chain, a little under it
     * so a clean swing reaches it.
     */
    public double fullSpeed() {
        return Math.max(0.2, maxSpinRadians() * chainLength * 0.8);
    }

    /** The damage multiplier for a head moving at {@code speed} blocks a tick: from 0.2 (barely moving) to 1.25 (over full). */
    public double speedFactor(double speed) {
        double f = speed / fullSpeed();
        return f < 0.2 ? 0.2 : Math.min(1.25, f);
    }

    /** Ticks one left-click swing takes: half a turn at max spin, slower for a heavy, sluggish head. */
    public int swingTicks() {
        int ticks = (int) Math.ceil(Math.PI / maxSpinRadians() * Math.sqrt(inertia));
        return Math.max(4, Math.min(30, ticks));
    }

    /**
     * Resolves a flail file over its presets. {@code materials} and {@code kinds} look a preset up by id (null when there is
     * none). Throws {@link IllegalArgumentException} with a message naming the problem field.
     */
    public static FlailSpec resolve(JsonObject flail, Function<String, JsonObject> materials, Function<String, JsonObject> kinds) {
        checkKeys("flail", flail, null);
        String material = flail.has("material") ? flail.get("material").getAsString() : DEFAULT_MATERIAL;
        String kind = flail.has("kind") ? flail.get("kind").getAsString() : DEFAULT_KIND;
        JsonObject mat = materials.apply(material);
        if (mat == null) {
            throw new IllegalArgumentException("unknown material \"" + material + "\"");
        }
        JsonObject kin = kinds.apply(kind);
        if (kin == null) {
            throw new IllegalArgumentException("unknown kind \"" + kind + "\"");
        }
        checkKeys("material " + material, mat, MATERIAL_KEYS);
        checkKeys("kind " + kind, kin, KIND_KEYS);
        JsonObject merged = new JsonObject();
        kin.entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
        mat.entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
        flail.entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
        return new FlailSpec(new Reader(merged), material, kind);
    }

    /** Resolves against preset maps (as the data tables hold them), for tools and tests. */
    public static FlailSpec resolve(JsonObject flail, Map<String, JsonObject> materials, Map<String, JsonObject> kinds) {
        return resolve(flail, materials::get, kinds::get);
    }

    /** Checks a material or kind preset on its own (load-time error messages). */
    public static void checkPreset(String what, JsonObject preset, boolean material) {
        checkKeys(what, preset, material ? MATERIAL_KEYS : KIND_KEYS);
        // Parse it over empty defaults so a bad value shows up against the preset, not every flail using it.
        try {
            new FlailSpec(new Reader(preset.deepCopy()), "", "");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + ": " + e.getMessage(), e);
        }
    }

    private static void checkKeys(String what, JsonObject json, Set<String> allowed) {
        Set<String> all = new LinkedHashSet<>();
        all.addAll(MATERIAL_KEYS);
        all.addAll(KIND_KEYS);
        all.addAll(OWN_KEYS);
        for (String key : json.keySet()) {
            if (!all.contains(key)) {
                throw new IllegalArgumentException(what + ": unknown field \"" + key + "\"");
            }
            if (allowed != null && !allowed.contains(key)) {
                throw new IllegalArgumentException(what + ": \"" + key + "\" is not a " + (allowed == MATERIAL_KEYS ? "material" : "kind")
                    + " field (it belongs in the flail's own file); allowed: " + new TreeSet<>(allowed));
            }
        }
    }

    /** Typed, range-checked reads of the merged fields. */
    private static final class Reader {
        final JsonObject json;

        Reader(JsonObject json) {
            this.json = json;
        }

        String string(String key, String fallback) {
            JsonElement e = json.get(key);
            if (e == null || e.isJsonNull()) {
                return fallback;
            }
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("\"" + key + "\" must be a string");
            }
            return e.getAsString();
        }

        double number(String key, double fallback, double min, double max) {
            JsonElement e = json.get(key);
            if (e == null || e.isJsonNull()) {
                return fallback;
            }
            return range(key, num(key, e), min, max);
        }

        int integer(String key, int fallback, int min, int max) {
            JsonElement e = json.get(key);
            if (e == null || e.isJsonNull()) {
                return fallback;
            }
            double v = num(key, e);
            if (v != Math.rint(v)) {
                throw new IllegalArgumentException("\"" + key + "\" must be a whole number, not " + v);
            }
            if (v < min || v > max) {
                throw new IllegalArgumentException("\"" + key + "\" is " + (long) v + ", must be " + min + " to " + max);
            }
            return (int) v;
        }

        boolean bool(String key, boolean fallback) {
            JsonElement e = json.get(key);
            if (e == null || e.isJsonNull()) {
                return fallback;
            }
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("\"" + key + "\" must be true or false");
            }
            return e.getAsBoolean();
        }

        int color(String key, int fallback) {
            JsonElement e = json.get(key);
            if (e == null || e.isJsonNull()) {
                return fallback;
            }
            return parseColor(key, e);
        }

        List<String> strings(String key) {
            JsonElement e = json.get(key);
            if (e == null || e.isJsonNull()) {
                return List.of();
            }
            if (e.isJsonPrimitive()) {
                return List.of(e.getAsString());
            }
            if (!e.isJsonArray()) {
                throw new IllegalArgumentException("\"" + key + "\" must be a list of ids");
            }
            List<String> out = new ArrayList<>();
            for (JsonElement s : e.getAsJsonArray()) {
                out.add(s.getAsString());
            }
            return Collections.unmodifiableList(out);
        }

        JsonObject object(String key) {
            JsonElement e = json.get(key);
            if (e == null || e.isJsonNull()) {
                return null;
            }
            if (!e.isJsonObject()) {
                throw new IllegalArgumentException("\"" + key + "\" must be an object");
            }
            return e.getAsJsonObject();
        }

        Impact impact(String key) {
            JsonObject o = object(key);
            if (o == null) {
                return Impact.NONE;
            }
            only(key, o, Set.of("effects", "fire_seconds", "freeze_ticks", "particle"));
            Reader r = new Reader(o);
            List<Effect> effects = new ArrayList<>();
            JsonElement list = o.get("effects");
            if (list != null && !list.isJsonNull()) {
                if (!list.isJsonArray()) {
                    throw new IllegalArgumentException("\"" + key + ".effects\" must be a list");
                }
                for (JsonElement el : list.getAsJsonArray()) {
                    if (!el.isJsonObject()) {
                        throw new IllegalArgumentException("\"" + key + ".effects\" entries must be objects");
                    }
                    JsonObject eo = el.getAsJsonObject();
                    only(key + ".effects", eo, Set.of("id", "seconds", "amplifier"));
                    Reader er = new Reader(eo);
                    String id = er.string("id", "");
                    if (id.isEmpty()) {
                        throw new IllegalArgumentException("\"" + key + ".effects\" entry needs an \"id\"");
                    }
                    effects.add(new Effect(id, er.integer("seconds", 2, 1, 600), er.integer("amplifier", 0, 0, 9)));
                }
            }
            return new Impact(Collections.unmodifiableList(effects), r.integer("fire_seconds", 0, 0, 60), r.integer("freeze_ticks", 0, 0, 600),
                r.string("particle", ""));
        }

        Slam slam(String key) {
            JsonObject o = object(key);
            if (o == null) {
                return null;
            }
            only(key, o, Set.of("radius", "damage", "min_charge"));
            Reader r = new Reader(o);
            return new Slam(r.number("radius", 2.5, 0.5, 12.0), r.number("damage", 6.0, 0.0, 1000.0), r.number("min_charge", 0.6, 0.0, 1.0));
        }

        Launch launch(String key) {
            JsonObject o = object(key);
            if (o == null) {
                return null;
            }
            only(key, o, Set.of("range", "speed", "reel_ticks", "pull"));
            Reader r = new Reader(o);
            return new Launch(r.number("range", Launch.DEFAULT.range(), 1.0, 32.0), r.number("speed", Launch.DEFAULT.speed(), 0.2, 6.0),
                r.integer("reel_ticks", Launch.DEFAULT.reelTicks(), 2, 100), r.number("pull", 0.0, 0.0, 3.0));
        }

        private static void only(String key, JsonObject o, Set<String> allowed) {
            for (String k : o.keySet()) {
                if (!allowed.contains(k)) {
                    throw new IllegalArgumentException("\"" + key + "\": unknown field \"" + k + "\"; allowed: " + new TreeSet<>(allowed));
                }
            }
        }

        private static double num(String key, JsonElement e) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("\"" + key + "\" must be a number");
            }
            double v = e.getAsDouble();
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("\"" + key + "\" must be finite");
            }
            return v;
        }

        private static double range(String key, double v, double min, double max) {
            if (v < min || v > max) {
                throw new IllegalArgumentException("\"" + key + "\" is " + v + ", must be " + min + " to " + max);
            }
            return v;
        }
    }

    /** A colour: {@code "#RRGGBB"}, {@code "#AARRGGBB"} or a number (ARGB; no alpha given means opaque). */
    static int parseColor(String key, JsonElement e) {
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) {
                long v = p.getAsLong();
                return (int) (v > 0xFFFFFFL || v < 0 ? v : v | 0xFF000000L);
            }
            String s = p.getAsString().trim().toLowerCase(Locale.ROOT);
            if (s.startsWith("#")) {
                s = s.substring(1);
            }
            if (s.matches("[0-9a-f]{6}|[0-9a-f]{8}")) {
                long v = Long.parseLong(s, 16);
                return (int) (s.length() == 6 ? v | 0xFF000000L : v);
            }
        }
        throw new IllegalArgumentException("\"" + key + "\" must be a colour like \"#RRGGBB\"");
    }
}
