package com.fundamentum.quest;

import com.fundamentum.condition.Conditions;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.faction.Factions;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FVanilla;
import com.fundamentum.unlock.Unlocks;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The quest test, only in {@code gradlew runServer -Pquesttest} (world under run-questtest). Loads five test quests, a
 * and a faction, then checks offers from a mob, accepting, kill, collect, break, craft, visit and act objectives,
 * hand-in, rewards, faction acts, rank gates, requirements, cooldowns, time limits, abandoning, conditions,
 * events and saving. Writes questtest-result.txt and stops the server. Log lines start with QUESTTEST.
 */
public final class QuestSelfTest {
    private QuestSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final BlockPos AT = new BlockPos(8, 200, 8);
    private static final FId HUNT = FId.of("questtest", "hunt");
    private static final FId GATHER = FId.of("questtest", "gather");
    private static final FId ELITE = FId.of("questtest", "elite");
    private static final FId TIMED = FId.of("questtest", "timed");
    private static final FId AFTER = FId.of("questtest", "after");
    private static final FId GUILD = FId.of("questtest", "guild");
    private static boolean failed;
    private static boolean refuse;
    private static int completedEvents;
    private static int failedEvents;
    private static int ticks;
    private static int lateRep;
    private static ServerPlayer player;

    public static void init() {
        if (System.getProperty("fundamentum.questtest") == null) return;
        FLife.serverTick(QuestSelfTest::tick);
        FEvents.listen(Events.QuestCompleted.class, e -> completedEvents++);
        FEvents.listen(Events.QuestFailed.class, e -> failedEvents++);
        FEvents.listen(Events.QuestAccepted.class, (e, call) -> {
            if (refuse) call.cancel();
        });
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("QUESTTEST {}", line);
        else LOG.error("QUESTTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks == 5) server.overworld().setChunkForced(0, 0, true);
        try {
            if (ticks == 40) run(server);
            if (ticks == 90 && player != null) later();
        } catch (Throwable t) {
            LOG.error("QUESTTEST threw", t);
            check(false, "threw " + t);
        }
        if (ticks != 100) return;
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("QUESTTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("questtest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("QUESTTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static int count(ServerPlayer p, Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    private static <T extends Mob> T spawn(ServerLevel level, EntityType<T> type, int dx) {
        T m = type.create(level, EntitySpawnReason.COMMAND);
        m.setPos(AT.getX() + dx + 0.5, AT.getY(), AT.getZ() + 3.5);
        level.addFreshEntity(m);
        return m;
    }

    /** Kills a fresh mob as the player, then removes it so the body does not keep ticking (burning in daylight). */
    private static void kill(ServerLevel level, ServerPlayer p, EntityType<? extends Mob> type, int dx) {
        Mob m = spawn(level, type, dx);
        m.die(p.damageSources().playerAttack(p));
        m.discard();
    }

    private static boolean cond(String json, ServerPlayer p) {
        return Conditions.test(JsonParser.parseString(json), p);
    }

    private static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                level.setBlockAndUpdate(AT.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 4; y++) level.setBlockAndUpdate(AT.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }
        ServerPlayer p = FVanilla.fakePlayer(level, "questtest");
        player = p;
        p.setPos(AT.getX() + 0.5, AT.getY(), AT.getZ() + 0.5);

        // data
        check(Quests.all().size() == 5, "five test quests load (" + Quests.all().keySet() + ")");
        QuestDef hunt = Quests.get(HUNT);
        check(hunt != null && hunt.objectives().size() == 1 && GUILD.equals(hunt.faction()), "a quest parses");
        check(Quests.get(TIMED) != null && Quests.get(TIMED).timeLimit() == 20, "a time limit parses");
        check(Quests.PLAYER.saved() && Quests.PLAYER.copiesOnDeath(), "quest state is saved and kept through death");
        var root = server.getCommands().getDispatcher().getRoot();
        check(root.getChild("quests") != null && root.getChild("fundamentum").getChild("quest") != null, "both quest commands are registered");

        // the quest-giver NPC
        FId abbot = FId.of("questtest", "abbot");
        NpcDef ad = QuestNpcs.get(abbot);
        check(ad != null && ad.slim() && "Brother Aldric".equals(ad.name()) && ad.invulnerable() && !ad.wander(), "an NPC type parses with defaults");
        check(QuestNpcs.spawn(level, p.position(), FId.of("questtest", "nobody")) == null, "an unknown NPC type does not spawn");
        QuestNpc npc = QuestNpcs.spawn(level, p.position().add(2, 0, 0), abbot);
        check(npc != null && abbot.equals(npc.npcType()) && npc.slim() && "questtest:textures/entity/abbot.png".equals(npc.texture()),
            "an NPC spawns with its type's look");
        check(npc != null && npc.isInvulnerable() && "Brother Aldric".equals(npc.getCustomName().getString())
            && npc.getMainHandItem().is(Items.BOOK), "an NPC takes its name, invulnerability and held item");
        check(npc != null && Quests.offers(npc, hunt) && !Quests.offers(npc, Quests.get(GATHER)), "an NPC offers only the quests that list its type");
        check(root.getChild("fundamentum").getChild("npc") != null, "the npc command is registered");
        if (npc != null) npc.discard();

        // offers from a mob
        Mob villager = spawn(level, EntityTypes.VILLAGER, 0);
        check(Quests.offers(villager, hunt), "a villager offers the hunt quest");
        List<QuestDef> offers = Quests.offersFor(p, villager);
        check(offers.size() == 2, "a villager offers the two quests that name it (" + offers.size() + ")");
        check(!Quests.offered(p, HUNT) && Quests.talk(p, villager) && Quests.offered(p, HUNT), "talking opens offers");
        check("rank too low".equals(Quests.cannotAccept(p, ELITE)), "a rank gate holds");
        check("requirements not met".equals(Quests.cannotAccept(p, AFTER)), "a requirement holds");
        refuse = true;
        check("refused".equals(Quests.accept(p, HUNT)) && !Quests.active(p, HUNT), "a cancelled accept event refuses");
        refuse = false;

        // kill
        check(Quests.accept(p, HUNT) == null && Quests.active(p, HUNT), "accepting a quest");
        check("already taken".equals(Quests.cannotAccept(p, HUNT)), "a taken quest cannot be taken twice");
        check(cond("{\"type\":\"quest_active\",\"quest\":\"questtest:hunt\"}", p), "the quest_active condition");
        kill(level, p, EntityTypes.SKELETON, 2);
        check(Quests.progress(p, HUNT).get(0) == 0, "a kill outside the target list does not count");
        int emeralds = count(p, Items.EMERALD);
        int rep = Factions.rep(p, GUILD);
        int done = completedEvents;
        kill(level, p, EntityTypes.ZOMBIE, 3);
        check(Quests.progress(p, HUNT) != null && Quests.progress(p, HUNT).get(0) == 1, "a listed kill counts");
        kill(level, p, EntityTypes.HUSK, 4);
        check(!Quests.active(p, HUNT) && Quests.done(p, HUNT), "the second kill completes the quest");
        check(count(p, Items.EMERALD) == emeralds + 2, "quest rewards are given");
        check(Factions.rep(p, GUILD) == rep + 20, "completing runs the faction quest_done act (" + Factions.rep(p, GUILD) + ")");
        check(completedEvents == done + 1, "the completed event fires");
        check("already done".equals(Quests.cannotAccept(p, HUNT)), "a done quest is not repeatable by default");
        check(cond("{\"type\":\"quest_done\",\"quest\":\"questtest:hunt\"}", p), "the quest_done condition");
        check(Quests.cannotAccept(p, AFTER) == null, "a requirement on a done quest opens the next quest");

        // collect, break, hand-in
        check(Quests.accept(p, GATHER) == null, "accepting a hand-in quest");
        p.getInventory().add(new ItemStack(Items.WHEAT, 7));
        level.setBlockAndUpdate(AT.offset(0, 0, -3), Blocks.DIRT.defaultBlockState());
        p.gameMode.destroyBlock(AT.offset(0, 0, -3));
        Quests.update(p, Quests.get(GATHER));
        check(Quests.ready(p, GATHER) && Quests.active(p, GATHER), "collect and break objectives fill but a hand-in quest waits");
        check(Quests.talk(p, villager) && !Quests.active(p, GATHER), "talking to the giver hands it in");
        check(count(p, Items.WHEAT) == 2, "handing in takes the collected items (" + count(p, Items.WHEAT) + " left)");
        check(Unlocks.has(p, FId.of("questtest", "gathered")), "an unlock reward is given");
        check("not yet".equals(Quests.cannotAccept(p, GATHER)), "a repeatable quest waits for its cooldown");

        // craft, visit
        check(Quests.accept(p, AFTER) == null, "accepting a quest with visit and craft objectives");
        Quests.signal(p, "craft", new ItemStack(Items.STICK, 4), 4);
        check(Quests.progress(p, AFTER) != null && Quests.progress(p, AFTER).get(1) == 4, "crafting counts by stack size");
        Quests.signal(p, "visit", p, 1);
        check(Quests.done(p, AFTER), "a visit objective is met where the player stands");
        check(cond("{\"type\":\"location\",\"dimension\":\"minecraft:overworld\"}", p)
            && !cond("{\"type\":\"location\",\"dimension\":\"minecraft:the_nether\"}", p), "the location condition");

        // act, rank gate passed
        Factions.setRep(p, GUILD, 60);
        check(Quests.accept(p, ELITE) == null, "a high enough rank opens the gated quest");
        Factions.act(p, "questtest:other");
        check(Quests.progress(p, ELITE) != null && Quests.progress(p, ELITE).get(0) == 0, "an unrelated act does not count");
        Factions.act(p, "questtest:ring_bell");
        check(Quests.done(p, ELITE), "a named act completes an act objective");

        // an offer opens for a while
        Quests.offer(p, List.of(Quests.get(TIMED)), QuestSync.JOURNAL);
        check(Quests.offered(p, TIMED), "an offer opens");

        // abandon, then a timed quest left to run out
        check(Quests.accept(p, TIMED) == null, "accepting a timed quest");
        check(Quests.accept(p, GATHER) != null, "a cooldown still holds");
        Quests.reset(p, GATHER);
        int fails = failedEvents;
        check(Quests.accept(p, GATHER) == null && Quests.fail(p, GATHER, true) && !Quests.active(p, GATHER), "abandoning a quest");
        check(failedEvents == fails + 1, "abandoning fires the failed event");
        check(Quests.State.CODEC.encodeStart(JsonOps.INSTANCE, Quests.state(p)).result()
            .flatMap(j -> Quests.State.CODEC.parse(JsonOps.INSTANCE, j).result()).map(s -> s.equals(Quests.state(p))).orElse(false),
            "quest state saves and loads");
        lateRep = Factions.rep(p, GUILD);
    }

    private static void later() {
        ServerPlayer p = player;
        // the fake player is outside the player list, so run its once-a-second check by hand
        Quests.tickPlayer(p);
        check(!Quests.active(p, TIMED), "a quest past its time limit fails");
        check(Factions.rep(p, GUILD) == lateRep - 10, "failing runs the faction quest_failed act (" + Factions.rep(p, GUILD) + ")");
    }
}
