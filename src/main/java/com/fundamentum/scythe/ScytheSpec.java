package com.fundamentum.scythe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One scythe: {@code data/<ns>/fundamentum/scythes/<name>.json}. A scythe is a list of moves it opts into, each a data
 * block under {@code "moves"}. Every field is listed in the README's scythe section. No game types here, so it parses and
 * validates in unit tests and is the same on every version.
 *
 * <p>Moves:
 * <ul>
 * <li>{@code sweet_spot} (passive): hits landed with the blade's tip, between {@code min} and {@code max} blocks, deal
 * {@code multiplier} times; hits closer in, with the haft, deal {@code inside} times.</li>
 * <li>{@code backswing} (passive): a second left-click within {@code window} ticks of a swing cuts back the other way for
 * {@code damage} times. With Better Combat it is the preset's second attack instead.</li>
 * <li>{@code whirl} (held): spin the scythe round you; it hits everything within {@code radius} every {@code hit_every}
 * ticks, building from {@code min_damage} to {@code max_damage} times over {@code build_ticks}.</li>
 * <li>{@code reap_pull} (tap): hook everything in an {@code arc} in front out to {@code range} and drag it in.</li>
 * <li>{@code crescent_wave} (tap): send a crescent of air out along the look that cuts what it passes through.</li>
 * <li>{@code harvest_souls} (passive): kills with the scythe gather souls that make it hit harder and heal you.</li>
 * </ul>
 * Two held or tapped moves can't share an input.
 */
public final class ScytheSpec {
    /** The moves a scythe can opt into. */
    public static final Set<String> MOVES = Set.of("sweet_spot", "backswing", "whirl", "reap_pull", "crescent_wave", "harvest_souls");
    /** The buttons a held or tapped move goes on: right-click, or sneak and right-click. */
    public static final Set<String> INPUTS = Set.of("use", "sneak_use");

    public record SweetSpot(double min, double max, double multiplier, double inside, String sound) {}

    public record Backswing(double damage, int window, double arc) {}

    public record Whirl(String input, double radius, int buildTicks, double minDamage, double maxDamage, int hitEvery, int maxTicks,
                        double slow, int cooldown) {}

    public record ReapPull(String input, double range, double arc, double pull, double damage, int cooldown) {}

    public record CrescentWave(String input, double range, double speed, double width, double damage, int pierce, int cooldown,
                               String particle) {}

    public record HarvestSouls(int maxSouls, int decayTicks, double empowerPerSoul, double healPerSoul) {}

    public final String item;
    /** The scythe's base damage for its own moves (its left-click damage is the item's attack damage). */
    public final double damage;
    /** How far its swing reaches, blocks. */
    public final double reach;
    public final SweetSpot sweetSpot;
    public final Backswing backswing;
    public final Whirl whirl;
    public final ReapPull reapPull;
    public final CrescentWave crescentWave;
    public final HarvestSouls harvestSouls;
    /** Each input's move ({@code use} / {@code sneak_use} to {@code whirl}, {@code reap_pull} or {@code crescent_wave}). */
    public final Map<String, String> inputs;

    private ScytheSpec(JsonObject json) {
        only("scythe", json, Set.of("item", "damage", "reach", "moves"));
        R r = new R("", json);
        this.item = r.string("item", "");
        this.damage = r.number("damage", 7.0, 0.0, 1000.0);
        this.reach = r.number("reach", 3.0, 1.0, 8.0);
        JsonObject moves = r.object("moves");
        if (moves == null || moves.size() == 0) {
            throw new IllegalArgumentException("a scythe needs at least one move under \"moves\"; known moves: " + new TreeSet<>(MOVES));
        }
        for (String m : moves.keySet()) {
            if (!MOVES.contains(m)) {
                throw new IllegalArgumentException("unknown move \"" + m + "\"; known moves: " + new TreeSet<>(MOVES));
            }
        }
        R m = new R("moves", moves);
        this.sweetSpot = sweetSpot(m.object("sweet_spot"));
        this.backswing = backswing(m.object("backswing"));
        this.whirl = whirl(m.object("whirl"));
        this.reapPull = reapPull(m.object("reap_pull"));
        this.crescentWave = crescentWave(m.object("crescent_wave"));
        this.harvestSouls = harvestSouls(m.object("harvest_souls"));
        Map<String, String> in = new LinkedHashMap<>();
        bind(in, "whirl", whirl == null ? null : whirl.input());
        bind(in, "reap_pull", reapPull == null ? null : reapPull.input());
        bind(in, "crescent_wave", crescentWave == null ? null : crescentWave.input());
        this.inputs = Collections.unmodifiableMap(in);
    }

