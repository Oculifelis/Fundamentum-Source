package com.fundamentum.curios;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

/** Only ever loaded when Curios is present. */
final class CuriosBridge {
    static ItemStack equipped(LivingEntity entity, String slot, boolean cosmetic) {
        var inventory = CuriosApi.getCuriosInventory(entity).resolve().orElse(null);
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
        var inventory = CuriosApi.getCuriosInventory(entity).resolve().orElse(null);
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

    static java.util.List<ItemStack> stacks(LivingEntity entity, String slot) {
        var inventory = CuriosApi.getCuriosInventory(entity).resolve().orElse(null);
        var handler = inventory == null ? null : inventory.getCurios().get(slot);
        if (handler == null) {
            return java.util.List.of();
        }
        var stacks = handler.getStacks();
        java.util.List<ItemStack> out = new java.util.ArrayList<>(stacks.getSlots());
        for (int i = 0; i < stacks.getSlots(); i++) {
            out.add(stacks.getStackInSlot(i));
        }
        return out;
    }

    static java.util.List<ItemStack> allWorn(LivingEntity entity) {
        var inventory = CuriosApi.getCuriosInventory(entity).resolve().orElse(null);
        if (inventory == null) {
            return java.util.List.of();
        }
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        for (var handler : inventory.getCurios().values()) {
            var stacks = handler.getStacks();
            for (int i = 0; i < stacks.getSlots(); i++) {
                ItemStack stack = stacks.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    out.add(stack);
                }
            }
        }
        return out;
    }

    static boolean set(LivingEntity entity, String slot, int index, ItemStack stack) {
        var inventory = CuriosApi.getCuriosInventory(entity).resolve().orElse(null);
        var handler = inventory == null ? null : inventory.getCurios().get(slot);
        if (handler == null || index < 0 || index >= handler.getStacks().getSlots()) {
            return false;
        }
        handler.getStacks().setStackInSlot(index, stack);
        return true;
    }

    static void onChange(java.util.function.Consumer<LivingEntity> listener) {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
            (top.theillusivec4.curios.api.event.CurioChangeEvent event) -> listener.accept(event.getEntity()));
    }

    private CuriosBridge() {}
}
