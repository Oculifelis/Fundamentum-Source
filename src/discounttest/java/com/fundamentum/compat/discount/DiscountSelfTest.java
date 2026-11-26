package com.fundamentum.compat.discount;

import com.fundamentum.port.FLife;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.util.FakePlayerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

/**
 * The aspect discount test, only in {@code gradlew runServer -Pdiscounttest} (world under run-discounttest). Checks the item
 * table (direct and by tag), the per-stack value winning over it, sums over armour and Curios on a fake player, the floor
 * at 0, and, when Thaumaturge is loaded, that the wand cost hook was applied. Writes discounttest-result.txt and stops the
 * server. Log lines start with DISCOUNTTEST.
 */
public final class DiscountSelfTest {
    private DiscountSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final ResourceLocation IGNIS = new ResourceLocation("thaumaturge", "ignis");
    private static final ResourceLocation PERDITIO = new ResourceLocation("thaumaturge", "perditio");
    private static final ResourceLocation AER = new ResourceLocation("thaumaturge", "aer");
    private static boolean failed;
    private static int ticks;

    public static void init() {
        if (System.getProperty("fundamentum.discounttest") == null) return;
        FLife.serverTick(DiscountSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("DISCOUNTTEST {}", line);
        else LOG.error("DISCOUNTTEST {}", line);
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1.0E-4F;
    }

    private static void tick(MinecraftServer server) {
        if (++ticks != 40) return;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("DISCOUNTTEST threw", t);
            check(false, "threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("DISCOUNTTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("discounttest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("DISCOUNTTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static ItemStack withOwn(ItemStack stack, Map<ResourceLocation, Float> aspects) {
        AspectDiscounts.set(stack, new AspectDiscounts.Discount(aspects));
        return stack;
    }

    private static ICuriosItemHandler curiosOf(Player player) {
        ICuriosItemHandler inv = CuriosApi.getCuriosInventory(player).resolve().orElse(null);
        if (inv != null && inv.getCurios().isEmpty()) inv.reset();
        return inv;
    }

    private static void run(MinecraftServer server) throws Exception {
        ItemStack helmet = new ItemStack(Items.GOLDEN_HELMET);
        float v = AspectDiscounts.percentOf(helmet, IGNIS);
        check(near(v, 3), "item table, direct item: golden helmet ignis 3 (got " + v + ")");
        v = AspectDiscounts.percentOf(helmet, PERDITIO);
        check(near(v, 7), "item table, second aspect: golden helmet perditio 7 (got " + v + ")");
        v = AspectDiscounts.percentOf(helmet, AER);
        check(near(v, 0), "aspect not named: aer 0 (got " + v + ")");
        ItemStack boots = new ItemStack(Items.GOLDEN_BOOTS);
        v = AspectDiscounts.percentOf(boots, IGNIS);
        check(near(v, 5), "item table, by tag: golden boots ignis 5 (got " + v + ")");
        v = AspectDiscounts.percentOf(new ItemStack(Items.IRON_HELMET), IGNIS);
        check(near(v, 0), "item with no entry: iron helmet 0 (got " + v + ")");
        ItemStack own = withOwn(new ItemStack(Items.GOLDEN_HELMET), Map.of(IGNIS, 20F));
        v = AspectDiscounts.percentOf(own, IGNIS);
        check(near(v, 20), "stack value wins over the item table: 20 (got " + v + ")");
        v = AspectDiscounts.percentOf(own, PERDITIO);
        check(near(v, 0), "stack value replaces the whole entry: perditio 0 (got " + v + ")");
        check(near(AspectDiscounts.percentOf(ItemStack.EMPTY, IGNIS), 0), "empty stack 0");

        ServerLevel level = server.overworld();
        Player player = FakePlayerFactory.getMinecraft(level);
        check(near(AspectDiscounts.percentFor(null, IGNIS), 0), "no entity 0");
        player.setItemSlot(EquipmentSlot.HEAD, helmet.copy());
        player.setItemSlot(EquipmentSlot.FEET, boots.copy());
        v = AspectDiscounts.percentFor(player, IGNIS);
        check(near(v, 8), "armour adds up: helmet 3 + boots 5 = 8 (got " + v + ")");
        player.setItemSlot(EquipmentSlot.MAINHAND, own.copy());
        v = AspectDiscounts.percentFor(player, IGNIS);
        check(near(v, 8), "held items do not count (got " + v + ")");
        player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);

        ICuriosItemHandler curios = curiosOf(player);
        ICurioStacksHandler slot = null;
        if (curios != null) {
            for (ICurioStacksHandler handler : curios.getCurios().values()) {
                if (handler.getStacks().getSlots() > 0) {
                    slot = handler;
                    break;
                }
            }
        }
        check(slot != null, "the fake player has a Curios slot");
        if (slot != null) {
            slot.getStacks().setStackInSlot(0, withOwn(new ItemStack(Items.GOLD_NUGGET), Map.of(IGNIS, 10F)));
            v = AspectDiscounts.percentFor(player, IGNIS);
            check(near(v, 18), "Curios count: 8 + ring 10 = 18 (got " + v + ")");
            slot.getStacks().setStackInSlot(0, ItemStack.EMPTY);
        }

        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
        v = AspectDiscounts.percentFor(player, IGNIS);
        check(near(v, 0), "never under 0: 8 - 50 floors at 0 (got " + v + ")");
        for (EquipmentSlot s : EquipmentSlot.values()) player.setItemSlot(s, ItemStack.EMPTY);

        if (ModList.get().isLoaded("thaumaturge")) {
            boolean hooked = false;
            Class<?> helper = Class.forName("com.leclowndu93150.thaumaturge.content.wands.WandVisHelper");
            for (Method m : helper.getDeclaredMethods()) {
                if (m.getName().contains("aspectDiscount")) hooked = true;
            }
            check(hooked, "Thaumaturge loaded: wand cost hook applied");
        } else {
            LINES.add("INFO Thaumaturge not loaded, so no wand cost hook");
            LOG.info("DISCOUNTTEST INFO Thaumaturge not loaded, so no wand cost hook");
        }
    }
}
