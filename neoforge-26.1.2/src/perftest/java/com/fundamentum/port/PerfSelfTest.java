package com.fundamentum.port;

import com.fundamentum.multiblock.Multiblocks;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server tick cost test, only in {@code gradlew runServer -Pperftest} (world under run-perftest). Wraps every
 * Fundamentum server tick hook in a timer, then runs the server at full speed through three phases: 1 player with 10
 * formed 5x5x5 multiblocks, 20 players with 100, and 100 players with 1000, each with 200 unlock definitions loaded.
 * Every phase runs idle, then again with one inventory change per player per second (when that hook exists). Prints
 * median, mean and worst microseconds per server tick for each system, writes perftest-result.txt and stops the
 * server. Log lines start with PERFTEST.
 */
public final class PerfSelfTest {
    private PerfSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final int PHASE_TICKS = Integer.getInteger("fundamentum.perftest.ticks", 1500);
    private static final int[][] PHASES = {{1, 10}, {20, 100}, {100, 1000}};
    private static final List<String> LINES = new ArrayList<>();
    private static final Map<String, long[]> SAMPLES = new LinkedHashMap<>();
    private static final Map<String, Integer> COUNT = new LinkedHashMap<>();
    private static final List<ServerPlayer> PLAYERS = new ArrayList<>();
    private static int ticks;
    private static int phase = -1;
    private static boolean events;
    private static int phaseTick;
    private static int built;
    private static boolean wrapped;
    private static boolean failed;
    private static Method inventoryHook;

    public static void init() {
        if (System.getProperty("fundamentum.perftest") == null) return;
        FLife.serverTick(PerfSelfTest::tick);
        try {
            inventoryHook = FLife.class.getMethod("fireInventoryChanged", ServerPlayer.class);
        } catch (NoSuchMethodException absent) {
            inventoryHook = null;
        }
    }

    private static String nameOf(Consumer<MinecraftServer> hook) {
        String n = hook.getClass().getName();
        int cut = n.indexOf("$$");
        if (cut > 0) n = n.substring(0, cut);
        return n.substring(n.lastIndexOf('.') + 1);
    }

