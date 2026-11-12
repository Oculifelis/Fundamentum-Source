package com.fundamentum.curios;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * The old home of the slot API, kept so mods built against it still work. Every call goes to
 * {@link com.fundamentum.slots.Slots}.
 *
 * @deprecated use {@link com.fundamentum.slots.Slots}.
 */
@Deprecated
public final class Slots {
    public static final String WINGS = com.fundamentum.slots.Slots.WINGS;
    public static final String MOUNT = com.fundamentum.slots.Slots.MOUNT;
    public static final String HOOK = com.fundamentum.slots.Slots.HOOK;
    public static final String SUMMON = com.fundamentum.slots.Slots.SUMMON;
    public static final String QUIVER = com.fundamentum.slots.Slots.QUIVER;

    public static boolean curiosLoaded() {
        return com.fundamentum.slots.Slots.curiosLoaded();
    }

    public static ItemStack equipped(LivingEntity entity, String slot) {
        return com.fundamentum.slots.Slots.equipped(entity, slot);
    }

    public static List<ItemStack> allEquipped(LivingEntity entity, String slot) {
        return com.fundamentum.slots.Slots.allEquipped(entity, slot);
    }

    public static ItemStack cosmetic(LivingEntity entity, String slot) {
        return com.fundamentum.slots.Slots.cosmetic(entity, slot);
    }

    public static List<ItemStack> stacks(LivingEntity entity, String slot) {
        return com.fundamentum.slots.Slots.stacks(entity, slot);
    }

    public static List<ItemStack> allWorn(LivingEntity entity) {
        return com.fundamentum.slots.Slots.allWorn(entity);
    }

    public static boolean equip(LivingEntity entity, String slot, int index, ItemStack stack) {
        return com.fundamentum.slots.Slots.set(entity, slot, index, stack);
    }

    public static void onChange(Consumer<LivingEntity> listener) {
        com.fundamentum.slots.Slots.onChange(listener);
    }

    private Slots() { }
}
