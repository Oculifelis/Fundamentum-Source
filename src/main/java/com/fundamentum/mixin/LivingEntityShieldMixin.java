package com.fundamentum.mixin;

import com.fundamentum.shield.Shields;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A Fundamentum shield decides whether it is raised and what it blocks (its delay, angles, types and bypass tag). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityShieldMixin {
    @Inject(method = "isDamageSourceBlocked", at = @At("HEAD"), cancellable = true)
    private void fundamentum$shieldBlocks(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        Boolean blocks = Shields.blocks((LivingEntity) (Object) this, source);
        if (blocks != null) {
            cir.setReturnValue(blocks);
        }
    }

    @Inject(method = "isBlocking", at = @At("HEAD"), cancellable = true)
    private void fundamentum$shieldRaised(CallbackInfoReturnable<Boolean> cir) {
        Boolean raised = Shields.isBlocking((LivingEntity) (Object) this);
        if (raised != null) {
            cir.setReturnValue(raised);
        }
    }
}
