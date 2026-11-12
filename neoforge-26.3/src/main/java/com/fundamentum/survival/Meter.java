package com.fundamentum.survival;

import com.fundamentum.port.FId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One survival meter, from {@code data/<ns>/fundamentum/survival/<path>.json}. The value lives in a stat (see
 * {@code com.fundamentum.stat}); declare that stat too when it should have a name, format or limits of its own.
 * <pre>
 * {
 *   "stat": "mymod:thirst",            the stat holding the value (default: the meter's own id)
 *   "min": 0, "max": 20,               the meter's range (default 0 to 20)
 *   "start": 20,                       the value a player starts with (default max)
 *   "respawn": 20,                     the value after death (default start)
 *   "reset_on_death": true,
 *   "creative": false,                 true to run for creative and spectator players too
 *   "rules": [                         applied once a second, in order
 *     {"amount": -0.05},
 *     {"amount": -0.1, "when": "sprinting"},
 *     {"amount": -0.2, "when": [{"type": "climate", "value": "temperature", "min": 1.5}]},
 *     {"type": "climate", "value": "temperature", "scale": 50, "offset": 10, "rate": 0.1}
 *   ],
 *   "consume": [{"items": ["minecraft:potion", "#c:drinks"], "amount": 6}],
 *   "drink_blocks": [{"blocks": ["minecraft:water"], "amount": 2}],
 *   "thresholds": [
 *     {"below": 6, "effect": "minecraft:slowness", "amplifier": 0},
 *     {"at_most": 0, "damage": 1, "interval": 4}
 *   ],
 *   "hud": {"style": "bar", "color": "#3F76E4", "side": "right", "label": "meter.mymod.thirst", "order": 0}
 * }
 * </pre>
 * A rule's {@code when} is an activity name (sprinting, swimming, sneaking, moving, still, in_water, underwater,
 * in_rain, on_fire, in_lava, sleeping), any condition object, or a list of them that must all hold. A climate rule
 * moves the value a share ({@code rate}) of the way toward {@code reading * scale + offset} each second.
 */
