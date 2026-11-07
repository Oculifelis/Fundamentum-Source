package com.fundamentum.mixin;

import com.fundamentum.mount.Mounts;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A summoned mount Fundamentum steers (see {@link Mounts#steers}) is controlled by its rider. Subclasses that check a
 * stick or a saddle first (pig, strider, horses) fall through to this when their own check fails. Named twice (dev name and SRG name, unremapped) so Fundamentum needs no refmap.
 */
@Mixin(Mob.class)
public abstract class MobMixin {
    @Inject(method = {"getControllingPassenger", "m_6688_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void fundamentum$steeredMount(CallbackInfoReturnable<LivingEntity> cir) {
        Mob self = (Mob) (Object) this;
        if (self.getFirstPassenger() instanceof Player rider && Mounts.steers(self)) {
            cir.setReturnValue(rider);
        }
    }
}
