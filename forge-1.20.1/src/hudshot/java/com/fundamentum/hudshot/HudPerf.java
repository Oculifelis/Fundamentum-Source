package com.fundamentum.hudshot;

import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Test runs only ({@code gradlew runClient -Phudshot}): hides and mutes the client, makes a flat survival world and
 * times Fundamentum's HUD layers per frame with System.nanoTime for about 15 seconds, then prints the median and worst.
 * With {@code -Phudshot=perf} it closes after that; otherwise {@link HudShot} goes on.
 */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class HudPerf {
    private HudPerf() { }

    static final String MODE = System.getProperty("fundamentum.hudshot", "");
    static final boolean ON = !MODE.isEmpty();
    static volatile boolean done;
    static int ticks;

    private static boolean hidden;
    private static boolean started;
    private static int menuTicks;
    private static boolean measuring;
    private static long layerStart;
    private static long frameNanos;
    private static final long[] FRAMES = new long[20000];
    private static int frames;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!ON) return;
        Minecraft mc = Minecraft.getInstance();
        if (!hidden) {
            hidden = true;
            GLFW.glfwSetWindowPos(mc.getWindow().getWindow(), -4000, -4000);
            mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            mc.options.pauseOnLostFocus = false;
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        }
        if (mc.level == null || mc.player == null) {
            // the title screen, or a menu shown in front of it (the mod warnings screen): start once loading is over
            if (!started && mc.screen != null && mc.getOverlay() == null && (mc.screen instanceof TitleScreen || ++menuTicks > 40)) {
                started = true;
                create(mc);
            }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || done) return;
        ticks++;
        if (ticks == 1 && mc.screen != null) {
            mc.setScreen(null);
        } else if (ticks == 20) {
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                p.setGameMode(GameType.SURVIVAL);
                p.teleportTo(p.serverLevel(), p.getX(), p.getY(), p.getZ(), 0.0F, 10.0F);
            });
        } else if (ticks == 100) {
            frames = 0;
            measuring = true;
        } else if (ticks == 400) {
            measuring = false;
            long[] f = Arrays.copyOf(FRAMES, Math.min(frames, FRAMES.length));
            Arrays.sort(f);
            if (f.length == 0) {
                say("FAIL no frames timed");
            } else {
                double median = f[f.length / 2] / 1000.0;
                double worst = f[f.length - 1] / 1000.0;
                double p99 = f[(int) Math.min(f.length - 1, Math.floor(f.length * 0.99))] / 1000.0;
                say(String.format(java.util.Locale.ROOT, "PERF frames %d  median %.1f us  p99 %.1f us  worst %.1f us", f.length, median, p99, worst));
            }
            shot(mc, "01-hud");
        } else if (ticks == 410) {
            done = true;
            if ("perf".equals(MODE)) {
                say("DONE perf");
                mc.stop();
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void frameStart(RenderGuiEvent.Pre event) {
        frameNanos = 0;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void frameEnd(RenderGuiEvent.Post event) {
        if (measuring && frames < FRAMES.length) FRAMES[frames++] = frameNanos;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void layerStart(RenderGuiOverlayEvent.Pre event) {
        if ("fundamentum".equals(event.getOverlay().id().getNamespace())) layerStart = System.nanoTime();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void layerEnd(RenderGuiOverlayEvent.Post event) {
        if ("fundamentum".equals(event.getOverlay().id().getNamespace())) frameNanos += System.nanoTime() - layerStart;
    }

    private static void create(Minecraft mc) {
        say("creating a flat world");
        mc.options.renderDistance().set(6);
        mc.options.simulationDistance().set(5);
        String name = "hudshot" + System.currentTimeMillis() / 1000;
        LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
            new net.minecraft.world.level.GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261111L, false, false),
            access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
    }

    /** Saves screenshots/hudshot-NAME.png in the run folder. */
    static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "hudshot-" + name + ".png", mc.getMainRenderTarget(), msg -> say("saved hudshot-" + name + ".png"));
    }

    static void say(String line) {
        System.out.println("HUDSHOT " + line);
    }
}
