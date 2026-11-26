package com.fundamentum.religion;

import com.fundamentum.condition.Conditions;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.faction.Factions;
import com.fundamentum.faction.Rewards;
import com.fundamentum.mana.Mana;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FVanilla;
import com.fundamentum.quest.Quests;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The religion test, only in {@code gradlew runServer -Preligiontest} (world under run-religiontest). Loads two gods
 * in an exclusive pantheon, a shrine, a ritual and a quest, then checks following, favour as mana, prayer, shrine power,
 * offerings, boons, revive, events, conditions, rituals, the pray objective, desecration, wrath, regen, weather and
 * saving. Writes religiontest-result.txt and stops the server. Log lines start with RELIGIONTEST.
 */
public final class ReligionSelfTest {
    private ReligionSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final BlockPos AT = new BlockPos(8, 200, 8);
    private static final FId SUN = FId.of("religiontest", "sun");
    private static final FId MOON = FId.of("religiontest", "moon");
    private static final FId DEVOUT = FId.of("religiontest", "devout");
    private static boolean failed;
    private static boolean refuseBoon;
    private static boolean refuseDevotion;
    private static int prayers;
    private static int devotionEvents;
    private static int rituals;
    private static int ticks;

    public static void init() {
        if (System.getProperty("fundamentum.religiontest") == null) return;
        FLife.serverTick(ReligionSelfTest::tick);
        FEvents.listen(Events.Prayed.class, e -> prayers++);
        FEvents.listen(Events.RitualFinished.class, e -> rituals++);
        FEvents.listen(Events.BoonGranted.class, (e, call) -> {
            if (refuseBoon) call.cancel();
        });
        FEvents.listen(Events.DevotionChanged.class, (e, call) -> {
            devotionEvents++;
            if (refuseDevotion) call.cancel();
        });
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("RELIGIONTEST {}", line);
        else LOG.error("RELIGIONTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks == 5) server.overworld().setChunkForced(0, 0, true);
        try {
            if (ticks == 40) run(server);
        } catch (Throwable t) {
            LOG.error("RELIGIONTEST threw", t);
            check(false, "threw " + t);
        }
        if (ticks != 60) return;
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("RELIGIONTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("religiontest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("RELIGIONTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static boolean cond(String json, ServerPlayer p) {
        return Conditions.test(JsonParser.parseString(json), p);
    }

    private static int count(ServerPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    private static void freshPrayer(ServerPlayer p) {
        Religion.clearCooldown(p, "pray|" + SUN);
    }

    private static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                level.setBlockAndUpdate(AT.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 4; y++) level.setBlockAndUpdate(AT.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }
        ServerPlayer p = FVanilla.fakePlayer(level, "religiontest");
        p.setPos(AT.getX() + 0.5, AT.getY(), AT.getZ() + 0.5);

        // data
        check(Religion.deities().size() == 2, "two gods load (" + Religion.deities().keySet() + ")");
        check(Religion.pantheons().size() == 1 && Religion.shrines().size() == 1 && Religion.rituals().size() == 1,
            "a pantheon, a shrine and a ritual load");
        Deity sun = Religion.deity(SUN);
        Deity moon = Religion.deity(MOON);
        check(sun != null && sun.boons().size() == 2 && sun.offerings().size() == 1 && sun.favourMax() == 100, "a god parses");
        check(Religion.pantheonsOf(SUN).size() == 1 && Religion.pantheonsOf(MOON).size() == 1,
            "a god joins a pantheon by its own field and by the pantheon's list");
        check(moon != null && moon.wrath().enabled() && !sun.wrath().enabled(), "wrath is off unless a god turns it on");
        check(Religion.PLAYER.saved() && Religion.PLAYER.copiesOnDeath(), "religion state is saved and kept through death");
        var root = server.getCommands().getDispatcher().getRoot();
        check(root.getChild("gods") != null && root.getChild("fundamentum").getChild("god") != null, "both religion commands are registered");

        // following
        check(Mana.source(p, Religion.FAVOUR).isEmpty(), "no favour pool before following a god");
        check(Religion.follow(p, SUN) == null && Religion.follows(p, SUN), "following a god");
        String why = Religion.follow(p, MOON);
        check(why != null && why.startsWith("already follows"), "an exclusive pantheon refuses a second god (" + why + ")");
        check(Mana.source(p, Religion.FAVOUR).map(s -> s.max(p) == 100).orElse(false), "a follower has a favour mana pool");

        // praying
        BlockPos shrine = AT.offset(2, 0, 0);
        level.setBlockAndUpdate(shrine, Blocks.GOLD_BLOCK.defaultBlockState());
        check(Religion.shrineAt(level, shrine) != null && Religion.shrineAt(level, AT.offset(2, -1, 0)) == null, "a shrine block is found");
        int pr = prayers;
        check(Religion.pray(p, shrine) == null, "praying at a shrine");
        check(Religion.devotion(p, SUN) == 2 && Religion.favour(p, SUN) == 5, "a prayer gives devotion and favour ("
            + Religion.devotion(p, SUN) + ", " + Religion.favour(p, SUN) + ")");
        check(prayers == pr + 1, "the prayed event fires");
        check("prayed too recently".equals(Religion.pray(p, shrine)) && Religion.devotion(p, SUN) == 2, "prayer has a cooldown");
        check(Mana.source(p, Religion.FAVOUR).map(s -> s.spend(p, 2)).orElse(false) && Religion.favour(p, SUN) == 3,
            "spending favour through the mana pool");
        level.setBlockAndUpdate(shrine.above(), Blocks.GLOWSTONE.defaultBlockState());
        check(Religion.power(level, shrine, Religion.shrineAt(level, shrine)) == 1.5, "nearby blocks raise shrine power");
        freshPrayer(p);
        Religion.pray(p, shrine);
        check(Religion.devotion(p, SUN) == 5 && Religion.favour(p, SUN) == 10.5, "power scales a prayer ("
            + Religion.devotion(p, SUN) + ", " + Religion.favour(p, SUN) + ")");

        // offerings
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLD_INGOT, 2));
        int dev = Religion.devotion(p, SUN);
        check(Religion.offer(p, shrine, p.getMainHandItem()) == null && p.getMainHandItem().getCount() == 1, "an offering takes one item");
        check(Religion.devotion(p, SUN) == dev + 8, "an offering gives devotion times power (" + Religion.devotion(p, SUN) + ")");
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT));
        check("not wanted".equals(Religion.offer(p, shrine, p.getMainHandItem())) && p.getMainHandItem().getCount() == 1,
            "an unwanted item is not taken");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        // devotion events
        int de = devotionEvents;
        Factions.setRep(p, SUN, 30);
        check(devotionEvents == de + 1 && Religion.devotion(p, SUN) == 30, "changing devotion posts the devotion event");
        refuseDevotion = true;
        check(!Factions.setRep(p, SUN, 40) && Religion.devotion(p, SUN) == 30, "a cancelled devotion event keeps the old value");
        refuseDevotion = false;

