package com.fundamentum.unlock;

import com.fundamentum.condition.Conditions;
import com.fundamentum.event.EventTrigger;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The unlock test, only in {@code gradlew runServer -Punlocktest} (world under run-unlocktest). The same file on every
 * version. Checks granting, levels, parents, cancelling, revoking, scoreboard teams and team sharing, the declarations
 * from the test datapack, grant sources, the {@code unlocked} condition, the {@code fundamentum:unlocked} advancement
 * trigger, the command, and the save and sync codecs. Writes unlocktest-result.txt and stops the server. Log lines
 * start with UNLOCKTEST.
 */
public final class UnlockSelfTest {
    private UnlockSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static boolean failed;
    private static int ticks;
    private static int unlockGranted;
    private static final List<String> HEARD = new ArrayList<>();

    public static void init() {
        if (System.getProperty("fundamentum.unlocktest") == null) return;
        FEvents.listen(UnlockEvent.Pre.class, (e, call) -> {
            if (e.unlock().equals(id("blocked"))) call.cancel();
        });
        FEvents.listen(UnlockEvent.Granted.class, e -> HEARD.add("granted " + e.unlock() + " " + e.level() + " " + e.holder()));
        FEvents.listen(UnlockEvent.Revoked.class, e -> HEARD.add("revoked " + e.unlock() + " " + e.level()));
        FEvents.listen(Events.UnlockGranted.class, e -> unlockGranted++);
        FLife.serverTick(UnlockSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("UNLOCKTEST {}", line);
        else LOG.error("UNLOCKTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        if (++ticks != 40) return;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("UNLOCKTEST threw", t);
            check(false, "threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("UNLOCKTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("unlocktest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("UNLOCKTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static FId id(String s) {
        return FId.of("unlocktest", s);
    }

    private static void run(MinecraftServer server) {
        ServerPlayer p1 = FVanilla.testPlayer(server.overworld(), "UnlockTester");
        ServerPlayer p2 = FVanilla.testPlayer(server.overworld(), "UnlockMate");
        // team unlocks are saved with the world; start clean
        Unlocks.revoke(UnlockHolder.team("vanilla/utest"), id("shared"));

        // declarations
        check(Unlocks.defs().size() >= 10, "the test datapack's unlocks are loaded (" + Unlocks.defs().size() + ")");
        UnlockDef hidden = Unlocks.def(id("hidden"));
        check(hidden != null && hidden.hidden() && "unlock.unlocktest.secret".equals(hidden.name())
            && "unlock.unlocktest.secret.desc".equals(hidden.description()) && FId.of("minecraft", "diamond").equals(hidden.icon()),
            "a declaration reads name, description, icon and hidden");
        UnlockDef child = Unlocks.def(id("child"));
        check(child != null && child.parents().equals(List.of(id("root"))) && child.maxLevel() == 1, "a declaration reads parents and the default max level");
        check(Unlocks.def(id("never_declared")) == null, "an undeclared id has no declaration");

        // undeclared ids, levels
        FId free = id("never_declared");
        check(!Unlocks.has(p1, free) && Unlocks.level(p1, free) == 0, "nothing is unlocked at first");
        check(Unlocks.grant(p1, free), "an undeclared id can be granted");
        check(Unlocks.has(p1, free) && Unlocks.level(p1, free) == 1 && Unlocks.time(UnlockHolder.of(p1), free) > 0, "it is held at level 1 with a time");
        check(!Unlocks.grant(p1, free), "granting the same level again does nothing");
        check(Unlocks.grant(p1, free, 3) && Unlocks.level(p1, free) == 3, "a higher level raises it");
        check(Unlocks.grant(p1, id("capped"), 9) && Unlocks.level(p1, id("capped")) == 2, "max_level caps the level");
        check(!Unlocks.has(p2, free), "another player does not have it");
        check(Unlocks.all(p1).containsKey(free) && Unlocks.all(p1).get(free).level() == 3, "all() lists it");

        // parents
        check(!Unlocks.grant(p1, id("child")) && !Unlocks.has(p1, id("child")), "a missing parent blocks the grant");
        check(Unlocks.grant(p1, id("root")), "the parent can be granted");
        check(Unlocks.grant(p1, id("child")) && Unlocks.has(p1, id("child")), "with the parent the child can be granted");

        // cancelling and the events
        int before = unlockGranted;
        check(!Unlocks.grant(p1, id("blocked")) && !Unlocks.has(p1, id("blocked")), "a cancelled Pre event stops the grant");
        check(unlockGranted == before, "a cancelled grant posts no UnlockGranted");
        check(Unlocks.grant(p1, id("other")) && unlockGranted == before + 1, "a grant posts Events.UnlockGranted");
        check(HEARD.contains("granted unlocktest:other 1 player:" + p1.getUUID()), "a grant posts UnlockEvent.Granted with the holder");
        FEvents.listen(Events.UnlockGranted.class, (e, call) -> {
            if (e.unlock().equals(id("vetoed"))) call.cancel();
        });
        check(!Unlocks.grant(p1, id("vetoed")), "cancelling Events.UnlockGranted stops the grant");

        // revoking
        check(Unlocks.revoke(p1, id("other")) && !Unlocks.has(p1, id("other")), "an unlock can be revoked");
        check(HEARD.contains("revoked unlocktest:other 1"), "revoking posts UnlockEvent.Revoked");
        check(!Unlocks.revoke(p1, id("other")), "revoking what is not held does nothing");

        // teams
        Scoreboard sb = server.getScoreboard();
        PlayerTeam team = sb.getPlayerTeam("utest");
        if (team == null) team = sb.addPlayerTeam("utest");
        sb.addPlayerToTeam(p1.getScoreboardName(), team);
        sb.addPlayerToTeam(p2.getScoreboardName(), team);
        UnlockHolder t = Unlocks.team(p1);
        check(t != null && t.isTeam() && "vanilla/utest".equals(t.key()) && t.equals(Unlocks.team(p2)), "a scoreboard team is a holder");
        check(Unlocks.holderFor(p1, id("shared")).equals(t) && Unlocks.holderFor(p1, free).equals(UnlockHolder.of(p1)),
            "team_shared unlocks go to the team, others to the player");
        check(Unlocks.grant(p1, id("shared")) && Unlocks.has(t, id("shared")) && !Unlocks.has(UnlockHolder.of(p1), id("shared")),
            "a team_shared grant is stored on the team");
        check(Unlocks.has(p2, id("shared")) && Unlocks.all(p2).containsKey(id("shared")), "a teammate has the team's unlock");
        check(Unlocks.grant(t, id("team_only")) && Unlocks.has(p1, id("team_only")), "a grant straight to the team is seen by members");
        Unlocks.revoke(t, id("team_only"));

        // the unlocked condition
        check(Conditions.test(JsonParser.parseString("{\"type\":\"unlocked\",\"id\":\"unlocktest:never_declared\",\"min\":2}"), p1),
            "the unlocked condition passes at the level");
        check(!Conditions.test(JsonParser.parseString("{\"type\":\"unlocked\",\"id\":\"unlocktest:never_declared\",\"min\":4}"), p1),
            "the unlocked condition fails below min");
        check(Conditions.test(JsonParser.parseString("{\"type\":\"not\",\"of\":{\"type\":\"unlocked\",\"id\":\"unlocktest:other\"}}"), p1),
            "not inverts a condition");
        check(Conditions.test(JsonParser.parseString("[{\"type\":\"unlocked\",\"id\":\"unlocktest:root\"},{\"type\":\"unlocked\",\"id\":\"unlocktest:shared\"}]"), p2) == false
            && Conditions.test(JsonParser.parseString("{\"type\":\"unlocked\",\"id\":\"unlocktest:shared\"}"), p2),
            "a list needs every entry; the teammate passes the shared one");
        check(!Conditions.test(JsonParser.parseString("{\"type\":\"no_such_type\"}"), p1), "an unknown condition type is false");

        // grant sources and the advancement trigger
        check(EventTrigger.known(server, id("root_adv")) && EventTrigger.done(p1, id("root_adv")),
            "the fundamentum:unlocked trigger completed an advancement on the grant");
        check(!EventTrigger.done(p2, id("root_adv")), "the trigger did not complete it for another player");
        check(!Unlocks.has(p1, id("diamond")) && !Unlocks.has(p1, id("chain")), "item and chained unlocks start missing");
        p1.getInventory().add(new ItemStack(Items.DIAMOND));
        int got = Unlocks.checkSources(p1);
        check(Unlocks.has(p1, id("diamond")), "carrying the item grants its unlock");
        check(Unlocks.has(p1, id("chain")), "an unlock granted by another unlock follows in the same check");
        check(Unlocks.has(p1, id("adv")), "an advancement grant source works");
        check(got >= 3, "checkSources counts what it granted (" + got + ")");
        p1.awardStat(net.minecraft.stats.Stats.JUMP);
        Unlocks.checkSources(p1);
        check(Unlocks.has(p1, id("jumper")), "a vanilla stat grant source works");
        check(!Unlocks.has(p2, id("diamond")), "grant sources are per player");

        // the command
        int r = server.getCommands().getDispatcher().getRoot().getChild("fundamentum") == null ? -1 : 0;
        check(r == 0, "the /fundamentum command is registered");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withEntity(p1).withSuppressedOutput(),
            "fundamentum unlock grant @s unlocktest:cmd 2");
        check(Unlocks.level(p1, id("cmd")) == 2, "/fundamentum unlock grant works");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withEntity(p1).withSuppressedOutput(),
            "fundamentum unlock revoke @s unlocktest:cmd");
        check(!Unlocks.has(p1, id("cmd")), "/fundamentum unlock revoke works");

        // save and sync
        Map<String, Unlocks.Entry> saved = Unlocks.PLAYER.roundTrip(Unlocks.PLAYER.get(p1), server.registryAccess());
        check(saved.equals(Unlocks.PLAYER.get(p1)), "player unlocks survive a save and load");
        Unlocks.Sync sync = new Unlocks.Sync(Map.of("unlocktest:root", 1, "unlocktest:never_declared", 3));
        check(com.fundamentum.port.FNet.roundTrip(sync, server.registryAccess()).equals(sync), "the sync message survives the network codec");

        // leave the world as it was
        Unlocks.revoke(t, id("shared"));
        sb.removePlayerFromTeam(p1.getScoreboardName(), team);
        sb.removePlayerFromTeam(p2.getScoreboardName(), team);
        check(Unlocks.team(p1) == null && !Unlocks.has(p2, id("shared")), "leaving the team drops its unlocks from the player");
    }
}
