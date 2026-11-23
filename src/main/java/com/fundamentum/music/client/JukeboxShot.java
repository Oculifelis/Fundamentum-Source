package com.fundamentum.music.client;

import com.fundamentum.music.JukeboxProgress;
import com.fundamentum.music.JukeboxProgress.Track;
import java.lang.reflect.Field;
import java.util.Optional;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Dev client only ({@code gradlew runClient -Pjukeboxshot}, which also sets {@code fundamentum.nofocus}): muted, off
 * screen, in a fresh flat creative world it places a jukebox in front of the player and plays cat, checks
 * {@link JukeboxTracker} against the server's song player, screenshots the Jade line (when Jade is loaded), jumps the
 * server's song to 1:30 to check the Jade sync, screenshots twice more and closes. Pictures go to
 * run/screenshots/jukeboxshot-*.png; log lines start with JUKEBOXSHOT.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class JukeboxShot {
    private JukeboxShot() { }

    static final boolean ON = Boolean.getBoolean("fundamentum.jukeboxshot");

    private static boolean hidden;
    private static boolean started;
    private static int ticks;
    private static int failed;
    private static BlockPos box;

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
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        }
        if (mc.level == null || mc.player == null) {
            if (!started && mc.gui.screen() instanceof TitleScreen) {
                started = true;
                create(mc);
            }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        ticks++;
        boolean jade = ModList.get().isLoaded("jade");
        if (ticks == 1 && mc.gui.screen() != null) {
            mc.gui.setScreen(null);
        } else if (ticks == 60) {
            say("Jade loaded: " + jade);
            server.execute(() -> {
                ServerPlayer p = player(server);
                BlockPos feet = p.blockPosition();
                box = feet.offset(0, 1, 2);
                p.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0.0F, 0.0F);
                ServerLevel level = p.level();
                level.setBlockAndUpdate(box, Blocks.JUKEBOX.defaultBlockState());
                if (level.getBlockEntity(box) instanceof JukeboxBlockEntity j) {
                    j.setTheItem(new ItemStack(Items.MUSIC_DISC_CAT));
                } else {
                    fail("no jukebox block entity at " + box);
                }
            });
        } else if (ticks == 120) {
            Optional<Track> client = JukeboxTracker.at(box);
            Optional<Track> server1 = serverTrack(server);
            if (client.isEmpty() || server1.isEmpty()) {
                fail("tracker " + client + ", server " + server1);
            } else {
                Track c = client.get();
                Track s = server1.get();
                check(Math.abs(c.elapsedTicks() - s.elapsedTicks()) < 10, " tracker from the start event: "
                    + c.name().getString() + " " + c.timeText() + " (" + fmt(c.elapsedTicks()) + " ticks; server " + s.elapsedTicks()
                    + "), length " + c.lengthTicks() + " ticks, range " + c.hearingRange());
                check(c.lengthTicks() == 185 * 20, " cat is 3:05 from the registry: " + c.timeText());
            }
            Vec3 eye = mc.player.position();
            check(JukeboxTracker.nearest(eye).isPresent(), " nearest() finds it from the player");
            check(JukeboxTracker.nearest(eye.add(100, 0, 0)).isEmpty(), " nearest() 100 blocks away is empty");
            check("1:12 / 3:05".equals(JukeboxProgress.timeText(72.4, 185)), " timeText: "
                + JukeboxProgress.timeText(72.4, 185));
        } else if (ticks == 140) {
            shot(mc, "1-start");
        } else if (ticks == 160) {
            server.execute(() -> {                          // jump the server's song to 1:30; only Jade can tell the client
                if (player(server).level().getBlockEntity(box) instanceof JukeboxBlockEntity j) {
                    try {
                        Field f = JukeboxSongPlayer.class.getDeclaredField("ticksSinceSongStarted");
                        f.setAccessible(true);
                        f.setLong(j.getSongPlayer(), 90 * 20L);
                    } catch (ReflectiveOperationException e) {
                        fail("could not jump the song: " + e);
                    }
                }
            });
        } else if (ticks == 260) {
            Optional<Track> c = JukeboxTracker.at(box);
            Optional<Track> s = serverTrack(server);
            boolean synced = c.isPresent() && s.isPresent() && Math.abs(c.get().elapsedTicks() - s.get().elapsedTicks()) < 25;
            if (jade) {
                say("Jade's last data: " + com.fundamentum.compat.jade.JukeboxJadeClient.lastData());
                check(synced, " Jade synced the jump: client " + c.map(Track::timeText).orElse("none")
                    + ", server " + s.map(Track::timeText).orElse("none"));
            } else {
                check(!synced && c.isPresent(), " without Jade the client keeps its own time: client "
                    + c.map(Track::timeText).orElse("none") + ", server " + s.map(Track::timeText).orElse("none"));
            }
            shot(mc, "2-after-jump");
        } else if (ticks == 360) {
            shot(mc, "3-five-seconds-later");
        } else if (ticks == 380) {
            server.execute(() -> {
                if (player(server).level().getBlockEntity(box) instanceof JukeboxBlockEntity j) {
                    j.setTheItem(ItemStack.EMPTY);      // stops the song without dropping the disc
                }
            });
        } else if (ticks == 400) {
            check(JukeboxTracker.at(box).isEmpty(), " the stop event removed it");
        } else if (ticks == 420) {
            say("DONE failed=" + failed);
            mc.stop();
        }
    }

    private static Optional<Track> serverTrack(MinecraftServer server) {
        // on the server thread: a server level hands out no block entities to other threads
        return server.submit(() -> player(server).level().getBlockEntity(box) instanceof JukeboxBlockEntity j
            ? JukeboxProgress.of(j) : Optional.<Track>empty()).join();
    }

    private static String fmt(double d) {
        return String.format("%.1f", d);
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "jukeboxshot-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
            msg -> say("saved jukeboxshot-" + name + ".png"));
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
        }
        say((ok ? "PASS" : "FAIL") + what);
    }

    private static void fail(String why) {
        failed++;
        say("FAIL " + why);
    }

    private static void say(String line) {
        System.out.println("JUKEBOXSHOT " + line);
    }

    private static void create(Minecraft mc) {
        say("creating a flat world");
        mc.options.renderDistance().set(6);
        mc.options.simulationDistance().set(5);
        String name = "jukeboxshot" + System.currentTimeMillis() / 1000;
        LevelSettings settings = new LevelSettings(name, GameType.CREATIVE,
            new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261106L, false, false),
            provider -> provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
            new TitleScreen());
    }
}
