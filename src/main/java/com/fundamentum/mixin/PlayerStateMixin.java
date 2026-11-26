package com.fundamentum.mixin;

import com.fundamentum.port.FLife;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells {@link FLife#playerStateChanged} when a server player starts or stops sprinting, sneaking or swimming. */
@Mixin(Entity.class)
public abstract class PlayerStateMixin {
    @Inject(method = "setSprinting", at = @At("HEAD"))
    private void fundamentum$sprinting(boolean value, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer p && p.isSprinting() != value) FLife.firePlayerStateChanged(p);
    }

    @Inject(method = "setShiftKeyDown", at = @At("HEAD"))
    private void fundamentum$sneaking(boolean value, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer p && p.isShiftKeyDown() != value) FLife.firePlayerStateChanged(p);
    }

    @Inject(method = "setSwimming", at = @At("HEAD"))
    private void fundamentum$swimming(boolean value, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer p && p.isSwimming() != value) FLife.firePlayerStateChanged(p);
    }
}
