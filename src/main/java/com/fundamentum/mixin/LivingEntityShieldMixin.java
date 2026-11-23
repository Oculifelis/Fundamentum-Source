package com.fundamentum.mixin;

import com.fundamentum.shield.Shields;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Fundamentum shield decides whether it is raised and what it blocks (its delay, angles, types and bypass tag).
 * Named twice (dev name and SRG name, unremapped) so Fundamentum needs no refmap.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityShieldMixin {
    @Inject(method = {"isDamageSourceBlocked", "m_21275_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void fundamentum$shieldBlocks(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        Boolean blocks = Shields.blocks((LivingEntity) (Object) this, source);
        if (blocks != null) {
            cir.setReturnValue(blocks);
        }
    }

    @Inject(method = {"isBlocking", "m_21254_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void fundamentum$shieldRaised(CallbackInfoReturnable<Boolean> cir) {
        Boolean raised = Shields.isBlocking((LivingEntity) (Object) this);
        if (raised != null) {
            cir.setReturnValue(raised);
        }
    }
}
