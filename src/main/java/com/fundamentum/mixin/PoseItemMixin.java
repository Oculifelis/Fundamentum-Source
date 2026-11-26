package com.fundamentum.mixin;

import com.fundamentum.pose.PoseClip;
import com.fundamentum.pose.PosePlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Moves the main-hand item by a playing pose clip's "item" bone, in the hand's own space (pixels and degrees). */
@Mixin(ItemInHandLayer.class)
public abstract class PoseItemMixin {
    @Inject(method = "renderArmWithItem", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", ordinal = 0, shift = At.Shift.AFTER))
    private void fundamentum$poseItem(LivingEntity entity, ItemStack stack, ItemDisplayContext ctx, HumanoidArm arm,
                                      PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (arm != entity.getMainArm()) {
            return;
        }
        float[] v = PosePlayer.item(PosePlayer.sample(entity, Minecraft.getInstance().getFrameTime()));
        if (v == null) {
            return;
        }
        pose.translate(v[PoseClip.PX] / 16.0F, v[PoseClip.PY] / 16.0F, v[PoseClip.PZ] / 16.0F);
        pose.mulPose(Axis.XP.rotationDegrees(v[PoseClip.RX]));
        pose.mulPose(Axis.YP.rotationDegrees(v[PoseClip.RY]));
        pose.mulPose(Axis.ZP.rotationDegrees(v[PoseClip.RZ]));
    }
}
