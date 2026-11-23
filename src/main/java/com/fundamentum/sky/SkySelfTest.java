package com.fundamentum.sky;

import com.fundamentum.moon.Moon;
import com.fundamentum.moon.Moons;
import com.fundamentum.moon.NightEvents;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * The in-game Psychedelic Moon and moon command test ({@code gradlew runServer -Pskytest}, which also loads the plain
 * test moon and night event in {@code src/skytest}): the config reads on in a dev run and the released default is off,
 * the moon and its sky are in data, 20000 rolled nights with the switch on, off and on a full moon, then
 * {@code /fundamentum moon start|stop|list} on a plain moon, a night event, the Psychedelic Moon switched off (refused,
 * day kept) and on (made night, up). Each command is checked a few ticks on, once the level has caught up. Then it
 * stops the server. Log lines start with SKYTEST.
 */
public final class SkySelfTest {
    private SkySelfTest() { }

    private static final Logger LOG = LogUtils.getLogger();
    private static final String TEST_MOON = "fundamentum:skytest_moon";
    private static final String TEST_EVENT = "fundamentum:skytest_event";
    private static final List<String> RESULTS = new ArrayList<>();
    /** One command and what must hold five ticks after it. */
    private record Step(String name, String command, int expect, BooleanSupplier after) {}

    /** A step whose command result doesn't matter ("time set" returns the time). */
    private static final int ANY = Integer.MIN_VALUE;

    private static List<Step> steps;
    private static int index = -1;
    private static int ranAt;
    private static int result;

    public static void init() {
        if (!Boolean.getBoolean("fundamentum.skytest")) return;
        NeoForge.EVENT_BUS.addListener(SkySelfTest::tick);
    }

    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int now = server.getTickCount();
        if (index == -1) {
            if (now < 40) return;
            index = 0;
            if (!checks(server)) {
                finish(server);
                return;
            }
            run(server, now);
            return;
        }
        if (steps == null || index >= steps.size() || now < ranAt + 5) return;
        Step step = steps.get(index);
        boolean after = step.after.getAsBoolean();
        report(step.name, (step.expect == ANY || result == step.expect) && after,
            "command " + result + (step.expect == ANY ? "" : " (want " + step.expect + ")") + ", after " + after);
        index++;
        if (index < steps.size()) {
            run(server, now);
        } else {
            finish(server);
        }
    }

    /** The checks that need no waiting; false when the data the commands need is missing. */
    private static boolean checks(MinecraftServer server) {
        ServerLevel level = server.overworld();
        report("config reads on in a dev run", PsychedelicMoon.ENABLED.getAsBoolean(), "");
        report("the released default is off", !PsychedelicRules.defaultOn(true, null), "");
        Moon moon = Moons.get(PsychedelicMoon.ID).orElse(null);
        report("the moon is in data", moon != null, "");
        FractalSky sky = FractalSkies.all().stream().filter(s -> s.matches(PsychedelicMoon.ID, "", "minecraft:overworld")).findFirst().orElse(null);
        report("its fractal sky is in data", sky != null && sky.melt() > 0, sky == null ? "none" : sky.id() + " melt " + sky.melt());
        boolean testData = Moons.get(TEST_MOON).isPresent() && NightEvents.get(TEST_EVENT).isPresent();
        report("the test moon and night event are loaded", testData, "");
        if (moon == null || !testData) {
            return false;
        }
        int on = rolls(level, moon, true, 2), off = rolls(level, moon, false, 2), full = rolls(level, moon, true, 0);
        report("rolled on: about 3% of 20000 nights", on > 480 && on < 720, on + " nights");
        report("rolled off: never", off == 0, off + " nights");
        report("rolled on a full moon: never", full == 0, full + " nights");
        int moons = Moons.all().size(), events = NightEvents.all().size();
        steps = List.of(
            new Step("(stop whatever the kept dev world still has up)", "fundamentum moon stop", 1,
                () -> Moons.current(level).isEmpty() && NightEvents.current(level).isEmpty()),
            new Step("list counts every moon and night event", "fundamentum moon list", moons + events, () -> true),
            new Step("start an unknown id is refused", "fundamentum moon start fundamentum:nothing_here", 0, () -> Moons.current(level).isEmpty()),
            new Step("(set it to day)", "time set noon", ANY, () -> !Moons.isNight(level)),
            new Step("start a plain moon by day makes it night and raises it", "fundamentum moon start " + TEST_MOON, 1,
                () -> Moons.isNight(level) && Moons.isUp(level, TEST_MOON)),
            new Step("start a night event", "fundamentum moon start " + TEST_EVENT, 1, () -> NightEvents.isUp(level, TEST_EVENT)),
            new Step("stop ends the moon and the event", "fundamentum moon stop", 1,
                () -> Moons.current(level).isEmpty() && NightEvents.current(level).isEmpty()),
            new Step("(set it to day and switch the Psychedelic Moon off)", "time set noon", ANY, () -> {
                PsychedelicMoon.ENABLED.set(false);
                return !Moons.isNight(level);
            }),
            new Step("start the Psychedelic Moon switched off: refused, still day", "fundamentum moon start " + PsychedelicMoon.ID, 0,
                () -> !Moons.isUp(level, PsychedelicMoon.ID) && !Moons.isNight(level)),
            new Step("raise the Psychedelic Moon switched off: refused too", "fundamentum moon raise " + PsychedelicMoon.ID, 0,
                () -> !Moons.isUp(level, PsychedelicMoon.ID)),
            new Step("(switch it on)", "fundamentum moon list", moons + events, () -> {
                PsychedelicMoon.ENABLED.set(true);
                return true;
            }),
            new Step("start the Psychedelic Moon switched on: night, up", "fundamentum moon start " + PsychedelicMoon.ID, 1,
                () -> Moons.isNight(level) && Moons.isUp(level, PsychedelicMoon.ID)),
            new Step("query still works", "fundamentum moon query", 1, () -> Moons.isUp(level, PsychedelicMoon.ID)),
            new Step("stop ends it", "fundamentum moon stop", 1, () -> !Moons.isUp(level, PsychedelicMoon.ID)));
        return true;
    }

    private static void run(MinecraftServer server, int now) {
        Step step = steps.get(index);
        try {
            result = server.getCommands().getDispatcher().execute(step.command, server.createCommandSourceStack());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            result = -1;
            LOG.info("SKYTEST command '{}' failed: {}", step.command, e.getMessage());
        }
        ranAt = now;
    }

    private static void finish(MinecraftServer server) {
        long passed = RESULTS.stream().filter(r -> r.startsWith("PASS")).count();
        LOG.info("SKYTEST DONE {}/{}", passed, RESULTS.size());
        index = Integer.MAX_VALUE;
        steps = null;
        server.halt(false);
    }

    /** Tonight's chance through Moons with the switch set, rolled the way Moons rolls a night. */
    private static int rolls(ServerLevel level, Moon moon, boolean enabled, int phase) {
        PsychedelicMoon.ENABLED.set(enabled);
        Random random = new Random(20261104L);
        int n = 0;
        for (int i = 0; i < 20000; i++) {
            if (Moon.roll(List.of(moon), Moon.OVERWORLD, f -> true, phase, m -> Moons.chanceTonight(level, m), random::nextDouble) != null) {
                n++;
            }
        }
        return n;
    }

    private static void report(String name, boolean ok, String detail) {
        String line = (ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : " (" + detail + ")");
        RESULTS.add(line);
        LOG.info("SKYTEST {}", line);
    }
}
