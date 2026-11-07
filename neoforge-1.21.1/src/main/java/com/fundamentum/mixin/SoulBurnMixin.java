package com.fundamentum.mixin;

import com.fundamentum.soulfire.SoulFlames;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** While soul burning, the once-a-second burn hit is {@code fundamentum:on_soul_fire} for soulFireBurnDamage instead of on_fire for 1. */
@Mixin(Entity.class)
public abstract class SoulBurnMixin {
    @WrapOperation(method = "baseTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean fundamentum$soulBurn(Entity self, DamageSource source, float amount, Operation<Boolean> original) {
        if (source.is(DamageTypes.ON_FIRE) && SoulFlames.isSoulBurning(self)) {
            return original.call(self, SoulFlames.burnSource(self.level()), SoulFlames.burnDamage());
        }
        return original.call(self, source, amount);
    }
}
