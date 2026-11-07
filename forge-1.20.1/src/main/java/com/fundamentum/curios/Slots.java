package com.fundamentum.curios;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * Fundamentum's shared Curios slots, defined as data in {@code data/fundamentum/curios/}: {@link #WINGS} (with a
 * cosmetic twin), {@link #MOUNT}, {@link #HOOK} and {@link #SUMMON} (one slot to start; gear adds more through Curios' slot
 * modifiers), all on players. An item goes in a slot when it is in the item tag
 * {@code #curios:<slot>} (e.g. {@code #curios:wings}). Curios is optional: without it every slot reads as empty.
 */
public final class Slots {
    public static final String WINGS = "wings";
    public static final String MOUNT = "mount";
    public static final String HOOK = "hook";
    public static final String SUMMON = "summon";

    private static final boolean CURIOS = ModList.get().isLoaded("curios");

    public static boolean curiosLoaded() {
        return CURIOS;
    }

    /** The first item worn in a slot (not its cosmetic twin), or empty. */
    public static ItemStack equipped(LivingEntity entity, String slot) {
        return CURIOS ? CuriosBridge.equipped(entity, slot, false) : ItemStack.EMPTY;
    }

    /** The first cosmetic item in a slot's cosmetic twin, or empty; what to draw instead of {@link #equipped}. */
    public static ItemStack cosmetic(LivingEntity entity, String slot) {
        return CURIOS ? CuriosBridge.equipped(entity, slot, true) : ItemStack.EMPTY;
    }

    /** Every item worn in a slot (all of its slots, not the cosmetic twins). */
    public static java.util.List<ItemStack> allEquipped(LivingEntity entity, String slot) {
        return CURIOS ? CuriosBridge.all(entity, slot) : java.util.List.of();
    }

    private Slots() {}
}
