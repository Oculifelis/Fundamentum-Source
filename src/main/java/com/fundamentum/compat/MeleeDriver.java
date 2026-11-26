package com.fundamentum.compat;

import com.fundamentum.port.FId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;

/**
 * Decides who plays an item's left click, so two combat systems never swing at once. The order is fixed:
 * <ol>
 * <li>Better Combat, when it is loaded and the item's {@link BetterCombat} type gives it the left click;</li>
 * <li>registered drivers (a combat mod such as Fundamental Combat), highest priority first, ties by id;</li>
 * <li>nobody: the weapon type's own fallback runs.</li>
 * </ol>
 * A left-click handler asks {@link #owns(ItemStack, FId)} with its own id and steps aside when the answer is false.
 */
public final class MeleeDriver {
    /** The id {@link #driverOf} gives when Better Combat plays the click. */
    public static final FId BETTER_COMBAT = FId.of(BetterCombat.MOD_ID, "melee");

    /** A combat system that can play left clicks for the items it accepts. */
    public record Driver(FId id, int priority, Predicate<ItemStack> accepts) { }

    private static final List<Driver> DRIVERS = new CopyOnWriteArrayList<>();
    private static final Comparator<Driver> ORDER = Comparator.comparingInt(Driver::priority).reversed()
        .thenComparing(d -> d.id().toString());

    private MeleeDriver() { }

    /** Registers (or replaces) a driver. Call while the mod constructs. */
    public static void register(FId id, int priority, Predicate<ItemStack> accepts) {
        synchronized (DRIVERS) {
            DRIVERS.removeIf(d -> d.id().equals(id));
            DRIVERS.add(new Driver(id, priority, accepts));
            List<Driver> sorted = new ArrayList<>(DRIVERS);
            sorted.sort(ORDER);
            DRIVERS.clear();
            DRIVERS.addAll(sorted);
        }
    }

    public static boolean unregister(FId id) {
        synchronized (DRIVERS) {
            return DRIVERS.removeIf(d -> d.id().equals(id));
        }
    }

    public static List<Driver> drivers() {
        return List.copyOf(DRIVERS);
    }

    /** Who plays this item's left click: {@link #BETTER_COMBAT}, a registered driver's id, or null for the type fallback. */
    public static FId driverOf(ItemStack stack) {
        if (stack.isEmpty()) return null;
        if (BetterCombat.drivesLeftClick(stack)) return BETTER_COMBAT;
        for (Driver d : DRIVERS) {
            if (d.accepts().test(stack)) return d.id();
        }
        return null;
    }

    /** True when {@code driver} plays this item's left click. */
    public static boolean owns(ItemStack stack, FId driver) {
        return driver.equals(driverOf(stack));
    }

    /** True when no driver claims the item, so the weapon type's own left click runs. */
    public static boolean fallback(ItemStack stack) {
        return driverOf(stack) == null;
    }
}
