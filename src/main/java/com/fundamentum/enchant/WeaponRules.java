package com.fundamentum.enchant;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * The rules in a {@code fundamentum:weapon_effects} enchantment component, read with Gson only so every game version
 * shares this class. A component is one rule or a list of them:
 * <pre>
 * {"hook": "on_flail_hit", "chance": 0.5, "effects": [{"type": "modify", "field": "damage", "op": "add", "value": 2}]}
 * </pre>
 * Every number may be a plain number or {@code {"base": 1, "per_level_above_first": 0.5}}.
 */
public final class WeaponRules {
    private WeaponRules() {}

    /** A number that grows with the enchantment level. */
    public record Level(double base, double perLevel) {
        public static final Level ZERO = new Level(0, 0);
        public static final Level ONE = new Level(1, 0);

        public double at(int level) {
            return base + perLevel * Math.max(0, level - 1);
        }

        static Level read(JsonObject o, String key, Level fallback) {
            JsonElement e = o.get(key);
            if (e == null || e.isJsonNull()) {
                return fallback;
            }
            if (e.isJsonPrimitive()) {
                return new Level(e.getAsDouble(), 0);
            }
            JsonObject l = e.getAsJsonObject();
            return new Level(l.has("base") ? l.get("base").getAsDouble() : 0,
                l.has("per_level_above_first") ? l.get("per_level_above_first").getAsDouble() : 0);
        }
    }

    /**
     * One effect. {@code type} is modify, heal, mob_effect, ignite or particles. For modify, {@code field} is damage,
     * knockback, radius, reflect or stagger and {@code op} is add, multiply, per_charge or per_count. {@code to} picks
     * user or target. {@code id}, {@code duration} and {@code amplifier} serve mob_effect and particles.
     */
    public record Effect(String type, String field, String op, Level value, String to, String id, Level duration, Level amplifier) {
        static Effect read(JsonObject o) {
            return new Effect(str(o, "type", "modify"), str(o, "field", "damage"), str(o, "op", "add"),
                Level.read(o, "value", Level.ZERO), str(o, "to", "target"), str(o, "id", ""),
                Level.read(o, "duration", new Level(60, 0)), Level.read(o, "amplifier", Level.ZERO));
        }
    }

    /** Effects that run at one hook, some of the time. */
    public record Rule(String hook, Level chance, List<Effect> effects) {
        static Rule read(JsonObject o) {
            List<Effect> effects = new ArrayList<>();
            JsonElement e = o.get("effects");
            if (e != null && e.isJsonArray()) {
                for (JsonElement x : e.getAsJsonArray()) {
                    effects.add(Effect.read(x.getAsJsonObject()));
                }
            } else if (e != null && e.isJsonObject()) {
                effects.add(Effect.read(e.getAsJsonObject()));
            }
            return new Rule(str(o, "hook", ""), Level.read(o, "chance", Level.ONE), List.copyOf(effects));
        }
    }

    /** Every rule in a component. A malformed rule is skipped. */
    public static List<Rule> read(JsonElement json) {
        List<Rule> out = new ArrayList<>();
        if (json == null) {
            return out;
        }
        JsonArray list;
        if (json.isJsonArray()) {
            list = json.getAsJsonArray();
        } else {
            list = new JsonArray();
            list.add(json);
        }
        for (JsonElement e : list) {
            if (e.isJsonObject()) {
                try {
                    out.add(Rule.read(e.getAsJsonObject()));
                } catch (RuntimeException ignored) {
                    // a broken rule never stops the others
                }
            }
        }
        return List.copyOf(out);
    }

    /** Applies a modify effect to a number. */
    public static double modify(double current, String op, double value, double charge, int count) {
        return switch (op) {
            case "multiply" -> current * value;
            case "per_charge" -> current * (1 + value * charge);
            case "per_count" -> current * (1 + value * count);
            default -> current + value;
        };
    }

    private static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsString();
    }
}
