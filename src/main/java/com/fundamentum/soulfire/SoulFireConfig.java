package com.fundamentum.soulfire;

import net.minecraftforge.common.ForgeConfigSpec;

/** Common settings for soul burning (config/fundamentum-soulfire.toml). */
public final class SoulFireConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.DoubleValue BURN_DAMAGE = BUILDER
        .comment("Damage per second while burning with soul fire (ordinary burning does 1). 2 matches the vanilla soul fire block's contact damage.")
        .defineInRange("soulFire.soulFireBurnDamage", 2.0, 0.0, 1024.0);

    public static final ForgeConfigSpec.IntValue HIT_IGNITE_TICKS = BUILDER
        .comment("How long a hit of the soul fire element sets its target soul-burning, in ticks (0: soul fire hits don't ignite, they only turn existing fire blue).")
        .defineInRange("soulFire.soulFireHitIgniteTicks", 80, 0, 72000);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    static float burnDamage() {
        return SPEC.isLoaded() ? BURN_DAMAGE.get().floatValue() : 2.0f;
    }

    static int hitIgniteTicks() {
        return SPEC.isLoaded() ? HIT_IGNITE_TICKS.get() : 80;
    }

    private SoulFireConfig() {}
}
