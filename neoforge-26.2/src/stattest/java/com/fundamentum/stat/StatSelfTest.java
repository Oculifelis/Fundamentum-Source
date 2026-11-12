package com.fundamentum.stat;

import com.fundamentum.condition.Conditions;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.mana.Mana;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FVanilla;
import com.fundamentum.unlock.Unlocks;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stat test, only in {@code gradlew runServer -Pstattest} (world under run-stattest). The same file on every
 * version. Checks stored stats (declared and not), clamping, each built-in source, formatting, item and entity stats,
 * tooltips, the {@code stat} condition, the command, {@link Events.StatChanged}, the once-a-second check and the save and
 * sync codecs. Writes stattest-result.txt and stops the server. Log lines start with STATTEST.
 */
public final class StatSelfTest {
    private StatSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final List<Events.StatChanged> CHANGED = new ArrayList<>();
    private static boolean failed;
    private static int ticks;

    public static void init() {
        if (System.getProperty("fundamentum.stattest") == null) return;
        FEvents.listen(Events.StatChanged.class, (Events.StatChanged e) -> CHANGED.add(e));
        FLife.serverTick(StatSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("STATTEST {}", line);
        else LOG.error("STATTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        if (++ticks != 40) return;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("STATTEST threw", t);
            check(false, "threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("STATTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("stattest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("STATTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static FId id(String s) {
        return FId.of("stattest", s);
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static Events.StatChanged lastChange(LivingEntity e, FId stat) {
        for (int i = CHANGED.size() - 1; i >= 0; i--) {
            Events.StatChanged c = CHANGED.get(i);
            if (c.entity() == e && c.stat().equals(stat)) return c;
        }
        return null;
    }

    private static void command(MinecraftServer server, ServerPlayer as, String cmd) {
        CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(as == null ? src : src.withEntity(as), cmd);
    }

    private static void run(MinecraftServer server) {
        ServerPlayer p = FVanilla.testPlayer(server.overworld(), "StatTester");

        // declarations
        check(Stats.defs().size() >= 10, "the test datapack's stats are loaded (" + Stats.defs().size() + ")");
        StatDef power = Stats.def(id("power"));
        check(Stats.declared(id("power")) && power.min() == 0 && power.max() == 10 && power.defaultValue() == 2
            && power.scope() == StatDef.Scope.PLAYER && power.stored() && "stat.stattest.power".equals(power.name())
            && FId.of("minecraft", "iron_sword").equals(power.icon()), "a declaration reads its fields");
        check(!Stats.declared(id("free")) && Stats.def(id("free")).stored(), "an undeclared id is a stored stat");

        // stored stats
        check(Stats.get(p, id("free")) == 0, "an undeclared stat starts at 0");
        check(Stats.set(p, id("free"), 4.5) && near(Stats.get(p, id("free")), 4.5), "an undeclared stat can be set");
        Events.StatChanged c = lastChange(p, id("free"));
        check(c != null && c.from() == 0 && c.to() == 4.5, "setting posts StatChanged with from and to");
        check(Stats.add(p, id("free"), 1) && near(Stats.get(p, id("free")), 5.5), "add adds");
        int before = CHANGED.size();
        Stats.set(p, id("free"), 5.5);
        check(CHANGED.size() == before, "setting the same value posts nothing");
        check(Stats.get(p, id("power")) == 2, "a declared stat starts at its default");
        check(Stats.set(p, id("power"), 50) && Stats.get(p, id("power")) == 10, "a write is clamped to max");
        check(Stats.set(p, id("power"), -3) && Stats.get(p, id("power")) == 0, "a write is clamped to min");

        // sources
        check(near(Stats.get(p, id("health")), p.getMaxHealth()), "the attribute source reads max health (" + Stats.get(p, id("health")) + ")");
        check(!Stats.set(p, id("health"), 5), "a computed stat cannot be set");
        check(near(Stats.get(p, id("mana")), Mana.get(p)), "the mana source reads the mana pool");
        check(Stats.get(p, id("skill")) == 0, "the unlock source is 0 without the unlock");
        Unlocks.grant(p, id("skill"), 3);
        check(Stats.get(p, id("skill")) == 3, "the unlock source reads the unlock level");
        double jumps = Stats.get(p, id("jumps"));
        p.awardStat(net.minecraft.stats.Stats.JUMP);
        p.awardStat(net.minecraft.stats.Stats.JUMP);
        check(Stats.get(p, id("jumps")) == jumps + 2, "the vanilla_stat source reads the jump count");
        command(server, null, "scoreboard objectives add stattest dummy");
        command(server, null, "scoreboard players set StatTester stattest 9");
        check(Stats.get(p, id("score")) == 9, "the scoreboard source reads the score");
        check(Stats.set(p, id("score"), 12) && Integer.valueOf(12).equals(FVanilla.score(server, "StatTester", "stattest")),
            "a scoreboard stat can be set");
        c = lastChange(p, id("score"));
        check(c != null && c.from() == 9 && c.to() == 12, "setting a scoreboard stat posts StatChanged");
        Stats.registerSource("stattest:double_level", (def, e, s) -> e == null ? 0 : e.getMaxHealth() * 2);
        check(near(Stats.get(p, id("custom")), p.getMaxHealth() * 2), "a mod's own source works");

        // formatting
        check("50%".equals(Stats.def(id("crit")).format(0.5)), "percent stats format as a percentage");
        check("1:05".equals(Stats.def(id("playtime")).format(1300)) && "1:00:00".equals(Stats.def(id("playtime")).format(72000)),
            "time stats format ticks as m:ss and h:mm:ss");
        check("2.5".equals(Stats.def(id("speed")).format(2.5)) && "3".equals(Stats.def(id("free")).format(3))
            && "2.35".equals(Stats.def(id("free")).format(2.349)), "number stats format plainly");
        check("7".equals(Stats.def(id("jumps")).format(7.9)), "count stats drop the fraction");
        check("4.0x".equals(Stats.format(id("multiplier"), 4)), "a format pattern is used");

        // items
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        check(Stats.get(sword, id("sharpness")) == 1, "an item stat starts at its default");
        check(Stats.set(sword, id("sharpness"), 7) && Stats.get(sword, id("sharpness")) == 7, "an item stat can be set on a stack");
        check(Stats.set(sword, id("sharpness"), 500) && Stats.get(sword, id("sharpness")) == 100, "an item stat is clamped");
        check(Stats.get(sword.copy(), id("sharpness")) == 100, "a copied stack keeps it");
        check(Stats.get(new ItemStack(Items.DIAMOND_SWORD), id("sharpness")) == 1, "another stack does not have it");
        List<Component> tip = Stats.tooltip(sword, Stats.defs());
        check(tip.size() == 1 && tip.get(0).getString().equals("Sharpness: 100"), "the tooltip shows item stats (" + tip + ")");
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, sword);
        check(Stats.get(p, id("sharpness")) == 100, "an item stat read through an entity uses the main hand");
        check(Stats.tooltip(new ItemStack(Items.STICK), Stats.defs()).isEmpty(), "a plain stack has no stat lines");

        // entities
        LivingEntity z = FVanilla.create(FId.of("minecraft", "zombie"), server.overworld());
        check(Stats.get(z, id("armor")) == 5, "an entity stat starts at its default");
        check(Stats.set(z, id("armor"), 8) && Stats.get(z, id("armor")) == 8, "an entity stat can be set on a mob");
        c = lastChange(z, id("armor"));
        check(c != null && c.entity() == z && c.from() == 5 && c.to() == 8, "a mob's change posts StatChanged");
        check(Stats.ENTITY.roundTrip(Stats.ENTITY.get(z), server.registryAccess()).equals(Stats.ENTITY.get(z)), "stored stats survive a save and load");

        // the stat condition
        Stats.set(p, id("power"), 6);
        check(Conditions.test(JsonParser.parseString("{\"type\":\"stat\",\"id\":\"stattest:power\",\"min\":5}"), p), "the stat condition passes in range");
        check(!Conditions.test(JsonParser.parseString("{\"type\":\"stat\",\"id\":\"stattest:power\",\"max\":5}"), p), "the stat condition fails out of range");
        check(Conditions.test(JsonParser.parseString("{\"type\":\"stat\",\"id\":\"stattest:sharpness\",\"min\":50}"),
            new Conditions.Context(p, sword)), "the stat condition reads an item stat from the stack");
        check(!Conditions.test(JsonParser.parseString("{\"type\":\"stat\",\"id\":\"stattest:sharpness\",\"min\":50}"),
            new Conditions.Context(p, new ItemStack(Items.IRON_SWORD))), "and from the given stack, not the hand");

        // the command
        command(server, p, "fundamentum stat set @s stattest:power 7");
        check(Stats.get(p, id("power")) == 7, "/fundamentum stat set works");
        command(server, p, "fundamentum stat add @s stattest:power 2");
        check(Stats.get(p, id("power")) == 9, "/fundamentum stat add works");
        command(server, p, "fundamentum stat get @s stattest:power");
        check(Stats.get(p, id("power")) == 9, "/fundamentum stat get leaves the value alone");

        // the once-a-second check
        Stats.scan(p);
        before = CHANGED.size();
        p.awardStat(net.minecraft.stats.Stats.JUMP);
        Stats.scan(p);
        c = lastChange(p, id("jumps"));
        check(CHANGED.size() > before && c != null && c.to() == c.from() + 1, "the check posts StatChanged for a computed stat");
        before = CHANGED.size();
        Stats.scan(p);
        check(CHANGED.size() == before, "nothing changed, nothing posted");

        // sync
        Map<String, Double> values = Stats.values(p);
        check(values.containsKey("stattest:power") && values.containsKey("stattest:free") && !values.containsKey("stattest:sharpness"),
            "values() lists declared and stored player stats, not item stats");
        Stats.Sync sync = new Stats.Sync(Map.of("stattest:power", 9.0, "stattest:free", 5.5));
        check(FNet.roundTrip(sync, server.registryAccess()).equals(sync), "the value sync survives the network codec");
        Stats.Defs defs = Stats.defsMessage();
        check(FNet.roundTrip(defs, server.registryAccess()).equals(defs), "the declarations survive the network codec");
        Map<FId, StatDef> back = Stats.parseAll(defs.json());
        check(back.size() == Stats.defs().size() && back.get(id("power")).equals(Stats.defs().get(id("power"))), "the client reads the same declarations");
    }
}
