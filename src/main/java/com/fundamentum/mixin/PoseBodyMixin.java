package com.fundamentum.mixin;

import com.fundamentum.pose.ArmPoses;
import com.fundamentum.pose.PosePlayer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a playing pose clip's head, body and leg bones once the vanilla pose (walk, crouch, swing) is set. */
@Mixin(HumanoidModel.class)
public abstract class PoseBodyMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void fundamentum$poseBody(HumanoidRenderState state, CallbackInfo ci) {
        PosePlayer.Sample s = state.getRenderData(ArmPoses.SAMPLE);
        if (s != null && s.body()) {
            PosePlayer.applyBody((HumanoidModel<?>) (Object) this, s, state.mainArm);
        }
    }
}
