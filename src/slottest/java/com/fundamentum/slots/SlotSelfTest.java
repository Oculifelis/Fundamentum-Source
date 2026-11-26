package com.fundamentum.slots;

import com.fundamentum.cosmetic.CosmeticArmor;
import com.fundamentum.cosmetic.CosmeticArmorMenu;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FVanilla;
import com.fundamentum.wings.Wings;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The slot test, only in {@code gradlew runServer -Pslottest} (world under run-slottest). Checks the Curios slots
 * Fundamentum adds: slot sizes and the cosmetic twin, validity by {@code #curios:<slot>} tag (test datapack) and by
 * validator, set and get, tryEquip, firstMatching, shown, unequip, the equip and unequip events (cosmetic twins
 * included), that the cosmetic armour screen holds only the cosmetic armour, and wings (data stats, the flight cap, phases, take-off). Writes slottest-result.txt and stops
 * the server. Log lines start with SLOTTEST.
 */
public final class SlotSelfTest {
    private SlotSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final List<String> HEARD = new ArrayList<>();
    private static boolean failed;
    private static int ticks;
    private static int changes;

    public static void init() {
        if (System.getProperty("fundamentum.slottest") == null) return;
        FEvents.listen(Events.SlotEquipped.class, e -> HEARD.add("on " + e.slot() + " " + e.index() + " " + e.cosmetic() + " " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.stack().getItem())));
        FEvents.listen(Events.SlotUnequipped.class, e -> HEARD.add("off " + e.slot() + " " + e.index() + " " + e.cosmetic() + " " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.stack().getItem())));
        Slots.onChange(e -> changes++);
        Slots.validator(Slots.HOOK, s -> s.is(Items.LEAD));
        FLife.serverTick(SlotSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("SLOTTEST {}", line);
        else LOG.error("SLOTTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        if (++ticks != 40) return;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("SLOTTEST threw", t);
            check(false, "threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("SLOTTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("slottest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("SLOTTEST could not write the result file", e);
        }
        server.halt(false);
    }

    /** Curios looks for slot changes as the entity ticks; a fake player never ticks, so the test ticks it for Curios. */
    private static void settle(ServerPlayer p) {
        MinecraftForge.EVENT_BUS.post(new LivingEvent.LivingTickEvent(p));
    }

    /** A fake player never joins the level, so Curios has not laid out its slots yet; a real player's are laid out on joining. */
    private static final class CuriosLayout {
        static void lay(ServerPlayer p) {
            top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(p).ifPresent(inv -> {
                if (inv.getCurios().isEmpty()) inv.reset();
            });
        }
    }

    private static void run(MinecraftServer server) throws ReflectiveOperationException {
        ServerLevel level = server.overworld();

        ServerPlayer p = FVanilla.fakePlayer(level, "slottest");
        CuriosLayout.lay(p);
        settle(p);
        for (String s : List.of(Slots.WINGS, Slots.MOUNT, Slots.HOOK, Slots.SUMMON, Slots.QUIVER)) {
            check(Slots.size(p, s) == 1, "one " + s + " slot (" + Slots.size(p, s) + ")");
        }
        check(Slots.cosmeticSize(p, Slots.WINGS) == 1 && Slots.cosmeticSize(p, Slots.QUIVER) == 0, "wings have a cosmetic twin, the quiver none");

        // validity: the #curios:<slot> tag, a validator, and nothing else
        check(Slots.allows(Slots.QUIVER, new ItemStack(Items.ARROW)) && Slots.allows(Slots.WINGS, new ItemStack(Items.ELYTRA)), "tagged items allowed");
        check(Slots.allows(Slots.HOOK, new ItemStack(Items.LEAD)), "a validator allows an untagged item");
        check(!Slots.allows(Slots.QUIVER, new ItemStack(Items.DIRT)) && !Slots.allows(Slots.HOOK, new ItemStack(Items.ARROW)), "other items refused");
        check(Slots.tryEquip(p, Slots.QUIVER, new ItemStack(Items.DIRT)).isEmpty(), "tryEquip refuses what the slot does not allow");

        // set and get, tryEquip, firstMatching, events
        HEARD.clear();
        int before = changes;
        Optional<Slots.Worn> placed = Slots.tryEquip(p, Slots.QUIVER, new ItemStack(Items.ARROW, 1));
        settle(p);
        check(placed.isPresent() && placed.get().index() == 0 && Slots.equipped(p, Slots.QUIVER).is(Items.ARROW), "tryEquip puts an arrow in the quiver");
        check(Slots.tryEquip(p, Slots.QUIVER, new ItemStack(Items.SPECTRAL_ARROW)).isEmpty(), "tryEquip refuses a full slot");
        check(Slots.set(p, Slots.HOOK, 0, new ItemStack(Items.LEAD)) && Slots.get(p, Slots.HOOK, 0).is(Items.LEAD), "set and get");
        check(!Slots.set(p, Slots.HOOK, 1, new ItemStack(Items.LEAD)), "set past the slot count fails");
        settle(p);
        check(HEARD.contains("on quiver 0 false minecraft:arrow") && HEARD.contains("on hook 0 false minecraft:lead"), "slot_equipped heard " + HEARD);
        check(changes > before, "onChange listeners called");
        Optional<Slots.Worn> lead = Slots.firstMatching(p, s -> s.is(Items.LEAD));
        check(lead.isPresent() && lead.get().slot().equals(Slots.HOOK) && lead.get().index() == 0 && !lead.get().cosmetic(), "firstMatching finds the lead in the hook slot");
        check(Slots.firstMatching(p, Slots.QUIVER, s -> s.is(Items.LEAD)).isEmpty()
            && Slots.firstMatching(p, Slots.QUIVER, s -> s.is(Items.ARROW)).isPresent(), "firstMatching in one slot type");
        check(Slots.allWorn(p).size() >= 2, "allWorn lists both");

        // the cosmetic twin and the render hook
        HEARD.clear();
        check(Slots.set(p, Slots.WINGS, 0, new ItemStack(Items.ELYTRA)) && Slots.shown(p, Slots.WINGS).is(Items.ELYTRA), "shown is the worn item");
        check(Slots.setCosmetic(p, Slots.WINGS, 0, new ItemStack(Items.FEATHER)) && Slots.shown(p, Slots.WINGS).is(Items.FEATHER)
            && Slots.equipped(p, Slots.WINGS).is(Items.ELYTRA), "shown is the cosmetic twin when it holds something");
        settle(p);
        check(HEARD.contains("on wings 0 true minecraft:feather"), "slot_equipped heard for the cosmetic twin " + HEARD);
        check(!Slots.setCosmetic(p, Slots.QUIVER, 0, new ItemStack(Items.ARROW)), "no cosmetic twin for the quiver");
        check(Slots.firstMatching(p, s -> s.is(Items.FEATHER)).isEmpty(), "firstMatching skips cosmetic twins");

        // unequip
        HEARD.clear();
        ItemStack off = Slots.unequip(p, Slots.HOOK, 0);
        settle(p);
        check(off.is(Items.LEAD) && Slots.get(p, Slots.HOOK, 0).isEmpty(), "unequip returns the lead and empties the slot");
        check(HEARD.contains("off hook 0 false minecraft:lead"), "slot_unequipped heard " + HEARD);

        // the cosmetic armour screen: only the cosmetic armour slots, the accessory slots are in Curios' own screen
        Method forPlayer = CosmeticArmorMenu.class.getDeclaredMethod("forPlayer", int.class, net.minecraft.world.entity.player.Inventory.class);
        forPlayer.setAccessible(true);
        CosmeticArmorMenu menu = (CosmeticArmorMenu) forPlayer.invoke(null, 1, p.getInventory());
        check(menu.slots.size() == CosmeticArmor.SLOTS.length + 36, "the cosmetic armour screen adds no accessory slots (" + menu.slots.size() + ")");

        screenCosmetics(p);
        migration(level);
        wings(level);
    }

    /** A cosmetic twin changed by hand in Curios' screen (straight in Curios, as its slots do) posts the slot events. */
    private static void screenCosmetics(ServerPlayer p) {
        Slots.setCosmetic(p, Slots.WINGS, 0, ItemStack.EMPTY);
        check(!Slots.menuOpened(p, p.inventoryMenu), "only Curios' screen is watched");
        net.minecraft.world.inventory.AbstractContainerMenu screen = p.inventoryMenu;
        Slots.watch(p, screen);
        HEARD.clear();
        Slots.setQuietly(p, Slots.WINGS, 0, true, new ItemStack(Items.PAPER));
        p.getInventory().setItem(9, new ItemStack(Items.STONE));
        screen.broadcastChanges();
        check(HEARD.contains("on wings 0 true minecraft:paper") && HEARD.size() == 1, "a cosmetic twin changed in the screen posts slot_equipped " + HEARD);
        screen.broadcastChanges();
        check(HEARD.size() == 1, "nothing more posts while it stays the same " + HEARD);
        HEARD.clear();
        Slots.setCosmetic(p, Slots.WINGS, 0, new ItemStack(Items.FEATHER));
        p.getInventory().setItem(9, ItemStack.EMPTY);
        screen.broadcastChanges();
        check(HEARD.equals(List.of("off wings 0 true minecraft:paper", "on wings 0 true minecraft:feather")),
            "a change made through Slots while the screen is open posts once " + HEARD);
        HEARD.clear();
        Slots.setQuietly(p, Slots.WINGS, 0, true, ItemStack.EMPTY);
        Slots.menuClosed(p);
        check(HEARD.equals(List.of("off wings 0 true minecraft:feather")), "closing the screen posts what is left " + HEARD);
        HEARD.clear();
        Slots.setQuietly(p, Slots.WINGS, 0, true, new ItemStack(Items.PAPER));
        p.getInventory().setItem(9, new ItemStack(Items.STONE));
        screen.broadcastChanges();
        check(HEARD.isEmpty(), "a closed screen posts nothing " + HEARD);
        Slots.setQuietly(p, Slots.WINGS, 0, true, ItemStack.EMPTY);
        p.getInventory().setItem(9, ItemStack.EMPTY);
    }

    /** Old fundamentum:slots data, read from a saved player, moves into Curios once. */
    private static void migration(ServerLevel level) {
        ServerPlayer before = FVanilla.testPlayer(level, "slotmigrate");
        CompoundTag old = new CompoundTag();
        CompoundTag worn = new CompoundTag();
        worn.put("quiver", list(level, new ItemStack(Items.ARROW, 16)));
        worn.put("hook", list(level, new ItemStack(Items.LEAD)));
        worn.put("gone_slot", list(level, ItemStack.EMPTY, new ItemStack(Items.DIAMOND)));
        CompoundTag cosmetic = new CompoundTag();
        cosmetic.put("wings", list(level, new ItemStack(Items.FEATHER)));
        old.put("worn", worn);
        old.put("cosmetic", cosmetic);
        SlotMigration.Loadout loadout = FVanilla.parse(SlotMigration.Loadout.CODEC, old, level);
        check(loadout != null && loadout.worn().size() == 3 && !loadout.isEmpty(), "the old-format tag reads as old slot data");
        SlotMigration.OLD.set(before, loadout);
        CompoundTag saved = FVanilla.saveData(before);

        ServerPlayer p = FVanilla.testPlayer(level, "slotmigrate");
        FVanilla.loadInto(p, saved);
        CuriosLayout.lay(p);
        check(SlotMigration.OLD.has(p) && !SlotMigration.MIGRATED.get(p), "a saved player keeps the old data until it moves");
        Slots.set(p, Slots.HOOK, 0, new ItemStack(Items.STRING));
        SlotMigration.Result r = SlotMigration.migrate(p);
        check(Slots.get(p, Slots.QUIVER, 0).is(Items.ARROW) && Slots.get(p, Slots.QUIVER, 0).getCount() == 16
            && Slots.getCosmetic(p, Slots.WINGS, 0).is(Items.FEATHER) && Slots.get(p, Slots.HOOK, 0).is(Items.STRING),
            "old items move into free Curios slots");
        check(p.getInventory().countItem(Items.LEAD) == 1 && p.getInventory().countItem(Items.DIAMOND) == 1,
            "items whose slot is taken or gone go to the inventory");
        check(r.slotted() == 2 && r.given() == 2 && r.dropped() == 0, "the migration counts " + r);
        check(SlotMigration.MIGRATED.get(p) && !SlotMigration.OLD.has(p), "the marker is set and the old data removed");
        Slots.set(p, Slots.QUIVER, 0, ItemStack.EMPTY);
        check(SlotMigration.migrate(p).equals(SlotMigration.Result.NONE) && Slots.get(p, Slots.QUIVER, 0).isEmpty(), "it never runs twice");

        CompoundTag again = FVanilla.saveData(p);
        ServerPlayer later = FVanilla.testPlayer(level, "slotmigrate");
        FVanilla.loadInto(later, again);
        check(SlotMigration.MIGRATED.get(later) && !SlotMigration.OLD.has(later), "the marker survives saving");
        ServerPlayer fresh = FVanilla.testPlayer(level, "slotfresh");
        CuriosLayout.lay(fresh);
        check(SlotMigration.migrate(fresh).equals(SlotMigration.Result.NONE) && SlotMigration.MIGRATED.get(fresh), "a new player is just marked");
    }

    private static ListTag list(ServerLevel level, ItemStack... stacks) {
        ListTag l = new ListTag();
        for (ItemStack s : stacks) l.add(FVanilla.saveStack(s, level));
        return l;
    }

    /** Wings (worn in the wings slot): stats from data with clamps and defaults, the fx block, the flight-time cap, phases and the take-off event. */
    private static void wings(ServerLevel level) {
        ServerPlayer p = FVanilla.fakePlayer(level, "wingstest");
        CuriosLayout.lay(p);
        settle(p);
        check(Wings.worn(p).isEmpty() && Wings.stats(p) == null, "wings: nothing worn, no stats");
        Slots.set(p, Slots.WINGS, 0, new ItemStack(Items.FEATHER));
        check(Wings.stats(p) == Wings.Stats.DEFAULT, "wings: an item with no data gets the default stats");
        check(Wings.flightTicks(p, Wings.Stats.DEFAULT) == 40, "wings: default flight time 40 (" + Wings.flightTicks(p, Wings.Stats.DEFAULT) + ")");
        Slots.set(p, Slots.WINGS, 0, new ItemStack(Items.ELYTRA));
        Wings.Stats s = Wings.stats(p);
        check(s != null && s.flightTicks() == 200 && s.riseSpeed() == 0.5 && s.horizontalSpeed() == 1.0 && s.riseAcceleration() == 0.1,
            "wings: data stats, missing fields default (" + s + ")");
        check(s != null && s.glideFallSpeed() == Wings.MIN_GLIDE_FALL_SPEED, "wings: a glide slower than the floating kick is raised to the minimum");
        check(s != null && Wings.flightTicks(p, s) == Wings.MAX_FLIGHT_TICKS, "wings: flight time capped below the 80-tick kick");
        Wings.Fx fx = s == null ? null : s.fx();
        check(fx != null && fx.burst().equals(List.of("minecraft:cloud")) && fx.flap().size() == 2 && fx.burstCount() == 12
            && fx.flapSpeed() == 0.1F && fx.volume() == 0.5F && fx.flapSound() == null && fx.trail().isEmpty(),
            "wings: fx block reads one id or a list, defaults the rest, floors flap_speed (" + fx + ")");

        int[] takeOffs = {0};
        boolean[] refuse = {false};
        FEvents.listen(Events.WingsTakeOff.class, (e, call) -> {
            if (!e.player().getUUID().equals(p.getUUID())) return;
            takeOffs[0]++;
            if (refuse[0]) call.cancel();
        });
        check(Wings.phase(p) == Wings.FOLDED, "wings: folded at first");
        Wings.requestPhase(p, Wings.RISING);
        check(Wings.phase(p) == Wings.RISING && takeOffs[0] == 1, "wings: rising posts wings_take_off once");
        Wings.requestPhase(p, Wings.GLIDING);
        check(Wings.phase(p) == Wings.GLIDING && takeOffs[0] == 1, "wings: rising to gliding is not a new take-off");
        Wings.requestPhase(p, Wings.FOLDED);
        check(Wings.phase(p) == Wings.FOLDED, "wings: folding back");
        refuse[0] = true;
        Wings.requestPhase(p, Wings.RISING);
        check(Wings.phase(p) == Wings.FOLDED && takeOffs[0] == 2, "wings: a cancelled take-off stays folded");
        refuse[0] = false;
        Slots.set(p, Slots.WINGS, 0, ItemStack.EMPTY);
    }
}
