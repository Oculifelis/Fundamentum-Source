package com.fundamentum;

import com.fundamentum.attribute.HealthCap;
import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.command.Showcase;
import com.fundamentum.command.Tps;
import com.fundamentum.cosmetic.CosmeticArmor;
import com.fundamentum.creature.CreatureTypes;
import com.fundamentum.damage.DamageClasses;
import com.fundamentum.damage.Elements;
import com.fundamentum.data.DataTables;
import com.fundamentum.element.ElementInteractions;
import com.fundamentum.glow.Glows;
import com.fundamentum.hook.Hooks;
import com.fundamentum.moon.Moons;
import com.fundamentum.moon.NightEvents;
import com.fundamentum.mount.Mounts;
import com.fundamentum.portal.PortalCrafting;
import com.fundamentum.portal.PortalSelfTest;
import com.fundamentum.pose.Poses;
import com.fundamentum.sickness.Sickness;
import com.fundamentum.sky.FractalSkies;
import com.fundamentum.sky.PsychedelicMoon;
import com.fundamentum.sky.SkyConfig;
import com.fundamentum.sky.SkySelfTest;
import com.fundamentum.summon.Summons;
import com.fundamentum.trigger.Triggers;
import com.fundamentum.whip.Whips;
import com.fundamentum.whip.WhipsConfig;
import com.fundamentum.wings.Wings;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Fundamentum: the shared groundwork under Oculifelis's mods. Each piece lives in its own package (attribute: the health
 * ceiling and shared combat stats; data: data tables; creature: creature types; sickness: use sickness; item: item
 * matching; curios: the wings, mount, hook, summon and quiver slots; wings: Terraria-style wings;
 * cosmetic: cosmetic armour; arrow: 3D arrows; mount: summoned mounts; hook: grappling hooks; client: the hotkeys and
 * tooltips; command: /fundamentum showcase and tps; trigger: left click as a trigger; pose: how items are held, and pose
 * clips; moon: special moons and night events; summon: minions; damage: damage classes and elements; whip: cord whips;
 * glow: coloured Glowing outlines; portal: portal crafting, items thrown through a portal changing; element: elemental
 * auras, reactions and affinities; compat.jei: the optional JEI categories; sky: fractal skies and the Psychedelic Moon; biome: data-driven biome slices;
 * curve: shared curves; net: the 1.20.1 network channels; effect: data-driven status effects; armorset: armour set bonuses;
 * ammo: ammo search and quivers; mana: the mana bridge to other mods' pools; hud: HUD layer order and switches);
 * mods that rely on one list fundamentum as a required dependency.
 */
@Mod(Fundamentum.MODID)
public final class Fundamentum {
    public static final String MODID = "fundamentum";

    public Fundamentum() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        // the version-neutral layer (port/): attachments, level and stack data, packets, registration
        com.fundamentum.port.Port.init(modBus);
        loadPortTest(modBus);
        com.fundamentum.config.FConfig.init();
        loadTest("com.fundamentum.config.ConfigSelfTest");
        com.fundamentum.event.EventHooks.init();
        loadTest("com.fundamentum.event.EventSelfTest");
        var context = ModLoadingContext.get();
        HealthCap.lift();
        DataTables.init();
        StatAttributes.init(modBus);
        Elements.init();
        com.fundamentum.damage.DataElements.init();
        DamageClasses.init();
        ElementInteractions.init(modBus);
        com.fundamentum.soulfire.SoulFlames.init(modBus);
        com.fundamentum.shield.Shields.init(modBus);
        com.fundamentum.effect.Effects.init(modBus);
        com.fundamentum.armorset.ArmorSets.init(modBus);
        com.fundamentum.ammo.Ammo.init(modBus);
        loadSystemsTest(modBus);
        context.registerConfig(ModConfig.Type.COMMON, com.fundamentum.soulfire.SoulFireConfig.SPEC, "fundamentum-soulfire.toml");
        CreatureTypes.init();
        Sickness.init(modBus);
        Wings.init();
        CosmeticArmor.init(modBus);
        Mounts.init(modBus);
        Hooks.init(modBus);
        Summons.init(modBus);
        com.fundamentum.summon.Sentries.init(modBus);
        com.fundamentum.spear.Spears.init(modBus);
        com.fundamentum.gauntlet.Gauntlets.init(modBus);
        com.fundamentum.thrown.ThrownWeapons.init(modBus);
        TypesSelfTest.init();
        Showcase.init();
        Tps.init();
        Triggers.init(modBus);
        Whips.init(modBus);
        com.fundamentum.flail.Flails.init(modBus);
        Glows.init(modBus);
        context.registerConfig(ModConfig.Type.CLIENT, WhipsConfig.SPEC);
        Poses.init(modBus);
        Moons.init(modBus);
        NightEvents.init(modBus);
        PortalCrafting.init(modBus);
        com.fundamentum.relic.Relics.init(modBus);
        PortalSelfTest.init();
        FractalSkies.init();
        PsychedelicMoon.init();
        SkySelfTest.init();
        context.registerConfig(ModConfig.Type.COMMON, PsychedelicMoon.SPEC);
        context.registerConfig(ModConfig.Type.CLIENT, SkyConfig.SPEC, "fundamentum-sky-client.toml");
        context.registerConfig(ModConfig.Type.CLIENT, com.fundamentum.client.ItemHudConfig.SPEC, "fundamentum-hud-client.toml");
        com.fundamentum.biome.BiomeSlices.init();
        context.registerConfig(ModConfig.Type.COMMON, com.fundamentum.biome.BiomeSlicesConfig.SPEC, "fundamentum-biomes.toml");
        // bosses, boss music, loot helpers, material sets
        com.fundamentum.music.BossMusic.init(modBus);
        com.fundamentum.boss.Bosses.init(modBus);
        com.fundamentum.loot.Loots.init(modBus);
        com.fundamentum.material.Materials.init(modBus);
        context.registerConfig(ModConfig.Type.COMMON, com.fundamentum.material.MaterialConfig.SPEC, "fundamentum-materials.toml");
        if (FMLEnvironment.dist.isClient()) {
            FundamentumClient.init();
        }
    }

    /** A self test with a no-argument {@code init()}, only when its source folder is on the class path (-P<name>test). */
    private static void loadTest(String className) {
        try {
            Class.forName(className).getMethod("init").invoke(null);
        } catch (ClassNotFoundException absent) {
            // a normal build
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The porting layer test, only when {@code src/porttest} is on the class path (-Pporttest). */
    private static void loadPortTest(net.minecraftforge.eventbus.api.IEventBus modBus) {
        try {
            Class.forName("com.fundamentum.port.PortSelfTest").getMethod("init").invoke(null);
        } catch (ClassNotFoundException absent) {
            // a normal build
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The systems test (effects, armour sets, ammo, shield block events), only when {@code src/systemstest} is on the class path (-Psystemstest). */
    private static void loadSystemsTest(net.minecraftforge.eventbus.api.IEventBus modBus) {
        try {
            Class.forName("com.fundamentum.SystemsSelfTest").getMethod("init", net.minecraftforge.eventbus.api.IEventBus.class).invoke(null, modBus);
        } catch (ClassNotFoundException absent) {
            // a normal build
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
