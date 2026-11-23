package com.fundamentum.ammo;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * How Fundamentum reads and writes the ammo inside a quiver item. Items opt in by implementing {@link QuiverItem}, by
 * {@link Quivers#register}, or by being in the item tag {@code #fundamentum:quivers} (which uses the standard container
 * contents, {@link Quivers#container}).
 */
public interface QuiverAccess {
    /** How many stacks the quiver holds. */
    int size(ItemStack quiver);

    /** Copies of the stacks inside, {@link #size} long, empty slots as empty stacks. */
    List<ItemStack> contents(ItemStack quiver);

    /** Stores new contents on the quiver stack. */
    void setContents(ItemStack quiver, List<ItemStack> contents);

    /** Whether {@code ammo} may go into the quiver (used by {@link Quivers#insert}). */
    default boolean accepts(ItemStack quiver, ItemStack ammo) {
        return true;
    }
}
