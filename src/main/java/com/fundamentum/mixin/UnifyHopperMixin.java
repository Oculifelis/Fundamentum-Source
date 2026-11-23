package com.fundamentum.mixin;

import com.fundamentum.unify.Unify;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Items a hopper (or a hopper minecart, or a dropper) puts into a container become the kept item when unification replaces them. */
@Mixin(HopperBlockEntity.class)
public abstract class UnifyHopperMixin {
    @ModifyVariable(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
        at = @At("HEAD"), argsOnly = true)
    private static ItemStack fundamentum$unify(ItemStack stack) {
        return Unify.ENABLED.get() && Unify.CONTAINERS.get() ? Unify.convert(stack) : stack;
    }
}
