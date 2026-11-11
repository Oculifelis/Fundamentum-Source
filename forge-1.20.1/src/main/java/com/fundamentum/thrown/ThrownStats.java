package com.fundamentum.thrown;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * The numbers of one throw of a thrown weapon (javelin, knife, shuriken, kunai, molotov, boomerang, chakram). Pure data
 * (no game types), so it runs in unit tests and ports unchanged. Every field can be set per item in a datapack
 * ({@code data/<ns>/fundamentum/thrown/<name>.json}, see {@link ThrownWeapons}); the JSON names are the ones in
 * {@link #withJson}.
 *
 * <p>A throw without {@link #returns} flies like an arrow: out at {@code throw_speed}, pulled down by {@code gravity},
 * through up to {@code pierce} creatures, then it falls; on a block it sticks ({@code sticks_in_target}), drops, or
 * breaks ({@code breaks_on_hit}, which also ends it on the first creature: molotovs, potions). A throw with a
 * {@link Returns} block is a boomerang: it flies straight, turns home after its range and comes back to the slot it
 * left.
 *
 * @param throwSpeed       blocks per tick when thrown
 * @param gravity          blocks per tick lost downward each tick (not on a returning throw's flight)
 * @param pierce           creatures it passes through; 0 stops at the first, -1 is no limit
 * @param damage           damage of one hit, before the multiplier, enchantments and {@code fundamentum:thrower_damage}
 * @param damageMultiplier multiplier on {@code damage} (packs can scale a weapon without knowing its base)
 * @param sticksInTarget   it sticks in the block it hits and can be picked up there (knives, javelins); else it drops
 * @param breaksOnHit      it breaks on the first thing it hits (molotovs, bottles, bombs); nothing is left
 * @param consumable       a throw takes one item off the stack instead of costing durability (knives, shuriken)
 * @param maxTicks         a flight that has not landed or come home by then drops as an item
 * @param returns          the boomerang part, or null for a throw that does not come back
 */
public record ThrownStats(double throwSpeed, double gravity, int pierce, double damage, double damageMultiplier,
                          boolean sticksInTarget, boolean breaksOnHit, boolean consumable, int maxTicks, @Nullable Returns returns) {
    /** A plain thrown knife: 1.5 a tick, light drop, stops at the first creature, sticks in blocks. */
    public static final ThrownStats DEFAULT = new ThrownStats(1.5, 0.03, 0, 4, 1, true, false, false, 200, null);
    /** A plain wooden boomerang: straight flight, unlimited pierce, {@link Returns#DEFAULT}. */
    public static final ThrownStats BOOMERANG = new ThrownStats(1.5, 0, -1, 4, 1, false, false, false, 200, Returns.DEFAULT);

    /** Unlimited pierce. */
    public static final int UNLIMITED = -1;

    /**
     * The fields only a returning throw has ({@code "returns": {...}} in data).
     *
     * @param range        blocks flown out before it turns home
     * @param returnSpeed  top speed home, blocks per tick (it speeds up to this)
     * @param returnAccel  blocks per tick gained each tick on the way home
     * @param hitsOnReturn whether it hits creatures on the way home too (each creature once per leg)
     * @param bounces      ricochets: a hit on the way out flies on to the nearest other enemy, a block hit glances off
     */
    public record Returns(double range, double returnSpeed, double returnAccel, boolean hitsOnReturn, int bounces) {
        public static final Returns DEFAULT = new Returns(16, 1.6, 0.08, true, 0);

        public Returns {
            range = clamp(range, 0.5, 256);
            returnSpeed = clamp(returnSpeed, 0.05, 10);
            returnAccel = clamp(returnAccel, 0.001, 10);
            bounces = Math.max(0, bounces);
        }

        /** These fields with the ones present in {@code json} laid over. */
        public Returns withJson(JsonObject json) {
            return new Returns(num(json, "range", range), num(json, "return_speed", returnSpeed), num(json, "return_accel", returnAccel),
                bool(json, "hits_on_return", hitsOnReturn), (int) Math.round(num(json, "bounces", bounces)));
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("range", range);
            o.addProperty("return_speed", returnSpeed);
            o.addProperty("return_accel", returnAccel);
            o.addProperty("hits_on_return", hitsOnReturn);
            o.addProperty("bounces", bounces);
            return o;
        }
    }

    public ThrownStats {
        throwSpeed = clamp(throwSpeed, 0.05, 10);
        gravity = clamp(gravity, 0, 1);
        pierce = Math.max(UNLIMITED, pierce);
        damage = Math.max(0, damage);
        damageMultiplier = Math.max(0, damageMultiplier);
        maxTicks = Math.max(20, maxTicks);
    }

    /** Whether it comes back (has a {@link Returns} block). */
    public boolean returning() {
        return returns != null;
    }

    /** Hit damage: {@code damage * damage_multiplier}. */
    public double hitDamage() {
        return damage * damageMultiplier;
    }

    /**
     * These stats with the fields present in {@code json} laid over: {@code throw_speed}, {@code gravity}, {@code pierce},
     * {@code damage}, {@code damage_multiplier}, {@code sticks_in_target}, {@code breaks_on_hit}, {@code consumable},
     * {@code max_ticks}, and {@code returns}: an object of {@link Returns} fields ({@code range}, {@code return_speed},
     * {@code return_accel}, {@code hits_on_return}, {@code bounces}) laid over this throw's (or the defaults), {@code true}
     * for the defaults, or {@code false} for a throw that does not come back. Each number can also be a step object
     * ({@code {"multiply": 1.5}}, {@code {"add": 2}}, with {@code set}, {@code min}, {@code max}), applied to the value below.
     */
    public ThrownStats withJson(JsonObject json) {
        Returns back = returns;
        JsonElement r = json.get("returns");
        if (r != null && !r.isJsonNull()) {
            if (r.isJsonObject()) {
                back = (back == null ? Returns.DEFAULT : back).withJson(r.getAsJsonObject());
            } else {
                back = r.getAsBoolean() ? (back == null ? Returns.DEFAULT : back) : null;
            }
        }
        return new ThrownStats(
            num(json, "throw_speed", throwSpeed),
            num(json, "gravity", gravity),
            (int) Math.round(num(json, "pierce", pierce)),
            num(json, "damage", damage),
            num(json, "damage_multiplier", damageMultiplier),
            bool(json, "sticks_in_target", sticksInTarget),
            bool(json, "breaks_on_hit", breaksOnHit),
            bool(json, "consumable", consumable),
            (int) Math.round(num(json, "max_ticks", maxTicks)),
            back);
    }

    /**
     * These stats changed by the thrower's stats (accessories, armour, enchantments, effects): speed multiplies and pierce
     * adds for every throw; range and return speed multiply and bounces add for a returning one. Extra pierce does
     * nothing to unlimited pierce. A longer or faster boomerang throw also gets the time to come home.
     */
    public ThrownStats modified(double speedMul, int pierceAdd, double rangeMul, double returnMul, int bounceAdd) {
        double speed = throwSpeed * speedMul;
        int ticks = maxTicks;
        Returns back = returns;
        if (back != null) {
            back = new Returns(back.range * rangeMul, back.returnSpeed * returnMul, back.returnAccel * Math.max(1, returnMul),
                back.hitsOnReturn, back.bounces + bounceAdd);
            ticks = Math.max(ticks, (int) Math.ceil(3 * back.range / Math.min(speed, back.returnSpeed)) + 40);
        }
        return new ThrownStats(speed, gravity, pierce == UNLIMITED ? UNLIMITED : pierce + pierceAdd, damage, damageMultiplier,
            sticksInTarget, breaksOnHit, consumable, ticks, back);
    }

    /** The stats as JSON, the same names {@link #withJson} reads (for saving a throw, and for docs). */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("throw_speed", throwSpeed);
        o.addProperty("gravity", gravity);
        o.addProperty("pierce", pierce);
        o.addProperty("damage", damage);
        o.addProperty("damage_multiplier", damageMultiplier);
        o.addProperty("sticks_in_target", sticksInTarget);
        o.addProperty("breaks_on_hit", breaksOnHit);
        o.addProperty("consumable", consumable);
        o.addProperty("max_ticks", maxTicks);
        if (returns != null) {
            o.add("returns", returns.toJson());
        } else {
            o.addProperty("returns", false);
        }
        return o;
    }

    static boolean bool(JsonObject json, String name, boolean below) {
        JsonElement e = json.get(name);
        return e == null || e.isJsonNull() ? below : e.getAsBoolean();
    }

    static double num(JsonObject json, String name, double below) {
        JsonElement e = json.get(name);
        if (e == null || e.isJsonNull()) {
            return below;
        }
        if (e.isJsonPrimitive()) {
            return e.getAsDouble();
        }
        JsonObject steps = e.getAsJsonObject();
        double v = steps.has("set") ? steps.get("set").getAsDouble() : below;
        if (steps.has("add")) v += steps.get("add").getAsDouble();
        if (steps.has("multiply")) v *= steps.get("multiply").getAsDouble();
        if (steps.has("min")) v = Math.max(v, steps.get("min").getAsDouble());
        if (steps.has("max")) v = Math.min(v, steps.get("max").getAsDouble());
        return v;
    }

    static double clamp(double v, double min, double max) {
        return Double.isNaN(v) ? min : Math.max(min, Math.min(max, v));
    }
}
