package com.fundamentum.mixin;

import com.fundamentum.pose.PosePlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a playing pose clip's head, body and leg bones once the vanilla pose (walk, crouch, swing) is set. */
@Mixin(HumanoidModel.class)
public abstract class PoseBodyMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void fundamentum$poseBody(LivingEntity entity, float limbSwing, float limbSwingAmount, float age, float yaw, float pitch,
                                      CallbackInfo ci) {
        PosePlayer.Sample s = PosePlayer.sample(entity, Minecraft.getInstance().getFrameTime());
        if (s != null && s.body()) {
            PosePlayer.applyBody((HumanoidModel<?>) (Object) this, s, entity.getMainArm());
        }
    }
}