    /** Puts a timer around every other tick hook, keyed by the class that registered it. */
    private static void wrap() {
        wrapped = true;
        for (int i = 0; i < FLife.TICK.size(); i++) {
            Consumer<MinecraftServer> hook = FLife.TICK.get(i);
            String name = nameOf(hook);
            if (name.equals("PerfSelfTest") || name.endsWith("SelfTest")) continue;
            if (COUNT.containsKey(name)) name = name + "#" + i;
            String key = name;
            COUNT.put(key, 0);
            SAMPLES.put(key, new long[PHASE_TICKS]);
            FLife.TICK.set(i, s -> {
                long t0 = System.nanoTime();
                hook.accept(s);
                long dt = System.nanoTime() - t0;
                if (phase >= 0 && phaseTick >= 0 && phaseTick < PHASE_TICKS) SAMPLES.get(key)[phaseTick] += dt;
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ServerPlayer> playerList(MinecraftServer server) throws ReflectiveOperationException {
        Field f = PlayerList.class.getDeclaredField("players");
        f.setAccessible(true);
        return (List<ServerPlayer>) f.get(server.getPlayerList());
    }

    private static void setPlayers(MinecraftServer server, int n) throws ReflectiveOperationException {
        List<ServerPlayer> list = playerList(server);
        ServerLevel level = server.overworld();
        ServerPlayer conn = FVanilla.fakePlayer(level, "fundamentum_perf_connection");
        while (PLAYERS.size() < n) {
            int i = PLAYERS.size();
            ServerPlayer p = FVanilla.testPlayer(level, "perf" + i);
            p.connection = conn.connection;
            try {
                p.connection.getClass().getMethod("markClientLoaded").invoke(p.connection);
            } catch (ReflectiveOperationException ignored) { }
            p.setPos(-24 + (i % 10) * 5 + 0.5, 100, -24 + (i / 10) * 5 + 0.5);
            PLAYERS.add(p);
            list.add(p);
        }
    }

    private static void removePlayers(MinecraftServer server) {
        try {
            playerList(server).removeAll(PLAYERS);
        } catch (ReflectiveOperationException e) {
            LOG.error("PERFTEST could not remove the test players", e);
        }
        PLAYERS.clear();
    }

    /** Builds and forms 5x5x5 structures until {@code n} exist, 6 blocks apart in a grid far from spawn. */
    private static void buildMultiblocks(ServerLevel level, int n) {
        FId cube = FId.of("perftest", "cube");
        while (built < n) {
            int i = built++;
            BlockPos c = new BlockPos(2000 + (i % 10) * 6, 100 + ((i / 100) % 10) * 6, 2000 + ((i / 10) % 10) * 6);
            Multiblocks.build(level, cube, c, Direction.NORTH, false);
            if (Multiblocks.check(level, c) == null) {
                failed = true;
                LOG.error("PERFTEST multiblock {} did not form at {}", i, c);
            }
        }
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        try {
            step(server);
        } catch (Throwable t) {
            LOG.error("PERFTEST threw", t);
            failed = true;
            finish(server);
        }
    }

    private static void step(MinecraftServer server) throws Exception {
        ServerLevel level = server.overworld();
        if (ticks == 5) {
            for (int x = -2; x <= 1; x++) for (int z = -2; z <= 1; z++) level.setChunkForced(x, z, true);
            for (int x = 125; x <= 129; x++) for (int z = 125; z <= 129; z++) level.setChunkForced(x, z, true);
            return;
        }
        if (ticks < 60) return;
        if (!wrapped) {
            wrap();
            server.tickRateManager().requestGameToSprint(PHASES.length * PHASE_TICKS * 3);
            startPhase(server, 0, false);
            return;
        }
        phaseTick++;
        if (events && inventoryHook != null) {
            for (int i = 0; i < PLAYERS.size(); i++) {
                if ((phaseTick + i) % 20 == 0) inventoryHook.invoke(null, PLAYERS.get(i));
            }
        }
        if (phaseTick < PHASE_TICKS) return;
        report();
        if (!events && inventoryHook != null) {
            startPhase(server, phase, true);
        } else if (phase + 1 < PHASES.length) {
            startPhase(server, phase + 1, false);
        } else {
            finish(server);
        }
    }

    private static void startPhase(MinecraftServer server, int which, boolean withEvents) throws ReflectiveOperationException {
        phase = which;
        events = withEvents;
        phaseTick = -1;
        setPlayers(server, PHASES[which][0]);
        buildMultiblocks(server.overworld(), PHASES[which][1]);
        for (long[] s : SAMPLES.values()) Arrays.fill(s, 0);
    }

    private static void report() {
        String label = PHASES[phase][0] + " players, " + PHASES[phase][1] + " multiblocks" + (events ? ", inventory events" : ", idle");
        LINES.add("== " + label);
        LOG.info("PERFTEST == {}", label);
        long[] total = new long[PHASE_TICKS];
        SAMPLES.forEach((name, s) -> {
            for (int i = 0; i < s.length; i++) total[i] += s[i];
            line(name, s);
        });
        line("ALL", total);
    }

    private static void line(String name, long[] samples) {
        long[] s = samples.clone();
        Arrays.sort(s);
        double sum = 0;
        for (long v : s) sum += v;
        String text = String.format(java.util.Locale.ROOT, "%-22s median %8.2f  mean %8.2f  worst %9.2f us/tick",
            name, s[s.length / 2] / 1000.0, sum / s.length / 1000.0, s[s.length - 1] / 1000.0);
        LINES.add(text);
        LOG.info("PERFTEST {}", text);
    }

    private static void finish(MinecraftServer server) {
        removePlayers(server);
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("PERFTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("perftest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("PERFTEST could not write the result file", e);
        }
        server.halt(false);
    }
}
