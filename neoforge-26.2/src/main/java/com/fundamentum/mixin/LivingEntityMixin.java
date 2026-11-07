package com.fundamentum.mixin;

import com.fundamentum.mount.Mounts;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A mount Fundamentum steers moves by {@link Mounts#steerTravel} instead of its own ridden travel. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "travelRidden", at = @At("HEAD"), cancellable = true)
    private void fundamentum$steeredTravel(Player controller, Vec3 selfInput, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (Mounts.steers(self)) {
            Mounts.steerTravel(self, controller);
            ci.cancel();
        }
    }
}
