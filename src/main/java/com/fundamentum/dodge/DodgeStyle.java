package com.fundamentum.dodge;

import com.fundamentum.port.FId;
import com.google.gson.JsonObject;

/**
 * One way of dodging, read from {@code data/<namespace>/fundamentum/dodge_style/<name>.json}. Every field is optional
 * ({@link #DEFAULT}); the player's dodge attributes scale them (see {@link Dodges}).
 * <pre>{"distance": 4, "ticks": 4, "iframe_start": 0, "iframe_end": 4, "chain_delay": 6, "recharge_ticks": 40,
 *  "lock_ticks": 0, "pose": "none", "ground": true, "air": true, "air_dodges": 1, "directions": "eight",
 *  "allow_up": false, "gravity": true, "cost": 0.2, "neutral": "back", "sound": "minecraft:entity.player.attack.sweep",
 *  "volume": 0.6, "pitch": 1.4, "particle": "minecraft:cloud", "particle_count": 6, "particle_spread": 0.3,
 *  "particle_speed": 0.02, "exit_speed": 0.2, "impact_reach": 0.4, "impact_knockback": 0.5}</pre>
 * {@code directions} is "eight" (any way the movement keys point) or "four" (the nearest of forward, back, left and
 * right). {@code exit_speed} is the share of dodge speed kept when the move ends. {@code impact_reach} widens the box
 * that finds mobs passed through, and {@code impact_knockback} scales the knockback attribute against them. {@code allow_up} turns a dodge with jump held into a straight rise. {@code pose} "low" shrinks the player to
 * a crawl while the dodge moves. {@code neutral} is where a dodge with no movement key goes: "back", "forward" or
 * "none" (no dodge).
 */
public record DodgeStyle(FId id, double distance, int ticks, int iframeStart, int iframeEnd, int chainDelay, int rechargeTicks,
                         int lockTicks, boolean low, boolean ground, boolean air, int airDodges, boolean fourWay, boolean allowUp,
                         boolean gravity, float cost, String neutral, FId sound, float volume, float pitch, FId particle,
                         int particleCount, double particleSpread, double particleSpeed, double exitSpeed,
                         double impactReach, double impactKnockback) {
    public static final DodgeStyle DEFAULT = new DodgeStyle(FId.of("fundamentum", "dash"), 4, 4, 0, 4, 6, 40, 0, false, true,
        true, 1, false, false, true, 0.2F, "back", null, 0.6F, 1.4F, null, 6, 0.3, 0.02, 0.2, 0.4, 0.5);

    static DodgeStyle fromJson(FId id, JsonObject j) {
        DodgeStyle d = DEFAULT;
        int ticks = Math.max(1, i(j, "ticks", d.ticks));
        return new DodgeStyle(id,
            Math.max(0, n(j, "distance", d.distance)),
            ticks,
            Math.max(0, i(j, "iframe_start", d.iframeStart)),
            Math.max(0, i(j, "iframe_end", ticks)),
            Math.max(0, i(j, "chain_delay", d.chainDelay)),
            Math.max(1, i(j, "recharge_ticks", d.rechargeTicks)),
            Math.max(0, i(j, "lock_ticks", d.lockTicks)),
            "low".equals(s(j, "pose", "none")),
            b(j, "ground", d.ground),
            b(j, "air", d.air),
            Math.max(0, i(j, "air_dodges", d.airDodges)),
            "four".equals(s(j, "directions", "eight")),
            b(j, "allow_up", d.allowUp),
            b(j, "gravity", d.gravity),
            (float) Math.max(0, n(j, "cost", d.cost)),
            s(j, "neutral", d.neutral),
            j.has("sound") ? FId.tryOf(j.get("sound").getAsString()) : null,
            (float) n(j, "volume", d.volume),
            (float) n(j, "pitch", d.pitch),
            j.has("particle") ? FId.tryOf(j.get("particle").getAsString()) : null,
            Math.max(0, i(j, "particle_count", d.particleCount)),
            Math.max(0, n(j, "particle_spread", d.particleSpread)),
            Math.max(0, n(j, "particle_speed", d.particleSpeed)),
            Math.max(0, n(j, "exit_speed", d.exitSpeed)),
            Math.max(0, n(j, "impact_reach", d.impactReach)),
            Math.max(0, n(j, "impact_knockback", d.impactKnockback)));
    }

    private static double n(JsonObject j, String k, double def) {
        return j.has(k) ? j.get(k).getAsDouble() : def;
    }

    private static int i(JsonObject j, String k, int def) {
        return j.has(k) ? j.get(k).getAsInt() : def;
    }

    private static boolean b(JsonObject j, String k, boolean def) {
        return j.has(k) ? j.get(k).getAsBoolean() : def;
    }

    private static String s(JsonObject j, String k, String def) {
        return j.has(k) ? j.get(k).getAsString() : def;
    }
}
