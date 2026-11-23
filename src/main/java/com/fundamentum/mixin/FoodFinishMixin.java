package com.fundamentum.mixin;

import com.fundamentum.food.Food;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The food hook: {@link Food#beforeEat} before a player finishes using an item, {@link Food#afterEat} after. */
@Mixin(LivingEntity.class)
public abstract class FoodFinishMixin {
    @Unique
    private Object fundamentum$food;

    @Inject(method = "completeUsingItem", at = @At("HEAD"), cancellable = true)
    private void fundamentum$beforeEat(CallbackInfo ci) {
        Object r = Food.beforeEat((LivingEntity) (Object) this);
        if (r == Food.CANCELLED) {
            ci.cancel();
            return;
        }
        this.fundamentum$food = r;
    }

    @Inject(method = "completeUsingItem", at = @At("RETURN"))
    private void fundamentum$afterEat(CallbackInfo ci) {
        Object r = this.fundamentum$food;
        this.fundamentum$food = null;
        if (r != null) Food.afterEat((LivingEntity) (Object) this, r);
    }
}
