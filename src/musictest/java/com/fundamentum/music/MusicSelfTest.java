package com.fundamentum.music;

import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The music test, only in {@code gradlew runServer -Pmusictest} (world under run-musictest). The same file on every
 * version. Checks that rule files load (a broken one left out), that each condition (biome, time, weather, season,
 * dimension, structure, boss, combat) matches as it should, that priority picks the winner with the boss track at boss
 * priority, the boss plumbing through BossMusic, combat detection, the structure lookup, the network messages, the
 * config and the command. The client player itself is compile-only. Writes musictest-result.txt and stops the server.
 * Log lines start with MUSICTEST.
 */
public final class MusicSelfTest {
    private MusicSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final FId OVERWORLD = FId.of("minecraft", "overworld");
    private static final FId PLAINS = FId.of("minecraft", "plains");
    private static boolean failed;
    private static int ticks;

    public static void init() {
        if (System.getProperty("fundamentum.musictest") == null) return;
        FLife.serverTick(MusicSelfTest::tick);
    }

    /** Finds a real village in the world, stands the player in its centre piece and reads the music state there. */
    private static void villageCheck(ServerLevel level, ServerPlayer p) {
        BlockPos near = level.findNearestMapStructure(net.minecraft.tags.StructureTags.VILLAGE, new BlockPos(0, 64, 0), 100, false);
        check(near != null, "a village is found in the world");
        if (near == null) return;
        BlockPos inside = null;
        var chunk = level.getChunk(near.getX() >> 4, near.getZ() >> 4);
        for (var start : chunk.getAllStarts().values()) {
            if (start.isValid() && !start.getPieces().isEmpty()) {
                inside = start.getPieces().get(0).getBoundingBox().getCenter();
                break;
            }
        }
        check(inside != null, "the village start has pieces");
        if (inside == null) return;
        // load the chunk the player stands in fully, so its structure references are in place
        level.getChunk(inside.getX() >> 4, inside.getZ() >> 4);
        p.setPos(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5);
        MusicState there = MusicDirector.stateFor(p);
        check(there.structures().contains("#minecraft:village"), "the music state reports the village the player stands in " + there.structures() + " at " + inside);
        MusicState cached = MusicDirector.cachedStateFor(p);
        check(cached.structures().contains("#minecraft:village"), "the event-driven state reports the village too " + cached.structures());
        // high above the village, out of every piece
        p.setPos(inside.getX() + 0.5, inside.getY() + 300, inside.getZ() + 0.5);
        check(!MusicDirector.cachedStateFor(p).structures().contains("#minecraft:village"), "leaving the village clears it from the state");
        p.setPos(8.5, 100, 8.5);
        check(sound(MusicDirector.choose(MusicDirector.rules(), ctx(PLAINS, 6000, MusicContext.Weather.CLEAR, null,
            Set.copyOf(there.structures()), false, null, false), null)).equals("musictest:village"), "the village track wins there");
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("MUSICTEST {}", line);
        else LOG.error("MUSICTEST {}", line);
    }

