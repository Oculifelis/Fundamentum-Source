package com.fundamentum.soulfire;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Common settings for soul burning (config/fundamentum-soulfire.toml). */
public final class SoulFireConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue BURN_DAMAGE = BUILDER
        .comment("Damage per second while burning with soul fire (ordinary burning does 1). 2 matches the vanilla soul fire block's contact damage.")
        .defineInRange("soulFire.soulFireBurnDamage", 2.0, 0.0, 1024.0);

    public static final ModConfigSpec.IntValue HIT_IGNITE_TICKS = BUILDER
        .comment("How long a hit of the soul fire element sets its target soul-burning, in ticks (0: soul fire hits don't ignite, they only turn existing fire blue).")
        .defineInRange("soulFire.soulFireHitIgniteTicks", 80, 0, 72000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    static float burnDamage() {
        return SPEC.isLoaded() ? BURN_DAMAGE.get().floatValue() : 2.0f;
    }

    static int hitIgniteTicks() {
        return SPEC.isLoaded() ? HIT_IGNITE_TICKS.get() : 80;
    }

    private SoulFireConfig() {}
}