        // boons
        Religion.setFavour(p, SUN, 50);
        p.setHealth(5);
        check(Religion.boon(p, SUN, "mend") == null && p.getHealth() == 15 && Religion.favour(p, SUN) == 30,
            "a boon spends favour and heals (" + p.getHealth() + ", " + Religion.favour(p, SUN) + ")");
        check("not a follower".equals(Religion.cannotBoon(p, MOON, "x")) || "unknown boon".equals(Religion.cannotBoon(p, MOON, "x")),
            "boons need a known boon of a followed god");
        Religion.setFavour(p, SUN, 10);
        check("not enough favour".equals(Religion.boon(p, SUN, "mend")), "a boon needs enough favour");
        Religion.setFavour(p, SUN, 50);
        refuseBoon = true;
        check("refused".equals(Religion.boon(p, SUN, "mend")) && Religion.favour(p, SUN) == 50, "a cancelled boon spends nothing");
        refuseBoon = false;
        Factions.setRep(p, SUN, 5);
        check("devotion too low".equals(Religion.cannotBoon(p, SUN, "mend")), "a boon's rank gate holds");
        Factions.setRep(p, SUN, 30);

        // conditions
        check(cond("{\"type\":\"devotion\",\"deity\":\"religiontest:sun\",\"min\":20}", p), "the devotion condition");
        check(cond("{\"type\":\"patron\",\"deity\":\"religiontest:sun\"}", p)
            && !cond("{\"type\":\"patron\",\"deity\":\"religiontest:moon\"}", p), "the patron condition");
        check(cond("{\"type\":\"favour\",\"min\":40}", p) && !cond("{\"type\":\"favour\",\"min\":60}", p), "the favour condition");

