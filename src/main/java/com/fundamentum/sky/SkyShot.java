package com.fundamentum.sky;

import com.fundamentum.moon.MoonsClient;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Dev client only ({@code gradlew runClient -Pskyshot}, which also sets {@code fundamentum.nofocus}): muted, off screen,
 * in a fresh flat world it raises the Psychedelic Moon with {@code /fundamentum moon start fundamentum:psychedelic} and
 * screenshots its sky where seams would show (straight up, a cube corner, the horizon at four yaws across the 180/-180
 * wrap, down at the horizon line), each with the melting vision off and on, then the melt at full, then the three-axis
 * mapping at its blend lines and other fractals, and closes the game. Pictures go to run/screenshots/skyshot-*.png; log
 * lines start with SKYSHOT.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class SkyShot {
    private SkyShot() { }

    static final boolean ON = Boolean.getBoolean("fundamentum.skyshot");

    /** One picture: a sky to preview (null: the moon's own), where to look, the melt, and the sky's clock (-1: real). */
    private record Shot(String name, FractalSky preview, float yaw, float pitch, boolean melt, double intensity, float time) {}

    private static FractalSky sky(String name, int fractal, int palette, int mapping, float cx, float cy, float scale, float depth, int iterations) {
        return new FractalSky(Identifier.fromNamespaceAndPath("fundamentum", "skyshot_" + name), "", "", "", 0, fractal, iterations, palette, 0xFFFFFF,
            mapping, cx, cy, scale, 0.01F, depth, 0.02F, 0.005F, 0.002F, 0.85F, 0.25F, 0.25F, 0.0F, false);
    }

    private static final FractalSky ACID_AXES = sky("julia_acid_axes", 1, 3, 1, 0.0F, 0.0F, 1.4F, 0.5F, 160);

    private static final List<Shot> SHOTS = shots();

    private static List<Shot> shots() {
        List<Shot> list = new ArrayList<>();
        // the Psychedelic Moon's tour, looking straight up: the whole set, then dives into three of its seven spots
        // (one dive every 80 s, deepest 40 s in)
        float[][] tour = {{0.0F}, {20.0F}, {40.0F}, {120.0F}, {200.0F}, {280.0F}};
        String[] stage = {"whole-set", "diving", "deep-seahorse-valley", "deep-feigenbaum-point", "deep-spiral-below", "deep-elephant-valley"};
        for (int i = 0; i < tour.length; i++) {
            list.add(new Shot("tour-" + stage[i], null, 0.0F, -90.0F, false, 0.5, tour[i][0]));
        }
        list.add(new Shot("whole-set-corner", null, 45.0F, -35.0F, false, 0.5, 0.0F));
        list.add(new Shot("whole-set-horizon", null, 0.0F, -8.0F, false, 0.5, 0.0F));
        // seams mid-dive (detail everywhere), melt off then on: [name, yaw, pitch]
        Object[][] views = {{"up", 0.0F, -90.0F}, {"corner", 45.0F, -35.0F}, {"horizon-yaw0", 0.0F, -8.0F},
            {"horizon-yaw90", 90.0F, -8.0F}, {"horizon-yaw180", 180.0F, -8.0F}, {"horizon-yaw270", -90.0F, -8.0F},
            {"horizon-line", 30.0F, 10.0F}};
        for (Object[] v : views) {
            list.add(new Shot("psychedelic-" + v[0] + "-melt-off", null, (Float) v[1], (Float) v[2], false, 0.5, 30.0F));
            list.add(new Shot("psychedelic-" + v[0] + "-melt-on", null, (Float) v[1], (Float) v[2], true, 0.5, 30.0F));
        }
        list.add(new Shot("psychedelic-horizon-melt-full", null, 30.0F, -12.0F, true, 1.0, 30.0F));
        list.add(new Shot("psychedelic-ground-melt-full", null, 30.0F, 20.0F, true, 1.0, 30.0F));
        // the three-axis mapping where two or three projections blend
        list.add(new Shot("axes-up", ACID_AXES, 0.0F, -90.0F, false, 0.5, -1.0F));
        list.add(new Shot("axes-corner", ACID_AXES, 45.0F, -35.0F, false, 0.5, -1.0F));
        list.add(new Shot("axes-horizon-yaw45", ACID_AXES, 45.0F, -8.0F, false, 0.5, -1.0F));
        list.add(new Shot("axes-horizon-yaw180", ACID_AXES, 180.0F, -8.0F, false, 0.5, -1.0F));
        // other fractals
        list.add(new Shot("mandelbrot-void-axes", sky("mandelbrot_void", 0, 5, 1, -0.75F, 0.0F, 1.6F, 0.0F, 160), 30.0F, -35.0F, false, 0.5, -1.0F));
        list.add(new Shot("julia-ice", sky("julia_ice", 1, 2, 0, 0.0F, 0.0F, 1.6F, 1.0F, 180), 30.0F, -60.0F, false, 0.5, -1.0F));
        list.add(new Shot("burning-ship-fire", sky("burning_ship", 2, 1, 0, -1.755F, -0.03F, 0.09F, 0.5F, 200), 30.0F, -60.0F, false, 0.5, -1.0F));
        return list;
    }

    private static boolean hidden;
    private static boolean started;
    private static int step;
    private static int ticks;
    private static int index;
    private static int failed;

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
            if (!mc.gui.hud.isHidden()) {
                mc.gui.hud.toggle();
            }
            say("psychedelicMoon config=" + PsychedelicMoon.ENABLED.getAsBoolean() + " (dev default "
                + PsychedelicRules.defaultOn(false, System.getProperty("fundamentum.psychedelic")) + ", released default "
                + PsychedelicRules.defaultOn(true, null) + ")");
        }
        if (mc.level == null || mc.player == null) {
            if (!started && mc.gui.screen() instanceof TitleScreen) {
                started = true;
                create(mc);
            }
            return;
        }
        if (mc.gui.screen() != null) {
            mc.gui.setScreen(null);
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        ticks++;
        switch (step) {
            case 0 -> {                                     // settle, then raise the moon (the command makes it night)
                if (ticks == 100) {
                    server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "fundamentum moon start fundamentum:psychedelic"));
                } else if (ticks > 100 && PsychedelicMoon.ID.equals(MoonsClient.moon()) && FractalSkiesClient.alpha() >= 1.0F) {
                    say("PASS the moon reached the client and its sky faded in after " + (ticks - 100) + " ticks");
                    next(1);
                } else if (ticks > 600) {
                    say("FAIL no psychedelic sky: moon='" + MoonsClient.moon() + "' alpha=" + FractalSkiesClient.alpha());
                    failed++;
                    next(1);
                }
            }
            case 1 -> {                                     // aim and set the options for the next picture
                if (index >= SHOTS.size()) {
                    FractalSkiesClient.preview(null);
                    FractalSkiesClient.timeOverride = -1.0F;
                    say("DONE failed=" + failed);
                    mc.stop();
                    next(9);
                    return;
                }
                Shot shot = SHOTS.get(index);
                FractalSkiesClient.preview(shot.preview);
                FractalSkiesClient.timeOverride = shot.time;
                SkyConfig.MELT.set(shot.melt);
                SkyConfig.MELT_INTENSITY.set(shot.intensity);
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    p.snapTo(p.getX(), p.getY(), p.getZ(), shot.yaw, shot.pitch);
                });
                mc.player.setYRot(shot.yaw);
                mc.player.setXRot(shot.pitch);
                next(2);
            }
            case 2 -> {                                     // let a preview fade in, then take it
                Shot shot = SHOTS.get(index);
                if (ticks >= 40 && FractalSkiesClient.alpha() >= 1.0F) {
                    String want = shot.preview == null ? "fundamentum:psychedelic" : shot.preview.id().toString();
                    FractalSky now = FractalSkiesClient.shown();
                    if (now == null || !now.id().toString().equals(want)) {
                        say("FAIL " + shot.name + ": showing " + (now == null ? "nothing" : now.id()));
                        failed++;
                    }
                    Screenshot.grab(mc.gameDirectory, "skyshot-" + shot.name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                        msg -> say("saved skyshot-" + shot.name + ".png"));
                    index++;
                    next(1);
                } else if (ticks > 400) {
                    say("FAIL " + shot.name + ": never faded in");
                    failed++;
                    index++;
                    next(1);
                }
            }
            default -> {
            }
        }
    }

    private static void next(int s) {
        step = s;
        ticks = 0;
    }

    private static void say(String line) {
        System.out.println("SKYSHOT " + line);
    }

    private static void create(Minecraft mc) {
        say("creating a flat world");
        mc.options.renderDistance().set(8);
        mc.options.simulationDistance().set(6);
        String name = "skyshot" + System.currentTimeMillis() / 1000;
        LevelSettings settings = new LevelSettings(name, GameType.CREATIVE,
            new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261104L, false, false),
            provider -> provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
            new TitleScreen());
    }
}
