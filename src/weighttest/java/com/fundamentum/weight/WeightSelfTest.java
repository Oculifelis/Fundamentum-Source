package com.fundamentum.weight;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.dodge.Dodges;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FVanilla;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The weight test, only in {@code gradlew runServer -Pweighttest} (world under run-weighttest). Checks item weights from
 * data (exact, tags, a second pack), the armour formula, container contents, that nothing changes while both parts are
 * off, the load steps and the dodge they shorten, the overload slowdown and that turning the parts off removes it.
 * Writes weighttest-result.txt and stops the server. Log lines start with WEIGHTTEST.
 */
public final class WeightSelfTest {
    private WeightSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final int Y = 200;
    private static boolean failed;
    private static int ticks;
    private static ServerPlayer heavy;
    private static ServerPlayer light;
    private static double heavyX, heavyZ, lightX, lightZ;

    public static void init() {
        if (System.getProperty("fundamentum.weighttest") == null) return;
        FLife.serverTick(WeightSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("WEIGHTTEST {}", line);
        else LOG.error("WEIGHTTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks == 5) server.overworld().setChunkForced(0, 0, true);
        try {
            if (ticks == 40) start(server);
            else if (ticks == 52) dodged();
            else if (ticks == 60) overload();
        } catch (Throwable t) {
            LOG.error("WEIGHTTEST threw", t);
            check(false, "threw " + t);
            ticks = 99;
        }
        if (ticks != 100) return;
        try {
            parts(false, false);
        } catch (RuntimeException ignored) { }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("WEIGHTTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("weighttest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("WEIGHTTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static ItemStack stack(String id, int n) {
        return new ItemStack(FVanilla.item(FId.of(id)), n);
    }

    private static double unit(String id) {
        return Weights.unitWeight(stack(id, 1));
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-4;
    }

    private static String f(double v) {
        return String.format("%.3f", v);
    }

    private static void parts(boolean load, boolean inv) {
        Weights.setConfig("equipment_load", load);
        Weights.setConfig("inventory_weight", inv);
    }

    private static void wear(ServerPlayer who, String material) {
        who.setItemSlot(EquipmentSlot.HEAD, stack("minecraft:" + material + "_helmet", 1));
        who.setItemSlot(EquipmentSlot.CHEST, stack("minecraft:" + material + "_chestplate", 1));
        who.setItemSlot(EquipmentSlot.LEGS, stack("minecraft:" + material + "_leggings", 1));
        who.setItemSlot(EquipmentSlot.FEET, stack("minecraft:" + material + "_boots", 1));
    }

    private static double speed(ServerPlayer p) {
        return p.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    private static void start(MinecraftServer server) {
        ServerLevel level = server.overworld();
        check(!Weights.equipmentLoad() && !Weights.inventoryWeight(), "both parts are off by default");

        // weights from data
        check(near(unit("minecraft:anvil"), 15), "an exact item weight (anvil " + f(unit("minecraft:anvil")) + ")");
        check(near(unit("minecraft:iron_ingot"), 0.5), "a tag weight (iron ingot " + f(unit("minecraft:iron_ingot")) + ")");
        check(near(unit("minecraft:flint"), 2), "a second pack adds an item (flint " + f(unit("minecraft:flint")) + ")");
        check(near(unit("minecraft:white_wool"), 0.5), "a second pack adds a tag (wool " + f(unit("minecraft:white_wool")) + ")");
        check(near(unit("minecraft:dirt"), 0.25), "a block with no entry uses default_block");
        check(near(unit("minecraft:stick"), 0.05), "an item with no entry uses default_item");
        check(near(Weights.stackWeight(stack("minecraft:anvil", 4)), 60), "a stack weighs its count times one");
        double chest = unit("minecraft:diamond_chestplate");
        check(near(chest, 8 * 1.5 + 2 * 2.0), "armour weighs its points and toughness (diamond chestplate " + f(chest) + ")");

        // container contents
        ItemStack box = WeightPort.testBox(List.of(stack("minecraft:anvil", 10)));
        check(near(Weights.stackWeight(box), 150.25), "a shulker box counts what it holds (" + f(Weights.stackWeight(box)) + ")");
        Weights.setConfig("count_containers", false);
        check(near(Weights.stackWeight(box), 0.25), "count_containers off counts only the box (" + f(Weights.stackWeight(box)) + ")");
        Weights.setConfig("count_containers", true);

        // both parts off: nothing changes
        heavy = FVanilla.fakePlayer(level, "weighttest_heavy");
        heavy.setPos(0.5, Y, 0.5);
        heavy.setYRot(0);
        wear(heavy, "diamond");
        heavy.getInventory().setItem(10, stack("minecraft:anvil", 64));
        double walk = speed(heavy);
        Weights.refresh(heavy);
        check(Weights.loadStep(heavy) == Weights.LoadStep.NONE, "off: no load step");
        check(Weights.overloadStep(heavy) == 0 && near(speed(heavy), walk), "off: walking speed unchanged");

        // equipment load
        parts(true, false);
        double load = Weights.load(heavy);
        check(near(load, 46), "full diamond armour weighs 46 (" + f(load) + ")");
        Weights.refresh(heavy);
        check(Weights.loadStep(heavy).name().equals("heavy"), "full diamond is a heavy load (" + Weights.loadStep(heavy).name() + ")");
        check(Weights.overloadStep(heavy) == 0 && near(speed(heavy), walk), "equipment load alone never slows walking");
        light = FVanilla.fakePlayer(level, "weighttest_light");
        light.setPos(8.5, Y, 0.5);
        light.setYRot(0);
        Weights.refresh(light);
        check(Weights.loadStep(light).name().equals("light"), "no armour is a light load");
        light.setItemSlot(EquipmentSlot.CHEST, stack("minecraft:iron_chestplate", 1));
        light.setItemSlot(EquipmentSlot.LEGS, stack("minecraft:iron_leggings", 1));
        Weights.refresh(light);
        check(Weights.loadStep(light).name().equals("medium"), "iron chestplate and leggings are a medium load (" + f(Weights.load(light)) + ")");
        light.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        light.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
        Weights.refresh(light);
        heavy.getAttribute(StatAttributes.LOAD_CAPACITY).setBaseValue(400);
        Weights.refresh(heavy);
        check(Weights.loadStep(heavy).name().equals("light"), "more load capacity makes the same armour light");
        heavy.getAttribute(StatAttributes.LOAD_CAPACITY).setBaseValue(40);
        Weights.refresh(heavy);

        // the step changes the dodge: a heavy dodge goes less far than a light one
        heavy.getAttribute(StatAttributes.DODGE_CHARGES).setBaseValue(1);
        light.getAttribute(StatAttributes.DODGE_CHARGES).setBaseValue(1);
        heavy.setOnGround(true);
        light.setOnGround(true);
        heavyX = heavy.getX();
        heavyZ = heavy.getZ();
        lightX = light.getX();
        lightZ = light.getZ();
        check(Dodges.tryDodge(heavy, 1, 0, false) && Dodges.tryDodge(light, 1, 0, false), "both players dodge");
    }

    private static void dodged() {
        double h = Math.hypot(heavy.getX() - heavyX, heavy.getZ() - heavyZ);
        double l = Math.hypot(light.getX() - lightX, light.getZ() - lightZ);
        check(l > 3 && h > 1 && h < l * 0.8, "a heavy dodge goes less far (" + f(h) + " against " + f(l) + ")");
    }

    private static void overload() {
        double walk = speed(heavy);
        double jump = WeightPort.jumpScale(heavy);
        parts(true, true);
        // 46 of armour and 960 of anvils against 400: past the last step
        Weights.refresh(heavy);
        check(Weights.overloadStep(heavy) == 3, "far past capacity is step 3 (" + Weights.overloadStep(heavy) + ", " + f(Weights.carriedFraction(heavy)) + ")");
        check(near(speed(heavy), walk * 0.55), "step 3 slows walking by 45% (" + f(speed(heavy)) + " of " + f(walk) + ")");
        check(near(WeightPort.jumpScale(heavy), jump * 0.5), "step 3 halves jumps (" + f(WeightPort.jumpScale(heavy)) + ")");
        check(Weights.loadStep(heavy).name().equals("heavy"), "both parts work together");
        heavy.getInventory().setItem(10, stack("minecraft:anvil", 26));
        Weights.refresh(heavy);
        // 46 + 390 = 436 of 400
        check(Weights.overloadStep(heavy) == 1 && near(speed(heavy), walk * 0.9), "just over capacity is step 1, 10% slower (" + f(Weights.carriedFraction(heavy)) + ")");
        heavy.getInventory().setItem(10, ItemStack.EMPTY);
        Weights.refresh(heavy);
        check(Weights.overloadStep(heavy) == 0 && near(speed(heavy), walk), "under capacity walks at full speed again");
        heavy.getInventory().setItem(10, stack("minecraft:anvil", 64));
        Weights.refresh(heavy);
        parts(false, false);
        Weights.refresh(heavy);
        check(Weights.overloadStep(heavy) == 0 && near(speed(heavy), walk) && near(WeightPort.jumpScale(heavy), jump),
            "turning both parts off removes the slowdown");
        check(Weights.loadStep(heavy) == Weights.LoadStep.NONE, "turning equipment load off ends the load step");
    }
}
