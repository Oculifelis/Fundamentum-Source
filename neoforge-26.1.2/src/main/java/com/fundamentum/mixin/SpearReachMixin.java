package com.fundamentum.mixin;

import com.fundamentum.spear.Spears;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A spear in {@code #fundamentum:spears} reaches further by {@code fundamentum:spear_reach} (see {@link Spears#reach}). */
@Mixin(LivingEntity.class)
public abstract class SpearReachMixin {
    @Inject(method = "getAttackRangeWith", at = @At("RETURN"), cancellable = true)
    private void fundamentum$spearReach(ItemStack weapon, CallbackInfoReturnable<AttackRange> cir) {
        AttackRange range = cir.getReturnValue();
        AttackRange reached = Spears.reach((LivingEntity) (Object) this, weapon, range);
        if (reached != range) {
            cir.setReturnValue(reached);
        }
    }
}
