package com.fundamentum.mixin;

import com.fundamentum.unify.Unify;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Items inserted into a mod's item storage (NeoForge's ItemStackHandler) become the kept item when unification replaces them. */
@Mixin(ItemStackHandler.class)
public abstract class UnifyItemHandlerMixin {
    @ModifyVariable(method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), argsOnly = true)
    private ItemStack fundamentum$unify(ItemStack stack) {
        return Unify.ENABLED.get() && Unify.CONTAINERS.get() ? Unify.convert(stack) : stack;
    }
}