    private static void bind(Map<String, String> in, String move, String input) {
        if (input == null) {
            return;
        }
        String other = in.putIfAbsent(input, move);
        if (other != null) {
            throw new IllegalArgumentException("\"" + other + "\" and \"" + move + "\" are both on input \"" + input
                + "\"; give one of them \"input\": \"" + (input.equals("use") ? "sneak_use" : "use") + "\"");
        }
    }

    /** Parses and checks one scythe file. Throws {@link IllegalArgumentException} naming the problem. */
    public static ScytheSpec parse(JsonObject json) {
        return new ScytheSpec(json);
    }

    /** The move on this input, or null. */
    public String moveOn(String input) {
        return inputs.get(input);
    }

    public boolean has(String move) {
        return switch (move) {
            case "sweet_spot" -> sweetSpot != null;
            case "backswing" -> backswing != null;
            case "whirl" -> whirl != null;
            case "reap_pull" -> reapPull != null;
            case "crescent_wave" -> crescentWave != null;
            case "harvest_souls" -> harvestSouls != null;
            default -> false;
        };
    }

    public List<String> moves() {
        return MOVES.stream().filter(this::has).sorted().toList();
    }

    // ------------------------------------------------------------------------------------------------ the move blocks
    private SweetSpot sweetSpot(JsonObject o) {
        if (o == null) {
            return null;
        }
        R r = block("sweet_spot", o, "min", "max", "multiplier", "inside", "sound");
        double max = r.number("max", reach, 0.5, 8.0);
        double min = r.number("min", max * 0.7, 0.0, 8.0);
        if (min >= max) {
            throw new IllegalArgumentException("\"sweet_spot\": \"min\" " + min + " must be below \"max\" " + max);
        }
        return new SweetSpot(min, max, r.number("multiplier", 1.5, 1.0, 5.0), r.number("inside", 0.75, 0.0, 1.0),
            r.string("sound", "minecraft:entity.player.attack.crit"));
    }

    private static Backswing backswing(JsonObject o) {
        if (o == null) {
            return null;
        }
        R r = block("backswing", o, "damage", "window", "arc");
        return new Backswing(r.number("damage", 0.7, 0.0, 5.0), r.integer("window", 12, 2, 60), r.number("arc", 150.0, 10.0, 360.0));
    }

    private static Whirl whirl(JsonObject o) {
        if (o == null) {
            return null;
        }
        R r = block("whirl", o, "input", "radius", "build_ticks", "min_damage", "max_damage", "hit_every", "max_ticks", "slow", "cooldown");
        double lo = r.number("min_damage", 0.3, 0.0, 5.0), hi = r.number("max_damage", 1.0, 0.0, 5.0);
        if (lo > hi) {
            throw new IllegalArgumentException("\"whirl\": \"min_damage\" " + lo + " is above \"max_damage\" " + hi);
        }
        return new Whirl(r.input("use"), r.number("radius", 3.0, 1.0, 8.0), r.integer("build_ticks", 40, 1, 400), lo, hi,
            r.integer("hit_every", 5, 1, 40), r.integer("max_ticks", 100, 10, 1200), r.number("slow", 0.5, 0.0, 1.0),
            r.integer("cooldown", 40, 0, 1200));
    }

