package com.fundamentum;

import com.fundamentum.attribute.HealthCap;
import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.command.Showcase;
import com.fundamentum.command.Tps;
import com.fundamentum.cosmetic.CosmeticArmor;
import com.fundamentum.creature.CreatureTypes;
import com.fundamentum.damage.DamageClasses;
import com.fundamentum.damage.Elements;
import com.fundamentum.element.ElementInteractions;
import com.fundamentum.data.DataTables;
import com.fundamentum.hook.Hooks;
import com.fundamentum.moon.Moons;
import com.fundamentum.moon.NightEvents;
import com.fundamentum.mount.Mounts;
import com.fundamentum.pose.Poses;
import com.fundamentum.portal.PortalCrafting;
import com.fundamentum.portal.PortalSelfTest;
import com.fundamentum.sky.FractalSkies;
import com.fundamentum.sky.PsychedelicMoon;
import com.fundamentum.sky.SkyConfig;
import com.fundamentum.sickness.Sickness;
import com.fundamentum.summon.Summons;
import com.fundamentum.trigger.Triggers;
import com.fundamentum.glow.Glows;
import com.fundamentum.whip.Whips;
import com.fundamentum.whip.WhipsConfig;
import com.fundamentum.wings.Wings;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;

/**
 * Fundamentum: the shared groundwork under Oculifelis's mods. Each piece lives in its own package (attribute: the health
 * ceiling and shared combat stats; data: data tables; creature: creature types; sickness: use sickness; item: item
 * matching; curios: the wings, mount and hook slots; wings: Terraria-style wings;
 * cosmetic: cosmetic armour; arrow: 3D arrows; mount: summoned mounts; hook: grappling hooks; client: the hotkeys;
 * command: /fundamentum showcase; trigger: left click as a trigger; pose: how items are held, and pose clips; moon: special moons; whip: cord whips; glow: coloured Glowing outlines;
 * portal: portal crafting, items thrown through a portal changing; element: elemental auras, reactions and affinities;
 * compat.jei: the optional JEI categories; sky: fractal skies and the Psychedelic Moon; biome: data-driven biome slices);
 * mods that rely on one list fundamentum as a required dependency.
 */
@Mod(Fundamentum.MODID)
public final class Fundamentum {
    public static final String MODID = "fundamentum";

    public Fundamentum(IEventBus modBus, ModContainer container) {
        HealthCap.lift();
        DataTables.init(modBus);
        StatAttributes.init(modBus);
        Elements.init();
        com.fundamentum.damage.DataElements.init();
        DamageClasses.init();
        ElementInteractions.init(modBus);
        com.fundamentum.soulfire.SoulFlames.init(modBus);
        com.fundamentum.shield.Shields.init(modBus);
        container.registerConfig(ModConfig.Type.COMMON, com.fundamentum.soulfire.SoulFireConfig.SPEC, "fundamentum-soulfire.toml");
        CreatureTypes.init();
        Sickness.init(modBus);
        Wings.init(modBus);
        CosmeticArmor.init(modBus);
        Mounts.init(modBus);
        Hooks.init(modBus);
        Summons.init(modBus);
        com.fundamentum.summon.Sentries.init(modBus);
        com.fundamentum.spear.Spears.init(modBus);
        com.fundamentum.gauntlet.Gauntlets.init(modBus);
        TypesSelfTest.init();
        Showcase.init();
        Tps.init();
        Triggers.init(modBus);
        Whips.init(modBus);
        com.fundamentum.thrown.ThrownWeapons.init(modBus);
        com.fundamentum.flail.Flails.init(modBus);
        Glows.init(modBus);
        container.registerConfig(ModConfig.Type.CLIENT, WhipsConfig.SPEC);
        Poses.init(modBus);
        Moons.init(modBus);
        NightEvents.init(modBus);
        PortalCrafting.init(modBus);
        com.fundamentum.relic.Relics.init(modBus);
        PortalSelfTest.init();
        FractalSkies.init();
        PsychedelicMoon.init();
        com.fundamentum.sky.SkySelfTest.init();
        container.registerConfig(ModConfig.Type.COMMON, PsychedelicMoon.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, SkyConfig.SPEC, "fundamentum-sky-client.toml");
        container.registerConfig(ModConfig.Type.CLIENT, com.fundamentum.client.ItemHudConfig.SPEC, "fundamentum-hud-client.toml");
        // biome slices (biome/BiomeSlices)
        com.fundamentum.biome.BiomeSlices.init();
        container.registerConfig(ModConfig.Type.COMMON, com.fundamentum.biome.BiomeSlicesConfig.SPEC, "fundamentum-biomes.toml");
    }
}
