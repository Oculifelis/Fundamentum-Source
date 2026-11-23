package com.fundamentum.spear;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;

/** Draws a thrown spear as its item, turned to point along its flight (the item's tip is its top right corner). */
public class ThrownSpearRenderer extends EntityRenderer<ThrownSpear> {
    private final ItemRenderer itemRenderer;

    public ThrownSpearRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(ThrownSpear entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int light) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(entity.getViewYRot(partialTicks) - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(entity.getViewXRot(partialTicks) - 45.0F));
        poseStack.translate(-0.25F, -0.25F, 0.0F);
        poseStack.scale(1.25F, 1.25F, 1.25F);
        itemRenderer.renderStatic(entity.getItem(), ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY, poseStack, buffers,
            entity.level(), entity.getId());
        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffers, light);
    }

    @Override
    @SuppressWarnings("deprecation")
    public ResourceLocation getTextureLocation(ThrownSpear entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
