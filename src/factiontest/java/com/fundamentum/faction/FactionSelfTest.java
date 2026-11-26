package com.fundamentum.faction;

import com.fundamentum.condition.Conditions;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.mobgoal.MobGoals;
import com.fundamentum.mobgoal.goal.FactionTargetGoal;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The faction test, only in {@code gradlew runServer -Pfactiontest} (world under run-factiontest). Loads two test
 * factions and an alignment axis, then checks rep, spill, clamping, ranks with their unlocks and one-time rewards,
 * cancelling, member mobs and villagers, attitudes, faction wars, villager prices, kill/break/custom acts, membership
 * with groups and min rank, alignment, conditions, drift and saving. Writes factiontest-result.txt and stops the server.
 * Log lines start with FACTIONTEST.
 */
public final class FactionSelfTest {
    private FactionSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final BlockPos AT = new BlockPos(8, 200, 8);
    private static final FId LANTERN = FId.of("factiontest", "lantern");
    private static final FId RAIDERS = FId.of("factiontest", "raiders");
    private static final FId ORDER = FId.of("factiontest", "order");
    private static boolean failed;
    private static boolean block;
    private static int rankEvents;
    private static int ticks;

    public static void init() {
        if (System.getProperty("fundamentum.factiontest") == null) return;
        FLife.serverTick(FactionSelfTest::tick);
        FEvents.listen(Events.RankChanged.class, e -> rankEvents++);
        FEvents.listen(Events.RepChanged.class, (e, call) -> {
            if (block) call.cancel();
        });
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("FACTIONTEST {}", line);
        else LOG.error("FACTIONTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks == 5) server.overworld().setChunkForced(0, 0, true);
        try {
            if (ticks == 40) run(server);
        } catch (Throwable t) {
            LOG.error("FACTIONTEST threw", t);
            check(false, "threw " + t);
        }
        if (ticks != 60) return;
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("FACTIONTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("factiontest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("FACTIONTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static int emeralds(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(Items.EMERALD)) n += s.getCount();
        }
        return n;
    }

    private static <T extends Mob> T spawn(ServerLevel level, EntityType<T> type, int dx) {
        T m = type.create(level);
        m.setPos(AT.getX() + dx + 0.5, AT.getY(), AT.getZ() + 3.5);
        m.setNoAi(false);
        level.addFreshEntity(m);
        return m;
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
        ServerPlayer p = FVanilla.fakePlayer(level, "factiontest");
        p.setPos(AT.getX() + 0.5, AT.getY(), AT.getZ() + 0.5);

        // data
        FactionDef lantern = Factions.get(LANTERN);
        check(lantern != null && Factions.get(RAIDERS) != null, "both test factions load (" + Factions.all().keySet() + ")");
        check(Factions.axes().containsKey(ORDER), "the alignment axis loads");
        check(lantern != null && lantern.ranks().size() == 4 && lantern.color() == 0xE0B040, "ranks and colour parse");
        check(Factions.PLAYER.saved() && Factions.PLAYER.copiesOnDeath(), "faction state is saved and kept through death");
        check(server.getCommands().getDispatcher().getRoot().getChild("fundamentum").getChild("faction") != null, "the faction command is registered");

        // rep, ranks, spill, rewards
        check(Factions.rep(p, LANTERN) == 0 && "neutral".equals(Factions.rank(p, LANTERN).id()), "rep starts at 0 (neutral)");
        int events = rankEvents;
        Factions.addRep(p, LANTERN, 150);
        check(Factions.rep(p, LANTERN) == 150 && "friendly".equals(Factions.rank(p, LANTERN).id()), "+150 reaches friendly");
        check(rankEvents == events + 1, "the rank change is posted");
        check(Factions.rep(p, RAIDERS) == -75, "half the change spills, negated, to the enemy faction (" + Factions.rep(p, RAIDERS) + ")");
        check(Unlocks.has(p, lantern.rankUnlock("friendly")) && !Unlocks.has(p, lantern.rankUnlock("neutral")), "the rank unlock follows the rank");
        check(emeralds(p) == 3 && Unlocks.has(p, FId.of("factiontest", "lantern_reward")), "the rank rewards are given (" + emeralds(p) + " emeralds)");
        Factions.setRep(p, LANTERN, 0);
        check(!Unlocks.has(p, lantern.rankUnlock("friendly")) && Unlocks.has(p, lantern.rankUnlock("neutral")), "falling a rank swaps the unlock back");
        Factions.setRep(p, LANTERN, 150);
        check(emeralds(p) == 3, "rank rewards are given only once");
        Factions.setRep(p, LANTERN, 5000);
        check(Factions.rep(p, LANTERN) == 1000 && "honoured".equals(Factions.rank(p, LANTERN).id()), "rep is clamped to max");
        block = true;
        boolean changed = Factions.setRep(p, LANTERN, 150);
        block = false;
        check(!changed && Factions.rep(p, LANTERN) == 1000, "a cancelled rep change keeps the old value");
        Factions.setRep(p, LANTERN, 150);

        // members and attitudes
        Mob vindicator = spawn(level, EntityType.VINDICATOR, -3);
        Mob pillager = spawn(level, EntityType.PILLAGER, 3);
        Villager cleric = spawn(level, EntityType.VILLAGER, 0);
        cleric.setVillagerData(cleric.getVillagerData().setProfession(VillagerProfession.CLERIC));
        check(Factions.isMember(vindicator, LANTERN) && Factions.isMember(pillager, RAIDERS), "mobs belong by entity type");
        check(Factions.isMember(cleric, LANTERN), "a cleric villager belongs by profession");
        check(vindicator.targetSelector.getAvailableGoals().stream().anyMatch(g -> g.getGoal() instanceof FactionTargetGoal), "member mobs get the faction target goal");
        check(FactionDef.FRIENDLY.equals(Factions.attitude(vindicator, p)), "a friendly-rank member is friendly");
        check(!MobGoals.factions().hostile(vindicator, p) && MobGoals.factions().allied(vindicator, p), "the hook treats the player as an ally at friendly");
        vindicator.setTarget(p);
        check(vindicator.getTarget() == null, "a friendly member will not target the player");
        check(MobGoals.factions().hostile(vindicator, pillager) && MobGoals.factions().hostile(pillager, vindicator), "enemy factions' mobs are hostile to each other");
        check(MobGoals.factions().allied(vindicator, vindicator), "members of one faction are allies");
        Mob cowA = spawn(level, EntityType.COW, 6);
        Mob cowB = spawn(level, EntityType.COW, 7);
        check(MobGoals.factions().allied(cowA, cowB) && !MobGoals.factions().hostile(cowA, cowB),
            "two mobs of one type with no faction stay allies, as before factions");
        check(!MobGoals.factions().allied(cowA, vindicator) && !MobGoals.factions().allied(vindicator, cowA),
            "a faction member and a mob with no faction are not allies");
        cowA.discard();
        cowB.discard();
        Factions.setRep(p, LANTERN, -500);
        check(MobGoals.factions().hostile(vindicator, p), "at hated, members are hostile to the player");
        Factions.setRep(p, LANTERN, 150);

        // prices
        List<MerchantOffer> offers = cleric.getOffers();
        double k = Factions.priceFactor(cleric, p);
        int[] before = offers.stream().mapToInt(MerchantOffer::getSpecialPriceDiff).toArray();
        Factions.applyPrices(cleric, p);
        boolean scaled = !offers.isEmpty() && k < 1;
        for (int i = 0; i < offers.size() && scaled; i++) {
            MerchantOffer o = offers.get(i);
            scaled = o.getSpecialPriceDiff() - before[i] == (int) Math.round(o.getBaseCostA().getCount() * (k - 1));
        }
        check(scaled, "friendly rank lowers a member villager's prices (factor " + k + ", " + offers.size() + " offers)");

        // acts
        vindicator.die(p.damageSources().playerAttack(p));
        check(Factions.rep(p, LANTERN) == 100, "killing a member costs 50 rep (" + Factions.rep(p, LANTERN) + ")");
        check(Factions.alignment(p, ORDER) == -2, "and pushes alignment (" + Factions.alignment(p, ORDER) + ")");
        pillager.die(p.damageSources().playerAttack(p));
        check(Factions.rep(p, LANTERN) == 110, "killing an enemy member earns 10 rep (" + Factions.rep(p, LANTERN) + ")");
        check(Factions.act(p, "quest_done") == 1 && Factions.rep(p, LANTERN) == 140, "a custom act applies (" + Factions.rep(p, LANTERN) + ")");
        level.setBlockAndUpdate(AT.offset(0, 0, -3), Blocks.GOLD_BLOCK.defaultBlockState());
        p.gameMode.destroyBlock(AT.offset(0, 0, -3));
        check(Factions.rep(p, LANTERN) == 115, "breaking a listed block costs 25 rep (" + Factions.rep(p, LANTERN) + ")");

        // membership
        check(Factions.join(p, LANTERN) == null && Factions.member(p, LANTERN), "the player joins");
        check(Unlocks.has(p, lantern.memberUnlock()), "joining grants the member unlock");
        String why = Factions.join(p, RAIDERS);
        check(why != null && why.startsWith("already in"), "factions in one group exclude each other (" + why + ")");
        check(Factions.leave(p, LANTERN) && !Factions.member(p, LANTERN) && !Unlocks.has(p, lantern.memberUnlock()), "the player leaves");
        check(Factions.rep(p, LANTERN) == 15, "leaving costs the leave rep (" + Factions.rep(p, LANTERN) + ")");
        Factions.setRep(p, LANTERN, -500);
        check("rank too low".equals(Factions.join(p, LANTERN)), "joining needs the min rank");
        check(Factions.cannotJoin(p, FId.of("factiontest", "nope")) != null, "an unknown faction cannot be joined");

        // alignment and conditions
        Factions.setAlignment(p, ORDER, 50);
        check(Factions.alignment(p, ORDER) == 10, "alignment is clamped to the axis");
        Factions.addAlignment(p, ORDER, -30);
        check(Factions.alignment(p, ORDER) == -10, "alignment adds and clamps");
        Factions.setRep(p, LANTERN, 0);
        check(cond("{\"type\":\"faction_rep\",\"faction\":\"factiontest:lantern\",\"min\":0}", p), "faction_rep condition");
        check(cond("{\"type\":\"faction_rank\",\"faction\":\"factiontest:lantern\",\"rank\":\"neutral\"}", p)
            && !cond("{\"type\":\"faction_rank\",\"faction\":\"factiontest:lantern\",\"rank\":\"friendly\",\"at_least\":true}", p), "faction_rank condition");
        check(!cond("{\"type\":\"faction_member\",\"faction\":\"factiontest:lantern\"}", p), "faction_member condition");
        check(cond("{\"type\":\"alignment\",\"axis\":\"factiontest:order\",\"max\":-5}", p), "alignment condition");

        // drift
        Factions.setRep(p, LANTERN, 300);
        Factions.drift(p);
        check(Factions.rep(p, LANTERN) == 200, "rep drifts toward its rest value (" + Factions.rep(p, LANTERN) + ")");
        Factions.setRep(p, LANTERN, -50);
        Factions.drift(p);
        check(Factions.rep(p, LANTERN) == 0, "drift stops at the rest value (" + Factions.rep(p, LANTERN) + ")");

        // saving
        Factions.State s = Factions.PLAYER.get(p);
        Factions.State back = Factions.State.CODEC.parse(JsonOps.INSTANCE, Factions.State.CODEC.encodeStart(JsonOps.INSTANCE, s).getOrThrow()).getOrThrow();
        check(back.equals(s), "faction state survives its codec");
        cleric.discard();
    }
}
