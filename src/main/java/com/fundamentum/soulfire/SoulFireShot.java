package com.fundamentum.soulfire;

import com.fundamentum.api.SoulFire;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Dev client only: {@code gradlew runClient -Psoulfireshot} (or {@code -Dfundamentum.soulfireshot=true} with {@code -Dfundamentum.nofocus=true}).
 * Muted and off screen, in a fresh flat world: a pig and a zombie standing in soul fire beside a pig in ordinary fire,
 * the player soul-burning seen from the front, then the first-person screen fire with soul fire and ordinary fire.
 * Pictures go to run/screenshots/soulfire-*.png; log lines start with SOULFIRESHOT.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class SoulFireShot {
    static final boolean ON = Boolean.getBoolean("fundamentum.soulfireshot") || "1".equals(System.getenv("FUNDAMENTUM_SOULFIRESHOT"));

    private static boolean hidden;
    private static boolean started;
    private static int ticks;
    private static int burst;

    private SoulFireShot() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!ON) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!hidden) {
            hidden = true;
            GLFW.glfwSetWindowPos(mc.getWindow().handle(), -4000, -4000);
            mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            mc.options.pauseOnLostFocus = false;
            mc.options.hideGui = true;
        }
        if (mc.level == null || mc.player == null) {
            if (!started && mc.screen instanceof TitleScreen) {
                started = true;
                create(mc);
            }
            return;
        }
        if (mc.screen != null) {
            mc.setScreen(null);
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        ticks++;
        if (ticks == 100) {
            server.execute(() -> setUp(server));
        } else if (ticks == 160) {
            say("mobs: soul pig " + soul(mc, "soul_pig") + ", soul zombie " + soul(mc, "soul_zombie") + ", ordinary pig " + soul(mc, "fire_pig"));
            grab(mc, "soulfire-mobs");
        } else if (ticks > 160 && ticks <= 184 && ticks % 2 == 0) {
            grab(mc, String.format("soulfire-burst-%02d", burst++));
        } else if (ticks == 190) {
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                SoulFire.ignite(p, 2000);
            });
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        } else if (ticks == 230) {
            say("player soul burning on the client: " + SoulFire.isSoulBurning(mc.player));
            grab(mc, "soulfire-player-third");
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            server.execute(() -> look(server, -90.0F, 40.0F));
        } else if (ticks == 260) {
            grab(mc, "soulfire-firstperson");
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                SoulFire.clear(p);
            });
        } else if (ticks == 290) {
            say("player soul burning after clear: " + SoulFire.isSoulBurning(mc.player) + ", on fire " + mc.player.isOnFire());
            grab(mc, "soulfire-firstperson-ordinary");
        } else if (ticks == 320) {
            say("DONE");
            mc.stop();
        }
    }

    private static String soul(Minecraft mc, String tag) {
        for (var e : mc.level.entitiesForRendering()) {
            if (e.getCustomName() != null && e.getCustomName().getString().equals(tag)) {
                return e.isOnFire() + "/" + SoulFire.isSoulBurning(e);
            }
        }
        return "missing";
    }

    private static void setUp(MinecraftServer server) {
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        ServerLevel level = p.level();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 20000, 0, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20000, 4, false, false));
        BlockPos base = p.blockPosition();
        mob(level, EntityType.PIG, base.offset(4, 0, -1), Blocks.SOUL_SOIL, Blocks.SOUL_FIRE, "soul_pig");
        mob(level, EntityType.ZOMBIE, base.offset(4, 0, 1), Blocks.SOUL_SOIL, Blocks.SOUL_FIRE, "soul_zombie");
        mob(level, EntityType.PIG, base.offset(4, 0, 3), Blocks.NETHERRACK, Blocks.FIRE, "fire_pig");
        look(server, -75.0F, 25.0F);
    }

    private static void look(MinecraftServer server, float yaw, float pitch) {
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        p.snapTo(p.getX(), p.getY(), p.getZ(), yaw, pitch);
        Minecraft.getInstance().player.setYRot(yaw);
        Minecraft.getInstance().player.setXRot(pitch);
    }

    private static void mob(ServerLevel level, EntityType<? extends Mob> type, BlockPos pos, Block floor, Block fire, String name) {
        level.setBlock(pos.below(), floor.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(pos, fire.defaultBlockState(), Block.UPDATE_ALL);
        Mob mob = type.create(level, EntitySpawnReason.COMMAND);
        mob.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        mob.setYRot(90.0F);
        mob.setYHeadRot(90.0F);
        mob.setYBodyRot(90.0F);
        mob.setNoAi(true);
        mob.setInvulnerable(true);
        mob.setCustomName(net.minecraft.network.chat.Component.literal(name));
        mob.setCustomNameVisible(false);
        level.addFreshEntity(mob);
    }

    private static void grab(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), 1, msg -> say("saved " + name + ".png"));
    }

    private static void say(String line) {
        System.out.println("SOULFIRESHOT " + line);
    }

    private static void create(Minecraft mc) {
        say("creating a flat world");
        mc.options.renderDistance().set(6);
        mc.options.simulationDistance().set(5);
        String name = "soulfireshot" + System.currentTimeMillis() / 1000;
        LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL,
            new LevelSettings.DifficultySettings(Difficulty.EASY, false, false), true, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261106L, false, false),
            provider -> provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
            new TitleScreen());
    }
}