        // miracles through rewards
        Rewards.give(p, JsonParser.parseString("{\"weather\":\"rain\"}"));
        check(server.overworld().getLevelData().isRaining(), "the weather miracle brings rain");
        Rewards.give(p, JsonParser.parseString("{\"weather\":\"clear\",\"favour\":5}"));
        check(!server.overworld().getLevelData().isRaining() && Religion.favour(p, SUN) == 55, "clear weather and the favour reward");

        // ritual
        p.getInventory().add(new ItemStack(Items.DIAMOND));
        freshPrayer(p);
        int rf = rituals;
        dev = Religion.devotion(p, SUN);
        check(Religion.pray(p, shrine) == null && count(p, Items.DIAMOND) == 0, "praying with the items performs the ritual");
        check(rituals == rf + 1 && Unlocks.has(p, FId.of("religiontest", "blessed")) && Religion.devotion(p, SUN) == dev + 10,
            "the ritual gives its rewards and fires its event");
        p.getInventory().add(new ItemStack(Items.DIAMOND));
        freshPrayer(p);
        Religion.pray(p, shrine);
        check(count(p, Items.DIAMOND) == 1 && rituals == rf + 1, "a ritual has a cooldown; a plain prayer happens instead");

        // pray objective
        check(Quests.accept(p, DEVOUT) == null, "accepting a prayer quest (" + Quests.cannotAccept(p, DEVOUT) + ")");
        freshPrayer(p);
        Religion.pray(p, shrine);
        check(Quests.done(p, DEVOUT), "praying completes the pray objective");

        // regen
        Religion.setFavour(p, SUN, 0);
        Religion.minute(p);
        check(Religion.favour(p, SUN) == 10, "followers regain favour each minute (" + Religion.favour(p, SUN) + ")");

        // revive
        Religion.setFavour(p, SUN, 100);
        Factions.setRep(p, SUN, 30);
        LivingDeathEvent death = new LivingDeathEvent(p, p.damageSources().generic());
        NeoForge.EVENT_BUS.post(death);
        check(death.isCanceled() && Religion.favour(p, SUN) == 50, "a revive boon cancels death and spends favour");

        // desecration
        dev = Religion.devotion(p, SUN);
        p.gameMode.destroyBlock(shrine);
        check(level.getBlockState(shrine).isAir() && Religion.devotion(p, SUN) == dev - 30, "breaking a shrine costs devotion ("
            + Religion.devotion(p, SUN) + ")");

        // wrath
        Factions.setRep(p, MOON, -100);
        check(Religion.wrath(p, moon, false) && p.hasEffect(MobEffects.WEAKNESS), "a hated god sends wrath");
        check(!Religion.wrath(p, moon, false), "wrath has a cooldown");
        check(!Religion.wrath(p, sun, true), "a god without wrath sends none");

        // forsaking
        dev = Religion.devotion(p, SUN);
        check(Religion.forsake(p, SUN) && !Religion.follows(p, SUN) && Religion.devotion(p, SUN) == dev - 10, "forsaking a god costs devotion");
        Factions.setRep(p, MOON, 0);
        check(Religion.follow(p, MOON) == null, "after forsaking, the other god of the pantheon can be followed");

        // saving
        check(Religion.State.CODEC.encodeStart(JsonOps.INSTANCE, Religion.state(p)).result()
            .flatMap(j -> Religion.State.CODEC.parse(JsonOps.INSTANCE, j).result()).map(s -> s.equals(Religion.state(p))).orElse(false),
            "religion state saves and loads");
    }
}