public record Meter(FId id, FId stat, double min, double max, double start, double respawn, boolean resetOnDeath, boolean creative,
                    List<Rule> rules, List<Consume> consume, List<DrinkBlock> drinkBlocks, List<Threshold> thresholds, Hud hud, String json) {

    /** One drain or fill rule. {@code climate} is null for a plain amount rule. */
    public record Rule(double amount, JsonElement when, String climate, double scale, double offset, double rate) { }

    /** Items that change the meter when eaten or drunk. Entries are item ids and {@code #tag} ids. */
    public record Consume(List<String> items, double amount) { }

    /** Blocks (or fluids' blocks, like minecraft:water) a player drinks from with an empty hand. */
    public record DrinkBlock(List<String> blocks, double amount) { }

    /**
     * An effect or damage while the value is past a limit. {@code compare} is below, above, at_most or at_least.
     * {@code effect} may be null (damage only); {@code damage} 0 means none.
     */
    public record Threshold(String compare, double limit, FId effect, int amplifier, int duration, float damage, FId damageType, int interval) {
        public boolean active(double value) {
            return switch (this.compare) {
                case "below" -> value < this.limit;
                case "above" -> value > this.limit;
                case "at_most" -> value <= this.limit;
                default -> value >= this.limit;
            };
        }
    }

    /** How the meter shows on the HUD: style bar, text or none; side left or right, above the hotbar. */
    public record Hud(String style, int color, String side, String label, int order) { }

    public static Meter parse(FId id, JsonObject o) {
        FId stat = o.has("stat") ? FId.of(o.get("stat").getAsString()) : id;
        double min = num(o, "min", 0);
        double max = num(o, "max", 20);
        if (max < min) throw new IllegalArgumentException("max is below min");
        double start = clamp(num(o, "start", max), min, max);
        double respawn = clamp(num(o, "respawn", start), min, max);
        boolean reset = !o.has("reset_on_death") || o.get("reset_on_death").getAsBoolean();
        boolean creative = o.has("creative") && o.get("creative").getAsBoolean();

        List<Rule> rules = new ArrayList<>();
        for (JsonElement e : list(o.get("rules"))) {
            JsonObject r = e.getAsJsonObject();
            String type = r.has("type") ? r.get("type").getAsString() : "amount";
            String climate = null;
            if ("climate".equals(type)) {
                climate = r.has("value") ? r.get("value").getAsString() : "temperature";
                if (!"temperature".equals(climate) && !"humidity".equals(climate)) {
                    throw new IllegalArgumentException("climate rule value must be temperature or humidity, not " + climate);
                }
            }
            rules.add(new Rule(num(r, "amount", 0), r.get("when"), climate, num(r, "scale", 1), num(r, "offset", 0), num(r, "rate", 0.1)));
        }
        List<Consume> consume = new ArrayList<>();
        for (JsonElement e : list(o.get("consume"))) {
            JsonObject c = e.getAsJsonObject();
            consume.add(new Consume(strings(c.get("items")), num(c, "amount", 0)));
        }
        List<DrinkBlock> drink = new ArrayList<>();
        for (JsonElement e : list(o.get("drink_blocks"))) {
            JsonObject c = e.getAsJsonObject();
            drink.add(new DrinkBlock(strings(c.get("blocks")), num(c, "amount", 0)));
        }
        List<Threshold> thresholds = new ArrayList<>();
        for (JsonElement e : list(o.get("thresholds"))) {
            JsonObject t = e.getAsJsonObject();
            String compare = null;
            for (String k : new String[] {"below", "above", "at_most", "at_least"}) {
                if (t.has(k)) compare = k;
            }
            if (compare == null) throw new IllegalArgumentException("a threshold needs below, above, at_most or at_least");
            FId effect = t.has("effect") ? FId.of(t.get("effect").getAsString()) : null;
            // left out: the meter's own damage type, see Survival#meterDamage
            FId dtype = t.has("damage_type") ? FId.of(t.get("damage_type").getAsString()) : null;
            thresholds.add(new Threshold(compare, t.get(compare).getAsDouble(), effect, (int) num(t, "amplifier", 0), (int) num(t, "duration", 60),
                (float) num(t, "damage", 0), dtype, Math.max(1, (int) num(t, "interval", 2))));
        }
        Hud hud = new Hud("none", 0xFFFFFF, "right", "meter." + id.namespace() + "." + id.path().replace('/', '.'), 0);
        if (o.has("hud") && o.get("hud").isJsonObject()) {
            JsonObject h = o.getAsJsonObject("hud");
            String style = h.has("style") ? h.get("style").getAsString().toLowerCase(Locale.ROOT) : "bar";
            if (!style.equals("bar") && !style.equals("text") && !style.equals("none")) throw new IllegalArgumentException("hud style must be bar, text or none");
            int color = 0xFFFFFF;
            if (h.has("color")) {
                String s = h.get("color").getAsString();
                color = s.startsWith("#") ? Integer.parseInt(s.substring(1), 16) : h.get("color").getAsInt();
            }
            String side = h.has("side") ? h.get("side").getAsString().toLowerCase(Locale.ROOT) : "right";
            hud = new Hud(style, color & 0xFFFFFF, "left".equals(side) ? "left" : "right", h.has("label") ? h.get("label").getAsString() : hud.label(),
                (int) num(h, "order", 0));
        }
        return new Meter(id, stat, min, max, start, respawn, reset, creative, List.copyOf(rules), List.copyOf(consume), List.copyOf(drink),
            List.copyOf(thresholds), hud, o.toString());
    }

    public double clamp(double v) {
        return clamp(v, this.min, this.max);
    }

    /** The value as a share of the range, 0 to 1. */
    public double fill(double v) {
        return this.max > this.min ? clamp((v - this.min) / (this.max - this.min), 0, 1) : 0;
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double num(JsonObject o, String k, double def) {
        return o.has(k) ? o.get(k).getAsDouble() : def;
    }

    private static List<JsonElement> list(JsonElement e) {
        List<JsonElement> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonArray()) e.getAsJsonArray().forEach(out::add);
        else out.add(e);
        return out;
    }

    static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        for (JsonElement x : list(e)) out.add(x.getAsString());
        return List.copyOf(out);
    }
}
