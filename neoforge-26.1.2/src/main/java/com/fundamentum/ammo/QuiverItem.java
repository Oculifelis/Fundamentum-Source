package com.fundamentum.ammo;

import net.minecraft.world.item.ItemStack;

/**
 * Implement on an item class to make it a quiver: Fundamentum then searches its contents for ammo when it is worn in the
 * {@code quiver} Curios slot. The default keeps 9 stacks in the standard container contents; override {@link #quiverAccess}
 * for anything else.
 */
public interface QuiverItem {
    default QuiverAccess quiverAccess(ItemStack stack) {
        return Quivers.container(9);
    }
}
