package com.fundamentum.mixin;

import com.fundamentum.pose.ArmPoses;
import com.fundamentum.pose.PoseClip;
import com.fundamentum.pose.PosePlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Moves the main-hand item by a playing pose clip's "item" bone, in the hand's own space (pixels and degrees). */
@Mixin(ItemInHandLayer.class)
public abstract class PoseItemMixin {
    @Inject(method = "submitArmWithItem", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", ordinal = 0, shift = At.Shift.AFTER))
    private void fundamentum$poseItem(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack, HumanoidArm arm,
                                      PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (arm != state.mainArm) {
            return;
        }
        float[] v = PosePlayer.item(state.getRenderData(ArmPoses.SAMPLE));
        if (v == null) {
            return;
        }
        pose.translate(v[PoseClip.PX] / 16.0F, v[PoseClip.PY] / 16.0F, v[PoseClip.PZ] / 16.0F);
        pose.rotate(Axis.XP.rotationDegrees(v[PoseClip.RX]));
        pose.rotate(Axis.YP.rotationDegrees(v[PoseClip.RY]));
        pose.rotate(Axis.ZP.rotationDegrees(v[PoseClip.RZ]));
    }
}
