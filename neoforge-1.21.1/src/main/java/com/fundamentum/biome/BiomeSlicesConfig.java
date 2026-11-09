package com.fundamentum.biome;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/** {@code fundamentum-biomes.toml}: the switch for all biome slices, single slices to leave out, and TerraBlender's weight. */
public final class BiomeSlicesConfig {
    private BiomeSlicesConfig() { }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
        .comment("Place the biome slices that mods and datapacks add (data/<namespace>/fundamentum/biome_slices). Off: no slice is placed.")
        .define("enabled", true);
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED = BUILDER
        .comment("Slice ids to leave out, such as \"examplemod:jungle_ruins\". /fundamentum biomes slices lists them.")
        .defineListAllowEmpty("disabled_slices", List.of(), () -> "", o -> o instanceof String);
    public static final ModConfigSpec.IntValue TERRABLENDER_WEIGHT = BUILDER
        .comment("With TerraBlender installed, the slices form one region per dimension; its weight against the other regions (vanilla's is 10).")
        .defineInRange("terrablender_region_weight", 10, 1, 1000);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
