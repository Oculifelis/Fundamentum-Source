package com.fundamentum.event;

import com.fundamentum.config.ConfigEvent;
import com.fundamentum.config.ConfigValue;
import com.fundamentum.config.FConfig;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The event test, only in {@code gradlew runServer -Peventtest} (world under run-eventtest). The same file on every
 * version. Checks the registered types, listening by class and by id, cancelling, the loader bus, reading fields and
 * {@code where} conditions, the config event, first join, and the {@code fundamentum:event} advancement trigger with
 * the test datapack's advancements. Writes eventtest-result.txt and stops the server. Log lines start with EVENTTEST.
 */
public final class EventSelfTest {
    private EventSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static boolean failed;

    private static final List<String> HEARD = new ArrayList<>();
    private static boolean cancelSpells;
    private static boolean cancelRitualsOnBus;
    private static final List<FId> BUS = new ArrayList<>();
    private static final List<ConfigEvent.Changed> CONFIG = new ArrayList<>();
    private static ConfigValue<Integer> level;
    private static int ticks;

    public static void init() {
        if (System.getProperty("fundamentum.eventtest") == null) return;
        try {
            Path cfg = Path.of("config", "fundamentum", "eventtest");
            if (Files.isDirectory(cfg)) {
                try (var s = Files.list(cfg)) {
                    for (Path p : s.toList()) Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        level = FConfig.create(FId.of("eventtest", "main")).integer("level", 1, 0, 10);
        FEvents.listen(Events.SpellCast.class, e -> HEARD.add("spell " + e.spell()));
        FEvents.listen(Events.SpellCast.class, (e, call) -> {
            if (cancelSpells) call.cancel();
        });
        FEvents.listen(Events.SpellCast.class, e -> HEARD.add("after " + e.spell()));
        FEvents.listen(FId.of("fundamentum", "machine_processed"), (e, call) -> {
            HEARD.add("machine " + ((Events.MachineProcessed) e).machine());
            call.cancel();
        });
        FEvents.listen(Events.FirstJoin.class, e -> HEARD.add("first join " + e.player().getName().getString()));
        FundamentumEvent.listen(e -> {
            BUS.add(e.id());
            if (cancelRitualsOnBus && e instanceof FundamentumEvent.Cancellable c && e.as(Events.RitualStarted.class) != null) c.setCanceled(true);
        });
        ConfigEvent.listen(CONFIG::add);
        FLife.serverTick(EventSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("EVENTTEST {}", line);
        else LOG.error("EVENTTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        if (++ticks != 40) return;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("EVENTTEST threw", t);
            check(false, "threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("EVENTTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("eventtest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("EVENTTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static FId id(String s) {
        return FId.of("eventtest", s);
    }

    private static void run(MinecraftServer server) {
        // types
        Set<FId> ids = new HashSet<>();
        boolean allOurs = true;
        for (FEvents.Type t : FEvents.types()) {
            ids.add(t.id());
            allOurs &= t.id().namespace().equals("fundamentum") && t.type().isRecord();
        }
        check(ids.size() >= 30 && allOurs, "at least 30 event types, each a record with a fundamentum id: " + ids.size());
        check(FEvents.idOf(ConfigEvent.Changed.class).equals(FId.of("fundamentum", "config_changed"))
                && FEvents.idOf(Events.FirstJoin.class).equals(FId.of("fundamentum", "first_join"))
                && FEvents.type(FId.of("fundamentum", "dimension_entered")) != null,
            "stable ids for the events Fundamentum fires");
        check(FEvents.type(Events.SpellCast.class).cancellable() && !FEvents.type(Events.QuestCompleted.class).cancellable(), "cancellable where it makes sense");
        boolean threw = false;
        try {
            FEvents.register(FId.of("fundamentum", "spell_cast"), Events.ManaSpent.class, false);
        } catch (IllegalStateException e) {
            threw = true;
        }
        check(threw, "an id cannot be taken twice");

        ServerPlayer fake = FVanilla.fakePlayer(server.overworld(), "eventtester");

        // listening and cancelling
        HEARD.clear();
        BUS.clear();
        boolean cancelled = FEvents.post(new Events.SpellCast(fake, id("fireball"), 5));
        check(!cancelled && HEARD.equals(List.of("spell eventtest:fireball", "after eventtest:fireball")), "listeners hear the event in order: " + HEARD);
        check(BUS.equals(List.of(FId.of("fundamentum", "spell_cast"))), "the event reaches the loader bus: " + BUS);
        HEARD.clear();
        BUS.clear();
        cancelSpells = true;
        cancelled = FEvents.post(new Events.SpellCast(fake, id("fireball"), 5));
        cancelSpells = false;
        check(cancelled && HEARD.equals(List.of("spell eventtest:fireball")) && BUS.isEmpty(),
            "a cancelled event stops later listeners and the loader bus: " + HEARD + " " + BUS);
        HEARD.clear();
        cancelled = FEvents.post(new Events.MachineProcessed(server.overworld(), BlockPos.ZERO, id("press"), null));
        check(!cancelled && HEARD.equals(List.of("machine eventtest:press")), "listen by id; cancel is ignored for an event that cannot be cancelled");
        BUS.clear();
        cancelRitualsOnBus = true;
        cancelled = FEvents.post(new Events.RitualStarted(fake, id("rain"), BlockPos.ZERO));
        cancelRitualsOnBus = false;
        check(cancelled && BUS.equals(List.of(FId.of("fundamentum", "ritual_started"))), "a loader bus listener can cancel");

        // fields and conditions
        Events.QuestCompleted quest = new Events.QuestCompleted(fake, id("first"));
        JsonObject d = FEvents.describe(quest);
        check(d.get("event").getAsString().equals("fundamentum:quest_completed") && d.get("player").getAsString().equals("minecraft:player")
                && d.get("quest").getAsString().equals("eventtest:first"),
            "describe: " + d);
        check(FEvents.player(quest) == fake, "the event's player is found");
        Events.RepChanged rep = new Events.RepChanged(fake, id("guild"), 10, 25);
        check(FEvents.matches(rep, null) && FEvents.matches(rep, obj("{}")), "no conditions match everything");
        check(FEvents.matches(rep, obj("{\"faction\": \"eventtest:guild\", \"to\": 25}")), "exact values match");
        check(!FEvents.matches(rep, obj("{\"faction\": \"eventtest:other\"}")), "a different value does not match");
        check(FEvents.matches(rep, obj("{\"to\": {\"min\": 20}, \"from\": {\"max\": 10}}")) && !FEvents.matches(rep, obj("{\"to\": {\"min\": 30}}")), "ranges");
        check(FEvents.matches(rep, obj("{\"faction\": [\"eventtest:a\", \"eventtest:guild\"]}")), "any of several values");
        check(!FEvents.matches(rep, obj("{\"colour\": 1}")), "a field the event does not have never matches");
        check(FEvents.matches(rep, obj("{\"player\": \"player\"}")), "a vanilla id may leave out minecraft:");
        check(FEvents.matches(new Events.BossKilled(fake, id("golem"), null), obj("{\"killer\": null}")), "an empty field matches null");

        // config events go through FEvents
        CONFIG.clear();
        List<ConfigEvent.Changed> viaEvents = new ArrayList<>();
        FEvents.listen(ConfigEvent.Changed.class, e -> viaEvents.add(e));
        level.set(4);
        level.reset();
        check(CONFIG.size() == 2 && viaEvents.size() == 2 && CONFIG.get(0).keys().equals(List.of("level")), "config changes are the event fundamentum:config_changed: " + CONFIG);

        // first join
        HEARD.clear();
        boolean first = EventHooks.loggedIn(fake);
        boolean again = EventHooks.loggedIn(fake);
        check(first && !again && HEARD.equals(List.of("first join eventtester")) && EventHooks.JOINED.get(fake), "first join fires once per player");

        // advancement trigger (a fake player never earns advancements, so this part uses a plain test player)
        ServerPlayer player = FVanilla.testPlayer(server.overworld(), "eventplayer");
        quest = new Events.QuestCompleted(player, id("first"));
        EventTrigger.Instance inst = EventTrigger.read(JsonParser.parseString("{\"event\": \"fundamentum:quest_completed\", \"where\": {\"quest\": \"eventtest:first\"}}"));
        check(inst.test(FEvents.type(Events.QuestCompleted.class), quest) && !inst.test(FEvents.type(Events.QuestAccepted.class), new Events.QuestAccepted(player, id("first"))),
            "trigger conditions read from JSON");
        check(EventTrigger.known(server, id("quest")) && EventTrigger.known(server, id("ritual")), "the test datapack's advancements are loaded");
        check(!EventTrigger.done(player, id("quest")) && !EventTrigger.done(player, id("ritual")), "test advancements start not done");
        FEvents.post(new Events.QuestCompleted(player, id("other")));
        check(!EventTrigger.done(player, id("quest")), "the trigger ignores an event its where does not match");
        FEvents.post(quest);
        check(EventTrigger.done(player, id("quest")), "the fundamentum:event trigger completes an advancement");
        cancelRitualsOnBus = true;
        FEvents.post(new Events.RitualStarted(player, id("rain"), BlockPos.ZERO));
        cancelRitualsOnBus = false;
        check(!EventTrigger.done(player, id("ritual")), "a cancelled event does not count for advancements");
        FEvents.post(new Events.RitualStarted(player, id("rain"), BlockPos.ZERO));
        check(EventTrigger.done(player, id("ritual")), "the same event, not cancelled, does");

        // player conditions, through a real advancement and through read()
        player.setRemainingFireTicks(0);
        FEvents.post(new Events.QuestAccepted(player, id("first")));
        check(EventTrigger.known(server, id("burning")) && !EventTrigger.done(player, id("burning")), "an event does not count when the player conditions fail");
        player.setRemainingFireTicks(200);
        FEvents.post(new Events.QuestAccepted(player, id("first")));
        check(EventTrigger.done(player, id("burning")), "the same event counts once the player conditions hold");
        EventTrigger.Instance burning = EventTrigger.read(JsonParser.parseString("{\"event\": \"fundamentum:quest_accepted\", \"player\": " + OnFire.PLAYER + "}"));
        boolean onFire = burning.testPlayer(player);
        player.setRemainingFireTicks(0);
        check(onFire && !burning.testPlayer(player), "player conditions read from JSON are kept");

        // every event Fundamentum's own systems post is registered, with the right cancel flag
        String[][] own = {
            {"shield_blocked", "false"}, {"effect_applied", "true"}, {"effect_ended", "false"}, {"effect_tick", "true"},
            {"loot_added", "true"}, {"boss_loot_dropped", "false"}, {"music_changed", "false"}, {"moon_rose", "false"},
            {"moon_set", "false"}, {"night_event_started", "false"}, {"night_event_ended", "false"},
            {"meter_threshold", "false"}, {"element_reacted", "false"}, {"boss_spawned", "false"},
            {"boss_phase_changed", "false"}, {"boss_killed", "false"}, {"set_bonus_on", "false"}, {"set_bonus_off", "false"},
            {"ammo_drawn", "true"}, {"thrown_weapon_hit", "true"}, {"wings_take_off", "true"}, {"mana_spent", "true"},
            {"summon_created", "true"}};
        List<String> wrong = new ArrayList<>();
        for (String[] e : own) {
            FEvents.Type t = FEvents.type(FId.of("fundamentum", e[0]));
            if (t == null || t.cancellable() != Boolean.parseBoolean(e[1])) wrong.add(e[0]);
        }
        // the records in Events, plus fundamentum:config_changed, which Events registers for the config system
        long count = FEvents.types().stream().filter(t -> t.type().getEnclosingClass() == Events.class).count()
            + (FEvents.type(FId.of("fundamentum", "config_changed")) != null ? 1 : 0);
        check(wrong.isEmpty() && count == 47, "Events holds its 47 event types " + (wrong.isEmpty() ? "(" + count + ")" : wrong));
    }

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
