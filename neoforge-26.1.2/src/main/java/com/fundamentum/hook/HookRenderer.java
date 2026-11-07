package com.fundamentum.hook;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Draws a hook ({@link HookModel}) pointing away from its owner, and a chain of links back to the owner's hand. */
public class HookRenderer extends EntityRenderer<HookEntity, HookRenderer.State> {
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("fundamentum", "textures/entity/hook/hook.png");
    /** Distance between link centres, in blocks. */
    private static final float LINK_STEP = 3.0F / 16.0F;

    private final HookModel hook;
    private final HookModel link;

    public HookRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.hook = new HookModel(context.bakeLayer(HookModel.HOOK));
        this.link = new HookModel(context.bakeLayer(HookModel.LINK));
    }

    public static class State extends EntityRenderState {
        /** From the hook to its owner's hand. */
        public Vec3 toHand = Vec3.ZERO;
        public Identifier texture = TEXTURE;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public boolean shouldRender(HookEntity entity, Frustum culler, double camX, double camY, double camZ) {
        // The chain can be on screen while the hook itself is not.
        return entity.getOwner() instanceof Player;
    }

    @Override
    public void extractRenderState(HookEntity entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        Identifier texture = entity.def().texture();
        state.texture = texture == null ? TEXTURE : texture;
        state.toHand = entity.getOwner() instanceof Player owner
            ? HookEntity.anchor(owner, partialTicks).subtract(entity.getPosition(partialTicks)) : Vec3.ZERO;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        Vec3 out = state.toHand.reverse();
        double length = out.length();
        float yRot = (float) (Mth.atan2(out.x, out.z) * Mth.RAD_TO_DEG);
        float xRot = (float) (Mth.atan2(out.y, out.horizontalDistance()) * Mth.RAD_TO_DEG);
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(yRot - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(xRot));
        collector.submitModel(hook, state, poseStack, state.texture, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        poseStack.popPose();
        int links = (int) (length / LINK_STEP);
        for (int i = 1; i < links; i++) {
            Vec3 at = state.toHand.scale(i * LINK_STEP / length);
            poseStack.pushPose();
            poseStack.translate(at.x, at.y, at.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(yRot - 90.0F));
            poseStack.mulPose(Axis.ZP.rotationDegrees(xRot));
            if (i % 2 == 1) {
                poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            }
            collector.submitModel(link, state, poseStack, state.texture, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
            poseStack.popPose();
        }
        super.submit(state, poseStack, collector, camera);
    }
}
