package com.fundamentum.whip;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Fundamentum's client settings for whips (config/fundamentum-client.toml). {@code flatHandles} is read by the
 * {@code fundamentum:whip_flat_handles} item model condition, so a whip's item definition can offer a flat handle
 * beside its 3D one; it is read every frame, so a change shows at once. One switch for every mod's whips: a mod that
 * offers both handles selects on the condition (or reads {@code FLAT_HANDLES} in code) instead of keeping its own option.
 * Mods, Fundamentum, Config edits it in game.
 */
public final class WhipsConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue FLAT_HANDLES = BUILDER
        .comment("Draw whip handles flat (2D sprites) in hand instead of 3D models, for every mod's whips that offer both.")
        .translation("fundamentum.configuration.flatHandles")
        .define("whips.flatHandles", false);

    /**
     * Tooltips (1.25.3, kept here because this is the one fundamentum-client.toml spec): fold Fundamentum's tooltip
     * lines behind "Hold [Shift] for details" ({@code com.fundamentum.client.Tooltips}).
     */
    public static final ModConfigSpec.BooleanValue SHIFT_FOR_DETAILS = BUILDER
        .comment("Fold item descriptions and stats (Fundamentum's tooltip lines, for every mod) behind \"Hold [Shift] for details\". false shows them always.")
        .translation("fundamentum.configuration.shiftForDetails")
        .define("tooltips.shiftForDetails", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private WhipsConfig() {
    }
}
