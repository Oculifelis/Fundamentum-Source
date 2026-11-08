package com.fundamentum.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client settings for the item HUD ({@link com.fundamentum.api.ItemHud}), config/fundamentum-hud-client.toml; Mods,
 * Fundamentum, Config edits it in game. Read every frame, so a change shows at once.
 */
public final class ItemHudConfig {
    private static final String S = "itemHud.";
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
        .comment("Show the item HUD: a few lines of text beside the hotbar for held items that have one (like the Engineer's Crossbow).")
        .translation("fundamentum.configuration.itemHud.enabled")
        .define(S + "enabled", true);
    public static final ModConfigSpec.EnumValue<HudBox.Anchor> ANCHOR = BUILDER
        .comment("Which part of the screen the HUD is placed against (HOTBAR_RIGHT: beside the hotbar).")
        .translation("fundamentum.configuration.itemHud.anchor")
        .defineEnum(S + "anchor", HudBox.Anchor.HOTBAR_RIGHT);
    public static final ModConfigSpec.IntValue X = BUILDER
        .comment("Moves the HUD right (negative: left) from its anchor, in GUI pixels.")
        .translation("fundamentum.configuration.itemHud.x")
        .defineInRange(S + "x", 0, -4000, 4000);
    public static final ModConfigSpec.IntValue Y = BUILDER
        .comment("Moves the HUD down (negative: up) from its anchor, in GUI pixels.")
        .translation("fundamentum.configuration.itemHud.y")
        .defineInRange(S + "y", 0, -4000, 4000);
    public static final ModConfigSpec.DoubleValue SCALE = BUILDER
        .comment("Size of the HUD: 1 is the size of the game's own text.")
        .translation("fundamentum.configuration.itemHud.scale")
        .defineInRange(S + "scale", 1.0, 0.25, 4.0);
    public static final ModConfigSpec.ConfigValue<String> TEXT_COLOR = BUILDER
        .comment("Colour of text that doesn't set its own, as #RRGGBB.")
        .translation("fundamentum.configuration.itemHud.textColor")
        .define(S + "textColor", "#FFFFFF", o -> o instanceof String s && s.matches("#?[0-9a-fA-F]{6}"));
    public static final ModConfigSpec.DoubleValue TEXT_OPACITY = BUILDER
        .comment("Opacity of the text: 0.1 faint, 1 solid.")
        .translation("fundamentum.configuration.itemHud.textOpacity")
        .defineInRange(S + "textOpacity", 1.0, 0.1, 1.0);
    public static final ModConfigSpec.BooleanValue SHADOW = BUILDER
        .comment("Draw the text with the game's drop shadow.")
        .translation("fundamentum.configuration.itemHud.shadow")
        .define(S + "shadow", true);
    public static final ModConfigSpec.BooleanValue BACKGROUND = BUILDER
        .comment("Draw a dark backdrop behind the HUD, as chat and subtitles have.")
        .translation("fundamentum.configuration.itemHud.background")
        .define(S + "background", false);
    public static final ModConfigSpec.DoubleValue BACKGROUND_OPACITY = BUILDER
        .comment("How dark the backdrop is: 0 clear, 1 solid black.")
        .translation("fundamentum.configuration.itemHud.backgroundOpacity")
        .defineInRange(S + "backgroundOpacity", 0.35, 0.0, 1.0);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ItemHudConfig() {
    }
}
