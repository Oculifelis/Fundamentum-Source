package com.fundamentum.material;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/** {@code fundamentum-materials.toml}: the scan switch, sets to turn off, and where colours come from. */
public final class MaterialConfig {
    private MaterialConfig() { }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue AUTO_DETECT = BUILDER
        .comment("Find material sets by scanning every block at server start (stairs, slabs, walls, block families). Off: only sets from code and datapacks.")
        .define("auto_detect", true);
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED = BUILDER
        .comment("Material set ids to turn off, such as \"minecraft:mud_bricks\", or a whole mod as \"examplemod:*\". /fundamentum materials dump lists them.")
        .defineListAllowEmpty("disabled_sets", List.of(), () -> "", o -> o instanceof String);
    public static final ModConfigSpec.ConfigValue<String> COLOR_SOURCE = BUILDER
        .comment("\"texture\": average of the block texture where the jar has it, map colour otherwise. \"map\": map colours only (the same on every side).")
        .define("color_source", "texture");

    public static final ModConfigSpec SPEC = BUILDER.build();

    static boolean autoDetect() {
        try {
            return AUTO_DETECT.get();
        } catch (Throwable t) {
            return true;
        }
    }

    static List<? extends String> disabled() {
        try {
            return DISABLED.get();
        } catch (Throwable t) {
            return List.of();
        }
    }

    static boolean textureColors() {
        try {
            return !"map".equals(COLOR_SOURCE.get());
        } catch (Throwable t) {
            return true;
        }
    }
}