    private static boolean done;

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks == 5) server.overworld().setChunkForced(0, 0, true);
        // a fresh world can take a while before the forced chunk ticks entities (the hunting check needs it)
        if (ticks < 40 || done || (ticks < 600 && !server.overworld().isPositionEntityTicking(new net.minecraft.core.BlockPos(8, 100, 8)))) return;
        done = true;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("MUSICTEST threw", t);
            check(false, "threw " + t);
        }
        try {
            MusicDirector.volumeValue().reset();
            MusicDirector.enabledValue().reset();
            MusicDirector.stopVanillaValue().reset();
            MusicDirector.combatTicksValue().reset();
            MusicDirector.combatRangeValue().reset();
        } catch (Throwable t) {
            check(false, "config reset threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("MUSICTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("musictest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("MUSICTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static int command(MinecraftServer server, String text) {
        try {
            return server.getCommands().getDispatcher().execute(text, server.createCommandSourceStack().withSuppressedOutput());
        } catch (Exception e) {
            return -1;
        }
    }

    private static MusicContext ctx(FId biome, long time, MusicContext.Weather weather, FId season, Set<String> structures,
                                    boolean boss, FId bossType, boolean combat) {
        return new MusicContext(OVERWORLD, biome, tag -> false, structures, time, weather, season, boss, bossType, combat);
    }

    private static MusicContext plain(long time) {
        return ctx(PLAINS, time, MusicContext.Weather.CLEAR, null, Set.of(), false, null, false);
    }

    private static String sound(MusicDirector.Choice c) {
        return c == null ? "none" : c.sound().toString();
    }

    private static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        List<MusicRule> rules = MusicDirector.rules().stream().filter(r -> r.id().namespace().equals("musictest")).toList();

        // loading
        check(rules.size() == 6, "six test rules load (got " + rules.size() + ")");
        check(rules.stream().noneMatch(r -> r.id().path().equals("broken")), "the rule without a sound is left out");
        check(MusicDirector.problems().stream().anyMatch(p -> p.contains("musictest:broken")), "the log names the broken rule");
        MusicRule plains = rules.stream().filter(r -> r.id().path().equals("plains_day")).findFirst().orElse(null);
        check(plains != null && plains.fadeIn() == 60 && plains.fadeOut() == 40 && plains.priority() == 1,
            "fades and priority read, with the default fade out");
        check(MusicDirector.watchedStructures().containsAll(Set.of("#minecraft:village", "minecraft:stronghold")),
            "structures the rules name are watched");

        // conditions and priority
        check(sound(MusicDirector.choose(rules, plain(6000), null)).equals("musictest:plains"), "plains by day picks the plains track");
        check(sound(MusicDirector.choose(rules, plain(15000), null)).equals("musictest:night"), "night beats plains at night");
        check(sound(MusicDirector.choose(rules, ctx(FId.of("minecraft", "desert"), 6000, MusicContext.Weather.CLEAR, null,
            Set.of(), false, null, false), null)).equals("none"), "a desert by day matches nothing");
        check(sound(MusicDirector.choose(rules, ctx(PLAINS, 15000, MusicContext.Weather.THUNDER, null, Set.of(), false, null, false),
            null)).equals("none"), "the night rule skips thunder");
        check(sound(MusicDirector.choose(rules, ctx(PLAINS, 6000, MusicContext.Weather.CLEAR, null, Set.of("#minecraft:village"),
            false, null, false), null)).equals("musictest:village"), "a village tag picks the village track");
        check(sound(MusicDirector.choose(rules, ctx(PLAINS, 6000, MusicContext.Weather.CLEAR, FId.of("fundamentum", "winter"),
            Set.of(), false, null, false), null)).equals("musictest:winter"), "winter picks the winter track");
        check(sound(MusicDirector.choose(rules, ctx(PLAINS, 6000, MusicContext.Weather.CLEAR, FId.of("fundamentum", "summer"),
            Set.of(), false, null, false), null)).equals("musictest:plains"), "summer leaves the winter track out");
        check(sound(MusicDirector.choose(rules, ctx(PLAINS, 6000, MusicContext.Weather.CLEAR, null, Set.of(), false, null, true),
            null)).equals("musictest:fight"), "combat picks the fight track");

        MusicState zombieBoss = new MusicState("musictest:boss", "minecraft:zombie", 30, true, List.of());
        MusicState otherBoss = new MusicState("musictest:boss", "minecraft:skeleton", 30, true, List.of());
        MusicDirector.Choice zb = MusicDirector.bossChoice(zombieBoss);
        check(zb != null && zb.priority() == MusicDirector.BOSS_PRIORITY && zb.boss(), "a boss track plays at boss priority");
        check(sound(MusicDirector.choose(rules, ctx(PLAINS, 6000, MusicContext.Weather.CLEAR, null, Set.of(), true,
            FId.of("minecraft", "zombie"), true), zb)).equals("musictest:zombie_king"), "a rule above boss priority wins for its boss");
        check(sound(MusicDirector.choose(rules, ctx(PLAINS, 6000, MusicContext.Weather.CLEAR, null, Set.of(), true,
            FId.of("minecraft", "skeleton"), true), MusicDirector.bossChoice(otherBoss))).equals("musictest:boss"),
            "another boss keeps its own track, and the fight rule stays off during a boss");
        check(MusicDirector.bossChoice(MusicState.NONE) == null, "no boss, no boss track");

        // parsing details
        MusicRule dawn = MusicRule.parse(FId.of("musictest", "dawn"), JsonParser.parseString("{\"sound\":\"a:b\",\"time\":\"dawn\"}").getAsJsonObject());
        check(dawn.matches(plain(23500)) && dawn.matches(plain(500)) && !dawn.matches(plain(6000)), "dawn wraps past midnight");
        MusicRule range = MusicRule.parse(FId.of("musictest", "range"), JsonParser.parseString("{\"sound\":\"a:b\",\"time\":{\"from\":100,\"to\":200}}").getAsJsonObject());
        check(range.matches(plain(24150)) && !range.matches(plain(250)), "a time range works on the day clock");
        boolean threw = false;
        try {
            MusicRule.parse(FId.of("musictest", "bad"), JsonParser.parseString("{\"sound\":\"a:b\",\"weather\":\"fog\"}").getAsJsonObject());
        } catch (IllegalArgumentException e) {
            threw = e.getMessage().contains("fog");
        }
        check(threw, "an unknown weather is refused with a reason");
        MusicRule tagged = MusicRule.parse(FId.of("musictest", "tag"), JsonParser.parseString("{\"sound\":\"a:b\",\"biome\":\"#minecraft:is_forest\"}").getAsJsonObject());
        check(tagged.matches(new MusicContext(OVERWORLD, PLAINS, t -> t.equals(FId.of("minecraft", "is_forest")), Set.of(), 0,
            MusicContext.Weather.CLEAR, null, false, null, false)) && !tagged.matches(plain(0)), "biome tags match through the tag test");
        MusicRule dim = MusicRule.parse(FId.of("musictest", "dim"), JsonParser.parseString("{\"sound\":\"a:b\",\"dimension\":\"minecraft:the_nether\"}").getAsJsonObject());
        check(!dim.matches(plain(0)), "a nether rule stays off in the overworld");

        // boss plumbing
        ServerPlayer p = FVanilla.testPlayer(level, "musictest");
        p.setPos(8.5, 100, 8.5);
        var bossSound = FId.of("musictest", "boss").vanilla();
        Mob zombie = (Mob) FVanilla.create(FId.of("minecraft", "zombie"), level);
        BossMusic.play(p, bossSound, 25, zombie);
        check(FId.of("musictest", "boss").equals(MusicDirector.bossSound(p)) && bossSound.equals(BossMusic.current(p)),
            "BossMusic hands the track to the music system");
        MusicState st = MusicDirector.stateFor(p);
        check(st.bossFight() && st.bossType().equals("minecraft:zombie") && st.bossFade() == 25, "the state carries the boss, its type and fade");
        check(sound(MusicDirector.choiceFor(p)).equals("musictest:zombie_king"), "the server's choice for the player follows the rules");
        BossMusic.stop(p, 15);
        check(MusicDirector.bossSound(p) == null && BossMusic.current(p) == null && MusicDirector.stateFor(p).bossFade() == 15,
            "stopping clears the boss track with its fade");

        // combat
        check(!MusicDirector.inCombat(p), "a quiet player is out of combat");
        p.setLastHurtByMob(zombie);
        check(MusicDirector.inCombat(p), "being hurt by a mob is combat");
        MusicDirector.combatTicksValue().set(20);
        p.tickCount += 40;
        check(!MusicDirector.inCombat(p), "combat ends after combat_ticks");
        MusicDirector.combatTicksValue().reset();
        p.setLastHurtByMob(null);
        Mob hunter = (Mob) FVanilla.create(FId.of("minecraft", "zombie"), level);
        hunter.setPos(10.5, 100, 8.5);
        level.addFreshEntity(hunter);
        hunter.setTarget(p);
        check(MusicDirector.inCombat(p), "an enemy hunting the player is combat");
        MusicDirector.combatRangeValue().set(0);
        check(!MusicDirector.inCombat(p), "combat_range 0 ignores hunters");
        MusicDirector.combatRangeValue().reset();
        hunter.discard();

        // structures
        Set<String> found = MusicDirector.structuresAt(level, new BlockPos(8, 64, 8),
            Set.of("#minecraft:village", "minecraft:stronghold", "musictest:nothing", "#musictest:nothing"));
        check(Set.of("#minecraft:village", "minecraft:stronghold").containsAll(found), "the structure lookup copes with unknown ids and tags");
        villageCheck(level, p);

        // network
        MusicState sent = new MusicState("a:b", "c:d", 12, true, List.of("#minecraft:village"));
        check(FNet.roundTrip(sent, server.registryAccess()).equals(sent), "the music state survives the network");
        MusicDirector.Rules synced = FNet.roundTrip(new MusicDirector.Rules(MusicDirector.ruleFiles()), server.registryAccess());
        List<MusicRule> clientSide = MusicDirector.parseAll(synced.files(), new ArrayList<>());
        check(clientSide.equals(MusicDirector.rules()), "clients rebuild the same rules from the sync");
        check(!MusicDirector.ruleFiles().containsKey("musictest:broken"), "the broken rule is not sent");

        // config
        check(MusicDirector.volume() == 1.0 && MusicDirector.enabled() && MusicDirector.stopVanilla(), "config defaults");
        MusicDirector.volumeValue().set(0.5);
        check(MusicDirector.volume() == 0.5, "the volume setting reads back");
        MusicDirector.volumeValue().reset();

        // command
        check(command(server, "fundamentum music") >= 0, "the music command runs");
    }
}
