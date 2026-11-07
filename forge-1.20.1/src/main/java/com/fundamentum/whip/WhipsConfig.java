package com.fundamentum.whip;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Fundamentum's client settings for whips (config/fundamentum-client.toml). {@code flatHandles} is read by the
 * {@code fundamentum:whip_flat_handles} item model condition, so a whip's item definition can offer a flat handle
 * beside its 3D one; it is read every frame, so a change shows at once. One switch for every mod's whips: a mod that
 * offers both handles selects on the condition (or reads {@code FLAT_HANDLES} in code) instead of keeping its own option.
 * Mods, Fundamentum, Config edits it in game.
 */
public final class WhipsConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue FLAT_HANDLES = BUILDER
        .comment("Draw whip handles flat (2D sprites) in hand instead of 3D models, for every mod's whips that offer both.")
        .translation("fundamentum.configuration.flatHandles")
        .define("whips.flatHandles", false);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private WhipsConfig() {
    }
}
