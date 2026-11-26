package com.fundamentum.stat;

import com.fundamentum.attribute.AttributeBonus;
import com.fundamentum.attribute.StartupStats;
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
 * sync codecs, the vanilla statistic and scoreboard hooks, stat modifiers and startup stats (real attributes). Writes stattest-result.txt and stops the server. Log lines start with STATTEST.
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

        // announced changes: vanilla statistics and scores
        Stats.testPlayer = p;
        Stats.flushPending(p);
        p.awardStat(net.minecraft.stats.Stats.JUMP);
        check(Stats.pending(p), "a watched vanilla statistic marks the player");
        before = CHANGED.size();
        Stats.flushPending(p);
        c = lastChange(p, id("jumps"));
        check(!Stats.pending(p) && CHANGED.size() > before && c != null && c.to() == c.from() + 1,
            "the marked player's vanilla_stat stat posts StatChanged");
        p.awardStat(net.minecraft.stats.Stats.WALK_ONE_CM);
        check(!Stats.pending(p), "an unwatched vanilla statistic does not");
        command(server, null, "scoreboard players set StatTester stattest 20");
        check(Stats.pending(p), "a score change marks the player");
        Stats.flushPending(p);
        c = lastChange(p, id("score"));
        check(c != null && c.to() == 20, "the marked player's scoreboard stat posts StatChanged");
        command(server, null, "scoreboard players set Nobody stattest 3");
        check(!Stats.pending(p), "another holder's score does not mark the player");

        // modifiers
        FId pw = id("power");
        Stats.set(p, pw, 4);
        before = CHANGED.size();
        check(Stats.addModifier(p, pw, id("a"), 2, AttributeBonus.Op.ADD_VALUE) && Stats.get(p, pw) == 6 && Stats.base(p, pw) == 4,
            "an add modifier adds to the value, not the base");
        c = lastChange(p, pw);
        check(CHANGED.size() > before && c != null && c.from() == 4 && c.to() == 6, "adding a modifier posts StatChanged");
        Stats.addModifier(p, pw, id("b"), 0.5, AttributeBonus.Op.ADD_MULTIPLIED_BASE);
        check(near(Stats.get(p, pw), 9), "multiply_base scales base plus adds (" + Stats.get(p, pw) + ")");
        Stats.addModifier(p, pw, id("t"), 1, AttributeBonus.Op.ADD_MULTIPLIED_TOTAL);
        check(Stats.get(p, pw) == 10, "the modified value is clamped to max");
        check(Stats.removeModifier(p, pw, id("t")) && near(Stats.get(p, pw), 9), "removing a modifier undoes it");
        check(!Stats.removeModifier(p, pw, id("t")), "removing a missing modifier says so");
        Stats.add(p, pw, 1);
        check(Stats.base(p, pw) == 5 && near(Stats.get(p, pw), 10), "add changes the base under the modifiers");
        Stats.set(p, pw, 4);
        Stats.removeModifiers(p, id("a"));
        check(near(Stats.get(p, pw), 6) && Stats.modifiers(p, pw).size() == 1, "removeModifiers takes one id off");
        command(server, p, "fundamentum stat modifier add @s stattest:power stattest:cmd 2 add_value");
        check(near(Stats.get(p, pw), 9), "/fundamentum stat modifier add works (" + Stats.get(p, pw) + ")");
        command(server, p, "fundamentum stat modifier remove @s stattest:power stattest:cmd");
        check(near(Stats.get(p, pw), 6), "/fundamentum stat modifier remove works");
        Stats.removeModifier(p, pw, id("b"));
        check(Stats.get(p, pw) == 4 && Stats.modifiers(p, pw).isEmpty(), "with no modifiers the value is the base");
        check(!Stats.addModifier(p, id("sharpness"), id("a"), 1, AttributeBonus.Op.ADD_VALUE), "item stats take no modifiers");
        AttributeBonus bonus = AttributeBonus.parse(JsonParser.parseString("{\"stat\":\"stattest:power\",\"amount\":3}").getAsJsonObject());
        check(bonus.attribute() == null && pw.equals(bonus.stat()), "a bonus can name a stat");
        bonus.apply(p, id("set_bonus").vanilla(), 1);
        check(Stats.get(p, pw) == 7, "applying a stat bonus adds a stat modifier");
        bonus.remove(p, id("set_bonus").vanilla());
        check(Stats.get(p, pw) == 4, "removing the stat bonus takes it off");

        // startup stats
        FId magic = id("magic");
        check(StartupStats.entries().containsKey(magic), "a jar's startup stat is read (" + StartupStats.entries().keySet() + ")");
        check(net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.containsKey(magic.vanilla()), "a startup stat is a registered attribute");
        check(Stats.declared(magic) && "attribute".equals(Stats.def(magic).sourceType()), "a startup stat is declared as an attribute stat");
        check(Stats.get(p, magic) == 3 && Stats.get(z, magic) == 3, "players and mobs have it at its default");
        Stats.addModifier(z, magic, id("a"), 4, AttributeBonus.Op.ADD_VALUE);
        check(Stats.get(z, magic) == 7, "a startup stat takes modifiers");
        Stats.removeModifier(z, magic, id("a"));
        try {
            Path dir = Path.of("stattest-startup");
            Files.createDirectories(dir.resolve("stattest"));
            Files.writeString(dir.resolve("stattest").resolve("extra.json"), "{\"default\": 2, \"max\": 5}");
            Files.writeString(dir.resolve("loose.json"), "{\"id\": \"stattest:loose\"}");
            Files.writeString(dir.resolve("bad.json"), "{\"default\": 9}");
            Files.writeString(dir.resolve("stattest").resolve("broken.json"), "{\"default\": 9, \"max\": 5}");
            Map<FId, StartupStats.Entry> cfg = StartupStats.fromConfig(dir);
            StartupStats.Entry extra = cfg.get(id("extra"));
            check(extra != null && extra.base() == 2 && extra.max() == 5 && "attribute.name.stattest.extra".equals(extra.name()),
                "a config stat file takes its id from its path");
            check(cfg.containsKey(id("loose")) && cfg.size() == 2, "a loose file needs an id, a broken one is skipped (" + cfg.keySet() + ")");
        } catch (IOException e) {
            check(false, "the startup stat files could not be written: " + e);
        }
        boolean refused = false;
        try {
            StartupStats.entry(FId.of("minecraft", "nope"), new com.google.gson.JsonObject());
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check(refused, "a startup stat cannot use the minecraft namespace");

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
