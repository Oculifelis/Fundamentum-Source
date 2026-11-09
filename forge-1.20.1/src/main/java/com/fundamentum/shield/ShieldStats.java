package com.fundamentum.shield;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The numbers of one shield. Pure data (no game types), so it runs in unit tests and is the same file on every version.
 * Every field can be set per item in a datapack ({@code data/<ns>/fundamentum/shields/<name>.json}, see {@link Shields});
 * the JSON names are the ones in {@link #withJson}.
 *
 * <p>The first group is vanilla's {@code minecraft:blocks_attacks} component with the same names: on 26.x the shield gets
 * that component (so every mod sees a normal shield), on the older versions {@link Shields} does the same sums itself.
 * The rest is added on top: the slowdown while blocking, the parry, the bash, element resistance and the throw.
 *
 * @param blockDelaySeconds    seconds after raising before it blocks (vanilla shield 0.25)
 * @param disableCooldownScale multiplier on how long an axe hit disables it (0 = never disabled)
 * @param damageReductions     what a block takes off a hit; each matching entry adds, the sum is capped at the hit
 * @param itemDamage           durability a blocked hit costs
 * @param bypassedBy           damage type tag that is never blocked ({@code "#minecraft:bypasses_shield"}), or null for none
 * @param blockSound           sound id of a block, or null for silence
 * @param disabledSound        sound id when an axe disables it, or null
 * @param speedWhileBlocking   walking speed multiplier while raised (vanilla 0.2)
 * @param canSprint            whether the holder can keep sprinting while it is raised
 * @param parry                the parry, or null for none
 * @param bash                 the bash, or null for none
 * @param elementResistance    element id to the share of that element's damage it stops while raised (0.5 = half)
 * @param thrown               the throw: thrown-weapon fields laid over a boomerang's, or null when it cannot be thrown
 */
public record ShieldStats(double blockDelaySeconds, double disableCooldownScale, List<Reduction> damageReductions,
                          ItemDamage itemDamage, String bypassedBy, String blockSound, String disabledSound,
                          double speedWhileBlocking, boolean canSprint, Parry parry, Bash bash,
                          Map<String, Double> elementResistance, JsonObject thrown) {
    /** The tag vanilla shields let through. */
    public static final String BYPASSES_SHIELD = "#minecraft:bypasses_shield";

    /** Exactly the vanilla shield: no parry, no bash, no extras. */
    public static final ShieldStats VANILLA = new ShieldStats(0.25, 1, List.of(Reduction.FULL_FRONT), ItemDamage.VANILLA,
        BYPASSES_SHIELD, "minecraft:item.shield.block", "minecraft:item.shield.break", 0.2, false, null, null, Map.of(), null);
    /** A plain Fundamentum shield: the vanilla shield with the default parry and bash. */
    public static final ShieldStats DEFAULT = new ShieldStats(0.25, 1, List.of(Reduction.FULL_FRONT), ItemDamage.VANILLA,
        BYPASSES_SHIELD, "minecraft:item.shield.block", "minecraft:item.shield.break", 0.2, false, Parry.DEFAULT, Bash.DEFAULT, Map.of(), null);

    /**
     * One entry of {@code damage_reductions}: a hit inside {@code horizontal_blocking_angle} degrees of where the holder
     * looks, of a listed damage type (or any when {@code type} is absent), loses {@code base + factor * damage}, capped at
     * the hit.
     *
     * @param horizontalBlockingAngle degrees either side of the view (90 = the whole front half)
     * @param type                    damage type ids and {@code #tags}, or null for every type
     * @param base                    flat damage stopped
     * @param factor                  share of the hit stopped
     */
    public record Reduction(double horizontalBlockingAngle, List<String> type, double base, double factor) {
        /** The vanilla shield's: everything from the front, in full. */
        public static final Reduction FULL_FRONT = new Reduction(90, null, 0, 1);

        public Reduction {
            horizontalBlockingAngle = clamp(horizontalBlockingAngle, 0.001, 180);
            type = type == null ? null : List.copyOf(type);
        }

        /** Damage this entry stops: 0 outside its angle (radians, as vanilla measures it) or for another type. */
        public double resolve(double dealt, double angleRadians, TypeMatcher matcher) {
            if (angleRadians > Math.toRadians(horizontalBlockingAngle)) {
                return 0;
            }
            if (type != null && !matches(type, matcher)) {
                return 0;
            }
            return clamp(base + factor * dealt, 0, dealt);
        }

        static Reduction fromJson(JsonObject json) {
            return new Reduction(num(json, "horizontal_blocking_angle", 90), strings(json.get("type")),
                num(json, "base", 0), num(json, "factor", 0));
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("horizontal_blocking_angle", horizontalBlockingAngle);
            if (type != null) {
                JsonArray a = new JsonArray();
                type.forEach(a::add);
                o.add("type", a);
            }
            o.addProperty("base", base);
            o.addProperty("factor", factor);
            return o;
        }
    }

    /**
     * {@code item_damage}: a blocked hit of at least {@code threshold} costs {@code floor(base + factor * damage)} durability.
     */
    public record ItemDamage(double threshold, double base, double factor) {
        public static final ItemDamage VANILLA = new ItemDamage(3, 1, 1);

        public ItemDamage {
            threshold = Math.max(0, threshold);
        }

        public int apply(double damage) {
            return damage < threshold ? 0 : (int) Math.floor(base + factor * damage);
        }

        ItemDamage withJson(JsonObject json) {
            return new ItemDamage(num(json, "threshold", threshold), num(json, "base", base), num(json, "factor", factor));
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("threshold", threshold);
            o.addProperty("base", base);
            o.addProperty("factor", factor);
            return o;
        }
    }

    /**
     * {@code parry}: a hit blocked within {@code window} ticks of the shield starting to block is parried. It is stopped
     * in full and costs no durability; a melee attacker is knocked back and staggered (Slowness and Weakness for
     * {@code stagger_ticks}); an arrow or other projectile flies back at whoever shot it when {@code reflect} is on. A raise
     * within {@code rearm} ticks of the last one gets no window, so tapping the button does not parry everything.
     *
     * @param window        ticks after blocking starts that count as well timed
     * @param rearm         ticks a raise must come after the previous one to have a window again
     * @param staggerTicks  how long a parried attacker is slowed and weakened
     * @param knockback     knockback on a parried attacker
     * @param reflect       whether a parried projectile flies back
     * @param reflectSpeed  multiplier on the projectile's speed when it flies back
     */
    public record Parry(int window, int rearm, int staggerTicks, double knockback, boolean reflect, double reflectSpeed) {
        public static final Parry DEFAULT = new Parry(5, 20, 30, 0.6, true, 1.0);

        public Parry {
            window = Math.max(0, window);
            rearm = Math.max(0, rearm);
            staggerTicks = Math.max(0, staggerTicks);
            knockback = Math.max(0, knockback);
            reflectSpeed = clamp(reflectSpeed, 0.05, 10);
        }

        /** Whether a hit {@code ticksBlocking} ticks after the block started (0 = the first blocking tick) is parried. */
        public boolean inWindow(int ticksBlocking) {
            return ticksBlocking >= 0 && ticksBlocking <= window;
        }

        /** Whether a raise at {@code now} gets a window, the previous raise having been at {@code previousRaise} (or never: Long.MIN_VALUE). */
        public boolean armed(long previousRaise, long now) {
            return previousRaise == Long.MIN_VALUE || now - previousRaise >= rearm;
        }

        Parry withJson(JsonObject json) {
            return new Parry((int) Math.round(num(json, "window", window)), (int) Math.round(num(json, "rearm", rearm)),
                (int) Math.round(num(json, "stagger_ticks", staggerTicks)), num(json, "knockback", knockback),
                bool(json, "reflect", reflect), num(json, "reflect_speed", reflectSpeed));
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("window", window);
            o.addProperty("rearm", rearm);
            o.addProperty("stagger_ticks", staggerTicks);
            o.addProperty("knockback", knockback);
            o.addProperty("reflect", reflect);
            o.addProperty("reflect_speed", reflectSpeed);
            return o;
        }
    }

    /**
     * {@code bash}: a shove with the raised shield. It hits every creature within {@code reach} blocks and {@code angle}
     * degrees of the view for {@code damage}, knocks them back and staggers them, and costs {@code durability}. Never on
     * left click (that is the weapon's, and Better Combat's): {@code trigger} {@code "sneak"} bashes when the holder starts
     * sneaking while the shield is raised, {@code "use"} when they use it while sneaking (a throwable shield throws on that
     * instead).
     *
     * @param damage       damage of the bash
     * @param knockback    knockback strength
     * @param reach        blocks from the holder's eyes
     * @param angle        degrees either side of the view
     * @param cooldown     ticks before the next bash
     * @param staggerTicks Slowness and Weakness on the creatures hit
     * @param durability   durability one bash costs
     * @param trigger      {@code "sneak"} or {@code "use"}
     */
    public record Bash(double damage, double knockback, double reach, double angle, int cooldown, int staggerTicks,
                       int durability, String trigger) {
        public static final String SNEAK = "sneak";
        public static final String USE = "use";
        public static final Bash DEFAULT = new Bash(3, 1.0, 2.75, 50, 30, 20, 1, SNEAK);

        public Bash {
            damage = Math.max(0, damage);
            knockback = Math.max(0, knockback);
            reach = clamp(reach, 0.5, 8);
            angle = clamp(angle, 1, 180);
            cooldown = Math.max(1, cooldown);
            staggerTicks = Math.max(0, staggerTicks);
            durability = Math.max(0, durability);
            trigger = USE.equals(trigger) ? USE : SNEAK;
        }

        /** Whether the bash is ready at {@code now}, the last one having been at {@code last} (or never: Long.MIN_VALUE). */
        public boolean ready(long last, long now) {
            return last == Long.MIN_VALUE || now - last >= cooldown;
        }

        /**
         * Whether a creature at offset {@code (dx, dy, dz)} from the holder's eyes is inside the bash, the holder looking
         * along {@code (lx, ly, lz)} (need not be unit length). The offset is to the nearest point of the creature.
         */
        public boolean inCone(double dx, double dy, double dz, double lx, double ly, double lz) {
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > reach) {
                return false;
            }
            if (dist < 1e-6) {
                return true;
            }
            double look = Math.sqrt(lx * lx + ly * ly + lz * lz);
            if (look < 1e-6) {
                return true;
            }
            double cos = (dx * lx + dy * ly + dz * lz) / (dist * look);
            return cos >= Math.cos(Math.toRadians(angle)) - 1e-9;
        }

        Bash withJson(JsonObject json) {
            JsonElement t = json.get("trigger");
            return new Bash(num(json, "damage", damage), num(json, "knockback", knockback), num(json, "reach", reach),
                num(json, "angle", angle), (int) Math.round(num(json, "cooldown", cooldown)),
                (int) Math.round(num(json, "stagger_ticks", staggerTicks)), (int) Math.round(num(json, "durability", durability)),
                t == null || t.isJsonNull() ? trigger : t.getAsString());
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("damage", damage);
            o.addProperty("knockback", knockback);
            o.addProperty("reach", reach);
            o.addProperty("angle", angle);
            o.addProperty("cooldown", cooldown);
            o.addProperty("stagger_ticks", staggerTicks);
            o.addProperty("durability", durability);
            o.addProperty("trigger", trigger);
            return o;
        }
    }

    /** Whether a hit of one damage type matches a type id or {@code #tag} in data. */
    @FunctionalInterface
    public interface TypeMatcher {
        boolean matches(String idOrTag);
    }

    public ShieldStats {
        blockDelaySeconds = clamp(blockDelaySeconds, 0, 10);
        disableCooldownScale = Math.max(0, disableCooldownScale);
        damageReductions = List.copyOf(damageReductions);
        speedWhileBlocking = clamp(speedWhileBlocking, 0, 1);
        Map<String, Double> res = new LinkedHashMap<>();
        elementResistance.forEach((k, v) -> res.put(k, clamp(v, -10, 1)));
        elementResistance = Map.copyOf(res);
        thrown = thrown == null ? null : thrown.deepCopy();
    }

    /** Ticks after raising before it blocks, rounded as vanilla rounds them. */
    public int blockDelayTicks() {
        return Math.round((float) blockDelaySeconds * 20.0F);
    }

    /** Ticks an axe disables it for, from the axe's seconds; 0 when it is never disabled. */
    public int disableTicks(double baseSeconds) {
        double seconds = baseSeconds * disableCooldownScale;
        return seconds > 0 ? (int) Math.round(seconds * 20) : 0;
    }

    /**
     * Damage a block stops, the way vanilla works it out: the sum of every matching {@link Reduction}, capped at the hit.
     * {@code angleRadians} is between the holder's view and the attacker (pi when the hit has no position).
     */
    public double blockedDamage(double dealt, double angleRadians, TypeMatcher matcher) {
        double sum = 0;
        for (Reduction r : damageReductions) {
            sum += r.resolve(dealt, angleRadians, matcher);
        }
        return clamp(sum, 0, dealt);
    }

    /** Whether a hit of this type goes through the shield untouched. */
    public boolean bypassed(TypeMatcher matcher) {
        return bypassedBy != null && matcher.matches(bypassedBy);
    }

    /** The widest angle (degrees) any reduction covers: element resistance works inside it too. */
    public double maxAngle() {
        double max = 0;
        for (Reduction r : damageReductions) {
            max = Math.max(max, r.horizontalBlockingAngle());
        }
        return max;
    }

    /** What the raised shield multiplies a hit of these elements (ids) by. */
    public double elementMultiplier(Collection<String> elements) {
        double m = 1;
        for (String e : elements) {
            Double r = elementResistance.get(e);
            if (r != null) {
                m *= 1 - r;
            }
        }
        return Math.max(0, m);
    }

    /** Whether this shield can be thrown. */
    public boolean throwable() {
        return thrown != null;
    }

    /**
     * These stats with the fields present in {@code json} laid over. Vanilla's names: {@code block_delay_seconds},
     * {@code disable_cooldown_scale}, {@code damage_reductions} (a list that replaces the old one), {@code item_damage},
     * {@code bypassed_by} (a {@code #tag}, or false for none), {@code block_sound}, {@code disabled_sound} (false for
     * silence). Added: {@code speed_while_blocking}, {@code can_sprint}, {@code parry} and {@code bash} (objects laid over
     * the defaults or this shield's, true for the defaults, false for none), {@code element_resistance} (an object of
     * element id to share; it replaces the old one) and {@code thrown} (an object of thrown-weapon fields over a
     * boomerang's, true for a plain boomerang throw, false for none). Each number can also be a step object
     * ({@code {"multiply": 1.5}}, {@code {"add": 2}}, with {@code set}, {@code min}, {@code max}), applied to the value below.
     */
    public ShieldStats withJson(JsonObject json) {
        List<Reduction> reductions = damageReductions;
        JsonElement r = json.get("damage_reductions");
        if (r != null && r.isJsonArray()) {
            reductions = new ArrayList<>();
            for (JsonElement e : r.getAsJsonArray()) {
                reductions.add(Reduction.fromJson(e.getAsJsonObject()));
            }
        }
        ItemDamage item = itemDamage;
        JsonElement d = json.get("item_damage");
        if (d != null && d.isJsonObject()) {
            item = item.withJson(d.getAsJsonObject());
        }
        Parry p = parry;
        JsonElement pj = json.get("parry");
        if (pj != null && !pj.isJsonNull()) {
            p = pj.isJsonObject() ? (p == null ? Parry.DEFAULT : p).withJson(pj.getAsJsonObject())
                : pj.getAsBoolean() ? (p == null ? Parry.DEFAULT : p) : null;
        }
        Bash b = bash;
        JsonElement bj = json.get("bash");
        if (bj != null && !bj.isJsonNull()) {
            b = bj.isJsonObject() ? (b == null ? Bash.DEFAULT : b).withJson(bj.getAsJsonObject())
                : bj.getAsBoolean() ? (b == null ? Bash.DEFAULT : b) : null;
        }
        Map<String, Double> res = elementResistance;
        JsonElement ej = json.get("element_resistance");
        if (ej != null && ej.isJsonObject()) {
            res = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : ej.getAsJsonObject().entrySet()) {
                res.put(e.getKey(), e.getValue().getAsDouble());
            }
        }
        JsonObject t = thrown;
        JsonElement tj = json.get("thrown");
        if (tj != null && !tj.isJsonNull()) {
            if (tj.isJsonObject()) {
                t = t == null ? new JsonObject() : t.deepCopy();
                for (Map.Entry<String, JsonElement> e : tj.getAsJsonObject().entrySet()) {
                    t.add(e.getKey(), e.getValue());
                }
            } else {
                t = tj.getAsBoolean() ? (t == null ? new JsonObject() : t) : null;
            }
        }
        return new ShieldStats(num(json, "block_delay_seconds", blockDelaySeconds),
            num(json, "disable_cooldown_scale", disableCooldownScale), reductions, item,
            optString(json, "bypassed_by", bypassedBy), optString(json, "block_sound", blockSound),
            optString(json, "disabled_sound", disabledSound), num(json, "speed_while_blocking", speedWhileBlocking),
            bool(json, "can_sprint", canSprint), p, b, res, t);
    }

    /** The stats as JSON, the same names {@link #withJson} reads. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("block_delay_seconds", blockDelaySeconds);
        o.addProperty("disable_cooldown_scale", disableCooldownScale);
        JsonArray reductions = new JsonArray();
        damageReductions.forEach(r -> reductions.add(r.toJson()));
        o.add("damage_reductions", reductions);
        o.add("item_damage", itemDamage.toJson());
        putOptional(o, "bypassed_by", bypassedBy);
        putOptional(o, "block_sound", blockSound);
        putOptional(o, "disabled_sound", disabledSound);
        o.addProperty("speed_while_blocking", speedWhileBlocking);
        o.addProperty("can_sprint", canSprint);
        if (parry != null) o.add("parry", parry.toJson()); else o.addProperty("parry", false);
        if (bash != null) o.add("bash", bash.toJson()); else o.addProperty("bash", false);
        JsonObject res = new JsonObject();
        elementResistance.forEach(res::addProperty);
        o.add("element_resistance", res);
        if (thrown != null) o.add("thrown", thrown.deepCopy()); else o.addProperty("thrown", false);
        return o;
    }

    private static boolean matches(List<String> types, TypeMatcher matcher) {
        for (String t : types) {
            if (matcher.matches(t)) {
                return true;
            }
        }
        return false;
    }

    private static void putOptional(JsonObject o, String name, String value) {
        if (value != null) o.addProperty(name, value); else o.addProperty(name, false);
    }

    static List<String> strings(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(x -> out.add(x.getAsString()));
        } else {
            out.add(e.getAsString());
        }
        return out;
    }

    static String optString(JsonObject json, String name, String below) {
        JsonElement e = json.get(name);
        if (e == null || e.isJsonNull()) {
            return below;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean() ? below : null;
        }
        return e.getAsString();
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
