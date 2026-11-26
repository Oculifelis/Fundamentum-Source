package com.fundamentum.compat.discount.mixin;

import com.fundamentum.compat.discount.AspectDiscounts;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Thaumaturge's wand consumption modifier for one aspect: takes the player's per-aspect discount off it. The target is named by
 * string so this class never loads Thaumaturge's; {@link ThaumaturgeMixinPlugin} only applies it when Thaumaturge is there.
 * The floor is Thaumaturge's own (a wand never costs under a tenth).
 */
@Mixin(targets = "com.leclowndu93150.thaumaturge.content.wands.WandVisHelper", remap = false)
public abstract class WandCostMixin {
    private static final float MIN_FACTOR = 0.1F;

    @Inject(method = "getConsumptionModifier", at = @At("RETURN"), cancellable = true)
    private static void fundamentum$aspectDiscount(ItemStack wand, Player player, ResourceKey<?> aspect, boolean crafting, CallbackInfoReturnable<Float> cir) {
        if (player == null) return;
        float off = AspectDiscounts.percentFor(player, aspect.location()) / 100.0F;
        if (off > 0) cir.setReturnValue(Math.max(MIN_FACTOR, cir.getReturnValueF() - off));
    }
}
