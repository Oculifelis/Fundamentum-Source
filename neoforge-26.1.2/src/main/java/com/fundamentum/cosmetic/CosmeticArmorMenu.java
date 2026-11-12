package com.fundamentum.cosmetic;

import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The cosmetic armour screen's menu: the four cosmetic slots (where the armour column is), then the player inventory.
 * The accessory slots are Curios slots, in Curios' own screen.
 */
public class CosmeticArmorMenu extends AbstractContainerMenu {
    private static final Identifier[] EMPTY_ICONS = {InventoryMenu.EMPTY_ARMOR_SLOT_HELMET, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
        InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS};
    private static final int COSMETIC_SLOTS = CosmeticArmor.SLOTS.length;

    private final Container cosmetics;

    /** Client side: the contents arrive from the server. */
    public CosmeticArmorMenu(int id, Inventory inventory) {
        this(id, inventory, new SimpleContainer(COSMETIC_SLOTS));
    }

    /** Server side: backed by the player's outfit, which is replaced (and synced) on every change. */
    static CosmeticArmorMenu forPlayer(int id, Inventory inventory) {
        Player player = inventory.player;
        SimpleContainer container = new SimpleContainer(CosmeticArmor.outfit(player).stacks().stream().map(ItemStack::copy).toArray(ItemStack[]::new)) {
            @Override
            public void setChanged() {
                super.setChanged();
                CosmeticArmor.set(player, CosmeticArmor.Outfit.of(this));
            }
        };
        return new CosmeticArmorMenu(id, inventory, container);
    }

    private CosmeticArmorMenu(int id, Inventory inventory, Container cosmetics) {
        super(CosmeticArmor.MENU.get(), id);
        this.cosmetics = cosmetics;
        Player player = inventory.player;
        for (int i = 0; i < COSMETIC_SLOTS; i++) {
            EquipmentSlot slot = CosmeticArmor.SLOTS[i];
            Identifier icon = EMPTY_ICONS[i];
            addSlot(new Slot(cosmetics, i, 8, 8 + i * 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return stack.canEquip(slot, player);
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }

                @Override
                public @Nullable Identifier getNoItemIcon() {
                    return icon;
                }
            });
        }
        addStandardInventorySlots(inventory, 8, 84);
    }

    /** How many slots come before the player inventory: the cosmetic armour. */
    static int ownSlots() {
        return COSMETIC_SLOTS;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int end = slots.size();
        int own = ownSlots();
        if (index < own) {
            if (!moveItemStackTo(stack, own, end, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            boolean moved = false;
            for (int i = 0; i < own && !moved; i++) {
                if (!slots.get(i).hasItem() && slots.get(i).mayPlace(stack)) {
                    moved = moveItemStackTo(stack, i, i + 1, false);
                }
            }
            // Otherwise between the main inventory and the hotbar, as vanilla does.
            int hotbar = end - 9;
            if (!moved && !(index < hotbar ? moveItemStackTo(stack, hotbar, end, false) : moveItemStackTo(stack, own, hotbar, false))) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return stack.getCount() == original.getCount() ? ItemStack.EMPTY : original;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive();
    }
}
