package com.fundamentum.script;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FVanilla;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The script test, only in {@code gradlew runServer -Pscripttest} with the KubeJS jars in libs/ (world under
 * run-scripttest). The server script fundamentum_scripttest.js listens to Fundamentum events through
 * {@code FundamentumEvents.event}, calls back through the {@code Fundamentum} binding and cancels; this checks each of
 * those, plus the {@link FScript} surface itself. Writes scripttest-result.txt and stops the server. Log lines start
 * with SCRIPTTEST.
 */
public final class ScriptSelfTest {
    private ScriptSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final BlockPos AT = new BlockPos(8, 200, 8);
    private static boolean failed;
    private static int heard;
    private static int ticks;

    public static void init() {
        if (System.getProperty("fundamentum.scripttest") == null) return;
        FLife.serverTick(ScriptSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("SCRIPTTEST {}", line);
        else LOG.error("SCRIPTTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks == 5) server.overworld().setChunkForced(0, 0, true);
        try {
            if (ticks == 40) run(server);
        } catch (Throwable t) {
            LOG.error("SCRIPTTEST threw", t);
            check(false, "threw " + t);
        }
        if (ticks != 60) return;
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("SCRIPTTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("scripttest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("SCRIPTTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer p = FVanilla.fakePlayer(level, "scripttest");
        p.setPos(AT.getX() + 0.5, AT.getY(), AT.getZ() + 0.5);

        check(ModList.get().isLoaded("kubejs"), "KubeJS is loaded");

        // the facade on its own
        check(FScript.factions().contains("scripttest:guild") && FScript.factions().contains("scripttest:echo"),
            "factions() lists the test factions");
        check(FScript.eventIds().contains("fundamentum:rep_changed") && FScript.eventIds().contains("fundamentum:quest_completed"),
            "eventIds() lists the built-in events (" + FScript.eventIds().size() + ")");
        check(FScript.gods().contains("scripttest:sun") && !FScript.gods().contains("scripttest:guild"), "gods() lists deities only");
        check(FScript.rep(p, "scripttest:guild") == 0 && "stranger".equals(FScript.rank(p, "scripttest:guild")), "rep and rank by text id");

        // a Java listener through FScript.on
        FScript.on("fundamentum:rep_changed", e -> {
            if ("scripttest:guild".equals(String.valueOf(e.get("faction")))) heard++;
        });
        boolean threw = false;
        try {
            FScript.on("scripttest:nothing", e -> { });
        } catch (IllegalArgumentException ex) {
            threw = true;
        }
        check(threw, "on() refuses an unknown event id");

        // the script's targeted handler copies the change onto Script Echo through the binding
        FScript.addRep(p, "scripttest:guild", 60);
        check(FScript.rep(p, "scripttest:guild") == 60 && "friend".equals(FScript.rank(p, "scripttest:guild")), "+60 reaches friend");
        check(FScript.rep(p, "scripttest:echo") == 60, "the script heard the change and called back (echo " + FScript.rep(p, "scripttest:echo") + ")");
        check(heard == 1, "the FScript.on listener heard it too (" + heard + ")");

        // the script cancels a change to 900 or more
        FScript.setRep(p, "scripttest:guild", 950);
        check(FScript.rep(p, "scripttest:guild") == 60, "the script cancelled the change to 950 (" + FScript.rep(p, "scripttest:guild") + ")");
        check(FScript.rep(p, "scripttest:echo") == 60, "a cancelled change is not copied");

        // the untargeted handler hears every event and keeps the player out of Script Closed
        String why = FScript.join(p, "scripttest:closed");
        check(why != null && !FScript.isMember(p, "scripttest:closed"), "the script refused joining Script Closed (" + why + ")");
        check(FScript.join(p, "scripttest:guild") == null && FScript.isMember(p, "scripttest:guild"), "joining Script Guild still works");

        // religion and pools by text id
        check(FScript.follow(p, "scripttest:sun") == null && FScript.follows(p, "scripttest:sun"), "follow a god by text id");
        FScript.addDevotion(p, "scripttest:sun", 25);
        check(FScript.devotion(p, "scripttest:sun") == 25, "devotion by text id");
        check(FScript.questSignal(p, "act", "scripttest:none", 1) == 0, "questSignal with no quest moves nothing");
        check(FScript.stamina(p) >= 0 && FScript.mana(p) >= 0 && !FScript.staggered(p), "pools read by entity");
        check(FEvents.type(Events.RepChanged.class).cancellable(), "rep_changed is cancellable");

        // CraftTweaker, where this tree has it: the .zs script imports mods.fundamentum.Fundamentum and logs a line
        if (ModList.get().isLoaded("crafttweaker")) {
            String log = "";
            try {
                log = new String(Files.readAllBytes(Path.of("logs", "crafttweaker.log")));
            } catch (IOException e) {
                LOG.error("SCRIPTTEST no crafttweaker.log", e);
            }
            check(log.contains("FUNDCT ok, events " + FScript.eventIds().size()), "CraftTweaker ran the script through mods.fundamentum.Fundamentum");
        }
    }
}
