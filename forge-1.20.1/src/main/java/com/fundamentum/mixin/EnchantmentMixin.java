package com.fundamentum.mixin;

import com.fundamentum.item.WeaponEnchanting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the 1.21-style enchantable item tags add vanilla enchantments to Fundamentum's weapon types (see
 * {@link WeaponEnchanting}). Vanilla's overrides (Sharpness on axes, Unbreaking on damageable items) fall back to these
 * base methods, so hooking the base class covers them. canEnchant is named twice (dev name and SRG name, unremapped) so
 * Fundamentum needs no refmap; canApplyAtEnchantingTable is Forge's own and has no SRG name.
 */
@Mixin(Enchantment.class)
public abstract class EnchantmentMixin {
    @Inject(method = {"canEnchant", "m_6081_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void fundamentum$tagSupported(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (WeaponEnchanting.supports((Enchantment) (Object) this, stack)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "canApplyAtEnchantingTable", at = @At("HEAD"), cancellable = true, remap = false)
    private void fundamentum$tagPrimary(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (WeaponEnchanting.primary((Enchantment) (Object) this, stack)) {
            cir.setReturnValue(true);
        }
    }
}
