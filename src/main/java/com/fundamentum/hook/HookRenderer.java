package com.fundamentum.hook;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Draws a hook ({@link HookModel}) pointing away from its owner, and a chain of links back to the owner's hand. */
public class HookRenderer extends EntityRenderer<HookEntity> {
    public static final ResourceLocation TEXTURE = new ResourceLocation("fundamentum", "textures/entity/hook/hook.png");
    /** Distance between link centres, in blocks. */
    private static final float LINK_STEP = 3.0F / 16.0F;

    private final ModelPart hook;
    private final ModelPart link;

    public HookRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.hook = context.bakeLayer(HookModel.HOOK);
        this.link = context.bakeLayer(HookModel.LINK);
    }

    @Override
    public ResourceLocation getTextureLocation(HookEntity entity) {
        ResourceLocation texture = entity.def().texture();
        return texture == null ? TEXTURE : texture;
    }

    @Override
    public boolean shouldRender(HookEntity entity, Frustum culler, double camX, double camY, double camZ) {
        // The chain can be on screen while the hook itself is not.
        return entity.getOwner() instanceof Player;
    }

    @Override
    public void render(HookEntity entity, float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int light) {
        if (!(entity.getOwner() instanceof Player owner)) {
            return;
        }
        Vec3 toHand = HookEntity.anchor(owner, partialTicks).subtract(entity.getPosition(partialTicks));
        Vec3 out = toHand.reverse();
        double length = out.length();
        float yRot = (float) (Mth.atan2(out.x, out.z) * Mth.RAD_TO_DEG);
        float xRot = (float) (Mth.atan2(out.y, out.horizontalDistance()) * Mth.RAD_TO_DEG);
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutout(getTextureLocation(entity)));
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(yRot - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(xRot));
        hook.render(poseStack, buffer, light, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
        int links = (int) (length / LINK_STEP);
        for (int i = 1; i < links; i++) {
            Vec3 at = toHand.scale(i * LINK_STEP / length);
            poseStack.pushPose();
            poseStack.translate(at.x, at.y, at.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(yRot - 90.0F));
            poseStack.mulPose(Axis.ZP.rotationDegrees(xRot));
            if (i % 2 == 1) {
                poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            }
            link.render(poseStack, buffer, light, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
        super.render(entity, yaw, partialTicks, poseStack, buffers, light);
    }
}
