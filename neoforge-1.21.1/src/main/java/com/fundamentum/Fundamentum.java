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
 * matching; slots: the wings, mount, hook, summon and quiver slots (Curios or built in); wings: Terraria-style wings;
 * cosmetic: cosmetic armour; arrow: 3D arrows; mount: summoned mounts; hook: grappling hooks; client: the hotkeys;
 * command: /fundamentum showcase; trigger: left click as a trigger; pose: how items are held, and pose clips; moon: special moons; whip: cord whips; glow: coloured Glowing outlines;
 * portal: portal crafting, items thrown through a portal changing; element: elemental auras, reactions and affinities;
 * recipeview: one declaration feeds JEI, EMI and REI; sky: fractal skies and the Psychedelic Moon; biome: data-driven biome slices;
 * effect: data-driven status effects; armorset: armour set bonuses; ammo: ammo search and quivers; boss: boss bars,
 * phases, arenas and defeat records; music: boss music; loot: loot additions, pools and boss drops; material: material
 * sets, palettes and the material swap processor; mana: the mana bridge to other mods' pools; hud: HUD layer order and switches);
 * mods that rely on one list fundamentum as a required dependency.
 */
@Mod(Fundamentum.MODID)
public final class Fundamentum {
    public static final String MODID = "fundamentum";

    public Fundamentum(IEventBus modBus, ModContainer container) {
        // the version-neutral layer (port/): attachments, level and stack data, packets, registration
        com.fundamentum.port.Port.init(modBus);
        loadPortTest(modBus);
        com.fundamentum.config.FConfig.init();
        loadTest("com.fundamentum.config.ConfigSelfTest");
        com.fundamentum.event.EventHooks.init();
        loadTest("com.fundamentum.event.EventSelfTest");
        com.fundamentum.unlock.Unlocks.init();
        loadTest("com.fundamentum.unlock.UnlockSelfTest");
        com.fundamentum.stat.Stats.init();
        loadTest("com.fundamentum.stat.StatSelfTest");
        loadTest("com.fundamentum.recipeview.RecipeViewSelfTest");
        loadTest("com.fundamentum.relic.RelicSelfTest");
        loadTest("com.fundamentum.slots.SlotSelfTest");
        loadTest("com.fundamentum.unify.UnifySelfTest");
        loadTest("com.fundamentum.multiblock.MultiblockSelfTest");
        com.fundamentum.port.FTransfer.init(modBus);
        com.fundamentum.transfer.Transfer.init();
        loadTest("com.fundamentum.transfer.TransferSelfTest");
        com.fundamentum.port.FMobs.init(modBus);
        com.fundamentum.mobgoal.MobGoals.init();
        loadTest("com.fundamentum.mobgoal.MobGoalSelfTest");
        com.fundamentum.port.FClimate.init(modBus);
        com.fundamentum.climate.Seasons.init();
        loadTest("com.fundamentum.climate.ClimateSelfTest");
        com.fundamentum.food.Food.init();
        loadTest("com.fundamentum.food.FoodSelfTest");
        com.fundamentum.survival.Survival.init();
        loadTest("com.fundamentum.survival.SurvivalSelfTest");
        loadTest("com.fundamentum.port.PerfSelfTest");
        HealthCap.lift();
        DataTables.init(modBus);
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
        container.registerConfig(ModConfig.Type.COMMON, com.fundamentum.soulfire.SoulFireConfig.SPEC, "fundamentum-soulfire.toml");
        CreatureTypes.init();
        Sickness.init(modBus);
        Wings.init(modBus);
        CosmeticArmor.init(modBus);
        com.fundamentum.slots.Slots.init();
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
        // recipe viewer pages (JEI, EMI, REI): portal crafting and relic info
        com.fundamentum.portal.PortalViews.init();
        com.fundamentum.relic.RelicViews.init();
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
        // bosses, boss music, loot helpers, material sets
        com.fundamentum.music.BossMusic.init(modBus);
        com.fundamentum.boss.Bosses.init(modBus);
        com.fundamentum.loot.Loots.init(modBus);
        com.fundamentum.material.Materials.init(modBus);
        com.fundamentum.unify.UnifyHooks.init(modBus);
        com.fundamentum.multiblock.Multiblocks.init();
        container.registerConfig(ModConfig.Type.COMMON, com.fundamentum.material.MaterialConfig.SPEC, "fundamentum-materials.toml");
        // music rules (music/MusicDirector, boss music included) and the guide (guide/Guides)
        com.fundamentum.music.MusicDirector.init();
        loadTest("com.fundamentum.music.MusicSelfTest");
        com.fundamentum.guide.Guides.init();
        loadTest("com.fundamentum.guide.GuideSelfTest");
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
    private static void loadPortTest(IEventBus modBus) {
        try {
            Class.forName("com.fundamentum.port.PortSelfTest").getMethod("init").invoke(null);
        } catch (ClassNotFoundException absent) {
            // a normal build
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The systems test (effects, armour sets, ammo, shield block events), only when {@code src/systemstest} is on the class path (-Psystemstest). */
    private static void loadSystemsTest(IEventBus modBus) {
        try {
            Class.forName("com.fundamentum.SystemsSelfTest").getMethod("init", IEventBus.class).invoke(null, modBus);
        } catch (ClassNotFoundException absent) {
            // a normal build
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
