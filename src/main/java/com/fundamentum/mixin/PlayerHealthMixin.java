package com.fundamentum.mixin;

import com.fundamentum.port.FLife;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells {@link FLife#playerStateChanged} when a server player's health changes or they fall asleep or wake. */
@Mixin(LivingEntity.class)
public abstract class PlayerHealthMixin {
    @Inject(method = {"setHealth", "m_21153_"}, at = @At("HEAD"), remap = false)
    private void fundamentum$health(float value, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer p && p.getHealth() != value) FLife.firePlayerStateChanged(p);
    }

    @Inject(method = {"startSleeping", "m_5802_"}, at = @At("HEAD"), remap = false)
    private void fundamentum$sleep(BlockPos pos, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer p) FLife.firePlayerStateChanged(p);
    }

    @Inject(method = {"stopSleeping", "m_5796_"}, at = @At("HEAD"), remap = false)
    private void fundamentum$wake(CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer p) FLife.firePlayerStateChanged(p);
    }
}
