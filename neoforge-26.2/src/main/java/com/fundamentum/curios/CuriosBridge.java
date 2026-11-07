package com.fundamentum.curios;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

/** Only ever loaded when Curios is present. */
final class CuriosBridge {
    static ItemStack equipped(LivingEntity entity, String slot, boolean cosmetic) {
        var inventory = CuriosApi.getCuriosInventoryOrNull(entity);
        var handler = inventory == null ? null : inventory.getCurios().get(slot);
        if (handler == null) {
            return ItemStack.EMPTY;
        }
        var stacks = cosmetic ? handler.getCosmeticStacks() : handler.getStacks();
        for (int i = 0; i < stacks.getSlots(); i++) {
            ItemStack stack = stacks.getStackInSlot(i);
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    static java.util.List<ItemStack> all(LivingEntity entity, String slot) {
        var inventory = CuriosApi.getCuriosInventoryOrNull(entity);
        var handler = inventory == null ? null : inventory.getCurios().get(slot);
        if (handler == null) {
            return java.util.List.of();
        }
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        var stacks = handler.getStacks();
        for (int i = 0; i < stacks.getSlots(); i++) {
            ItemStack stack = stacks.getStackInSlot(i);
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    private CuriosBridge() {}
}