    private static ReapPull reapPull(JsonObject o) {
        if (o == null) {
            return null;
        }
        R r = block("reap_pull", o, "input", "range", "arc", "pull", "damage", "cooldown");
        return new ReapPull(r.input("use"), r.number("range", 5.0, 1.0, 16.0), r.number("arc", 100.0, 10.0, 360.0),
            r.number("pull", 0.9, 0.0, 4.0), r.number("damage", 0.5, 0.0, 5.0), r.integer("cooldown", 30, 0, 1200));
    }

    private static CrescentWave crescentWave(JsonObject o) {
        if (o == null) {
            return null;
        }
        R r = block("crescent_wave", o, "input", "range", "speed", "width", "damage", "pierce", "cooldown", "particle");
        return new CrescentWave(r.input("sneak_use"), r.number("range", 10.0, 1.0, 48.0), r.number("speed", 1.0, 0.2, 4.0),
            r.number("width", 3.0, 0.5, 12.0), r.number("damage", 0.8, 0.0, 5.0), r.integer("pierce", 3, 1, 64),
            r.integer("cooldown", 60, 0, 1200), r.string("particle", "minecraft:sweep_attack"));
    }

    private static HarvestSouls harvestSouls(JsonObject o) {
        if (o == null) {
            return null;
        }
        R r = block("harvest_souls", o, "max_souls", "decay_ticks", "empower_per_soul", "heal_per_soul");
        return new HarvestSouls(r.integer("max_souls", 5, 1, 64), r.integer("decay_ticks", 200, 0, 12000),
            r.number("empower_per_soul", 0.1, 0.0, 2.0), r.number("heal_per_soul", 1.0, 0.0, 40.0));
    }

    private static R block(String name, JsonObject o, String... keys) {
        only("\"" + name + "\"", o, Set.of(keys));
        return new R(name, o);
    }

    private static void only(String what, JsonObject o, Set<String> allowed) {
        for (String k : o.keySet()) {
            if (!allowed.contains(k)) {
                throw new IllegalArgumentException(what + ": unknown field \"" + k + "\"; allowed: " + new TreeSet<>(allowed));
            }
        }
    }

    /** Typed, range-checked reads; errors name the move block. */
    private record R(String where, JsonObject json) {
        private String name(String key) {
            return "\"" + (where.isEmpty() ? key : where + "." + key) + "\"";
        }

        String string(String key, String fallback) {
            JsonElement e = json.get(key);
            if (e == null || e.isJsonNull()) {
                return fallback;
            }
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(name(key) + " must be a string");
            }
            return e.getAsString();
        }

        String input(String fallback) {
            String s = string("input", fallback);
            if (!INPUTS.contains(s)) {
                throw new IllegalArgumentException(name("input") + " is \"" + s + "\", must be use or sneak_use");
            }
            return s;
        }

        JsonObject object(String key) {
            JsonElement e = json.get(key);
            if (e == null || e.isJsonNull()) {
                return null;
            }
            if (!e.isJsonObject()) {
                throw new IllegalArgumentException(name(key) + " must be an object (use {} for the defaults)");
            }
            return e.getAsJsonObject();
        }

        double number(String key, double fallback, double min, double max) {
            JsonElement e = json.get(key);
            if (e == null || e.isJsonNull()) {
                return fallback;
            }
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException(name(key) + " must be a number");
            }
            double v = e.getAsDouble();
            if (!Double.isFinite(v) || v < min || v > max) {
                throw new IllegalArgumentException(name(key) + " is " + v + ", must be " + min + " to " + max);
            }
            return v;
        }

        int integer(String key, int fallback, int min, int max) {
            double v = number(key, fallback, min, max);
            if (v != Math.rint(v)) {
                throw new IllegalArgumentException(name(key) + " must be a whole number, not " + v);
            }
            return (int) v;
        }
    }
}
