package com.fundamentum.sky;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client settings for fractal skies (config/fundamentum-sky-client.toml; Mods, Fundamentum, Config edits it in game).
 * Read every frame, so a change shows at once.
 */
public final class SkyConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue FRACTAL_SKIES = BUILDER
        .comment("Draw fractal skies when a moon, night event or dimension calls for one.")
        .translation("fundamentum.configuration.fractalSkies")
        .define("sky.fractalSkies", true);

    public static final ModConfigSpec.BooleanValue MELT = BUILDER
        .comment("The slow 'melting' vision under skies that ask for it (the Psychedelic Moon). Off turns it off entirely.")
        .translation("fundamentum.configuration.melt")
        .define("sky.melt", true);

    public static final ModConfigSpec.DoubleValue MELT_INTENSITY = BUILDER
        .comment("How strong the melting vision is, 0 (none) to 1.")
        .translation("fundamentum.configuration.meltIntensity")
        .defineInRange("sky.meltIntensity", 0.5, 0.0, 1.0);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private SkyConfig() {
    }
}
