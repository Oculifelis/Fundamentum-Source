package com.fundamentum.client;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client settings for the item HUD ({@link com.fundamentum.api.ItemHud}), config/fundamentum-hud-client.toml; Mods,
 * Fundamentum, Config edits it in game. Anchor, x, y and scale are the default place of the item HUD element; once the
 * player moves it in the HUD editor, config/fundamentum-hud-layout.json wins.
 */
public final class ItemHudConfig {
    private static final String S = "itemHud.";
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLED = BUILDER
        .comment("Show the item HUD: a few lines of text beside the hotbar for held items that have one (like the Engineer's Crossbow).")
        .translation("fundamentum.configuration.itemHud.enabled")
        .define(S + "enabled", true);
    public static final ForgeConfigSpec.EnumValue<HudBox.Anchor> ANCHOR = BUILDER
        .comment("Which part of the screen the HUD is placed against (HOTBAR_RIGHT: beside the hotbar).")
        .translation("fundamentum.configuration.itemHud.anchor")
        .defineEnum(S + "anchor", HudBox.Anchor.HOTBAR_RIGHT);
    public static final ForgeConfigSpec.IntValue X = BUILDER
        .comment("Moves the HUD right (negative: left) from its anchor, in GUI pixels.")
        .translation("fundamentum.configuration.itemHud.x")
        .defineInRange(S + "x", 0, -4000, 4000);
    public static final ForgeConfigSpec.IntValue Y = BUILDER
        .comment("Moves the HUD down (negative: up) from its anchor, in GUI pixels.")
        .translation("fundamentum.configuration.itemHud.y")
        .defineInRange(S + "y", 0, -4000, 4000);
    public static final ForgeConfigSpec.DoubleValue SCALE = BUILDER
        .comment("Size of the HUD: 1 is the size of the game's own text.")
        .translation("fundamentum.configuration.itemHud.scale")
        .defineInRange(S + "scale", 1.0, 0.25, 4.0);
    public static final ForgeConfigSpec.ConfigValue<String> TEXT_COLOR = BUILDER
        .comment("Colour of text that doesn't set its own, as #RRGGBB.")
        .translation("fundamentum.configuration.itemHud.textColor")
        .define(S + "textColor", "#FFFFFF", o -> o instanceof String s && s.matches("#?[0-9a-fA-F]{6}"));
    public static final ForgeConfigSpec.DoubleValue TEXT_OPACITY = BUILDER
        .comment("Opacity of the text: 0.1 faint, 1 solid.")
        .translation("fundamentum.configuration.itemHud.textOpacity")
        .defineInRange(S + "textOpacity", 1.0, 0.1, 1.0);
    public static final ForgeConfigSpec.BooleanValue SHADOW = BUILDER
        .comment("Draw the text with the game's drop shadow.")
        .translation("fundamentum.configuration.itemHud.shadow")
        .define(S + "shadow", true);
    public static final ForgeConfigSpec.BooleanValue BACKGROUND = BUILDER
        .comment("Draw a dark backdrop behind the HUD, as chat and subtitles have.")
        .translation("fundamentum.configuration.itemHud.background")
        .define(S + "background", false);
    public static final ForgeConfigSpec.DoubleValue BACKGROUND_OPACITY = BUILDER
        .comment("How dark the backdrop is: 0 clear, 1 solid black.")
        .translation("fundamentum.configuration.itemHud.backgroundOpacity")
        .defineInRange(S + "backgroundOpacity", 0.35, 0.0, 1.0);

    public static final ForgeConfigSpec.IntValue SAFETY_RECHECK = BUILDER
        .comment("The HUD, the stats and survival meters and the multiblock ghost redraw only when something changes. As a safety net they also recheck every this many seconds, in case a change was missed. 0 turns the timer off.")
        .translation("fundamentum.configuration.refresh.safetyRecheck")
        .defineInRange("refresh.safetyRecheck", 5, 0, 600);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private ItemHudConfig() {
    }
}
