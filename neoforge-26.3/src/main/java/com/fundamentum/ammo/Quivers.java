package com.fundamentum.ammo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jspecify.annotations.Nullable;

/**
 * Quiver items: anything worn in the {@code quiver} Curios slot whose contents Fundamentum searches for ammo. Fundamentum
 * ships no quiver of its own; other mods' items opt in (see {@link QuiverAccess}).
 */
public final class Quivers {
    /** Items in this tag are quivers using the standard container contents, 9 stacks. */
    public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("fundamentum", "quivers"));

    private static final Map<Item, QuiverAccess> REGISTERED = new ConcurrentHashMap<>();
    private static final QuiverAccess DEFAULT = container(9);

    /** Makes {@code item} a quiver read and written through {@code access}. */
    public static void register(Item item, QuiverAccess access) {
        REGISTERED.put(item, access);
    }

    /** Makes {@code item} a quiver of {@code size} stacks kept in the standard container contents. */
    public static void register(Item item, int size) {
        register(item, container(size));
    }

    /** How to reach the ammo in {@code stack}, or null when it is no quiver. */
    public static @Nullable QuiverAccess get(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        QuiverAccess access = REGISTERED.get(stack.getItem());
        if (access != null) {
            return access;
        }
        if (stack.getItem() instanceof QuiverItem q) {
            return q.quiverAccess(stack);
        }
        return stack.is(TAG) ? DEFAULT : null;
    }

    public static boolean isQuiver(ItemStack stack) {
        return get(stack) != null;
    }

    /** Puts as much of {@code ammo} into the quiver as fits; what is left stays in {@code ammo}. True if any went in. */
    public static boolean insert(ItemStack quiver, ItemStack ammo) {
        QuiverAccess access = get(quiver);
        if (access == null || ammo.isEmpty() || !access.accepts(quiver, ammo)) {
            return false;
        }
        List<ItemStack> contents = new ArrayList<>(access.contents(quiver));
        int before = ammo.getCount();
        for (int i = 0; i < contents.size() && !ammo.isEmpty(); i++) {
            ItemStack slot = contents.get(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, ammo)) {
                int move = Math.min(ammo.getCount(), slot.getMaxStackSize() - slot.getCount());
                if (move > 0) {
                    slot.grow(move);
                    ammo.shrink(move);
                }
            }
        }
        for (int i = 0; i < contents.size() && !ammo.isEmpty(); i++) {
            if (contents.get(i).isEmpty()) {
                contents.set(i, ammo.split(Math.min(ammo.getCount(), ammo.getMaxStackSize())));
            }
        }
        if (ammo.getCount() == before) {
            return false;
        }
        access.setContents(quiver, contents);
        return true;
    }

    /** The standard quiver storage: {@code size} stacks in the item's container contents component. */
    public static QuiverAccess container(int size) {
        return new QuiverAccess() {
            @Override
            public int size(ItemStack quiver) {
                return size;
            }

            @Override
            public List<ItemStack> contents(ItemStack quiver) {
                ItemContainerContents c = quiver.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
                NonNullList<ItemStack> out = NonNullList.withSize(size, ItemStack.EMPTY);
                c.copyInto(out);
                return new ArrayList<>(out);
            }

            @Override
            public void setContents(ItemStack quiver, List<ItemStack> contents) {
                quiver.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
            }
        };
    }

    private Quivers() {}
}
