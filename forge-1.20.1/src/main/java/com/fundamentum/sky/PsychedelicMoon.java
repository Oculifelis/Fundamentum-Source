package com.fundamentum.sky;

import com.fundamentum.moon.Moons;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * The Psychedelic Moon ({@code fundamentum:psychedelic}, data in {@code fundamentum/moons/psychedelic.json}): a rare
 * moon whose night sky is a fractal ({@code fundamentum/fractal_skies/psychedelic.json}) with a slow melting vision.
 * It only rises when {@code moons.psychedelicMoon} in config/fundamentum-common.toml is on: off in the released jar, on
 * in dev runs ({@link PsychedelicRules#defaultOn}; {@code -Dfundamentum.psychedelic=true|false} overrides). Switched
 * off, it neither rolls nor starts by {@code /fundamentum moon start fundamentum:psychedelic}, which says why.
 */
public final class PsychedelicMoon {
    private PsychedelicMoon() { }

    public static final String ID = PsychedelicRules.ID;

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLED = BUILDER
        .comment("Let the Psychedelic Moon rise (a rare night with a fractal sky and a slow melting vision).",
            "Off in the released mod. While off, /fundamentum moon start fundamentum:psychedelic refuses too.")
        .translation("fundamentum.configuration.psychedelicMoon")
        .define("moons.psychedelicMoon", PsychedelicRules.defaultOn(production(), System.getProperty("fundamentum.psychedelic")));

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private static boolean production() {
        try {
            return FMLEnvironment.production;
        } catch (RuntimeException | LinkageError e) {
            return true;    // outside the game (unit tests): treat as released
        }
    }

    public static void init() {
        Moons.registerChance((level, moon, c) -> PsychedelicRules.chance(moon.id(), c, ENABLED.get()));
        Moons.registerSwitch((level, moon) -> ID.equals(moon.id()) && !ENABLED.get()
            ? Component.translatable("commands.fundamentum.psychedelic.disabled") : null);
    }
}
