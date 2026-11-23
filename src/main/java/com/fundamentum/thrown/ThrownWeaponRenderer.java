package com.fundamentum.thrown;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a thrown weapon as its item model. {@code "spin"} in the item's thrown data picks how:
 * {@code "flat"} whirls it level like a disc or chakram (the default for a returning weapon), {@code "tumble"} turns it
 * end over end in the plane of its flight like a thrown axe, {@code "none"} points it head first along its flight like
 * a javelin or knife (the default otherwise; the item sprite's tip is taken to be its top right, as for vanilla tools).
 * {@code "spin_speed"} is degrees a tick (default 36). A weapon stuck in a block is drawn head first and still. For a
 * mod's own entity the constructor sets the defaults: {@code ctx -> new ThrownWeaponRenderer<>(ctx, "tumble", 42)}.
 */
public class ThrownWeaponRenderer<T extends ThrownWeapon> extends EntityRenderer<T, ThrownWeaponRenderer.State> {
    private final ItemModelResolver items;
    private final String spinMode;
    private final float spinSpeed;

    public ThrownWeaponRenderer(EntityRendererProvider.Context context) {
        this(context, "", 36.0F);
    }

    /** {@code spin}: "flat", "tumble", "none", or "" to pick by whether the weapon returns. */
    public ThrownWeaponRenderer(EntityRendererProvider.Context context, String spin, float spinSpeed) {
        super(context);
        this.items = context.getItemModelResolver();
        this.spinMode = spin;
        this.spinSpeed = spinSpeed;
    }

    public static final class State extends ThrownItemRenderState {
        public float yRot;
        public float xRot;
        public float spin;
        /** "flat", "tumble" or "none". */
        public String mode = "none";
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(T entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        items.updateForNonLiving(state.item, entity.getItem(), ItemDisplayContext.FIXED, entity);
        Vec3 move = entity.position().subtract(entity.xo, entity.yo, entity.zo);
        if (move.lengthSqr() < 1.0E-6) {
            move = entity.getDeltaMovement();
        }
        if (!entity.isStuck() && move.lengthSqr() > 1.0E-6) {
            state.yRot = (float) (Mth.atan2(move.x, move.z) * Mth.RAD_TO_DEG);
            state.xRot = (float) (Mth.atan2(move.y, move.horizontalDistance()) * Mth.RAD_TO_DEG);
        } else {
            state.yRot = entity.getYRot(partialTicks);
            state.xRot = entity.getXRot(partialTicks);
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(entity.getItem().getItem());
        String mode = spinMode;
        float speed = spinSpeed;
        JsonObject data = ThrownWeapons.data(id);
        if (data != null) {
            if (data.has("spin")) {
                mode = data.get("spin").getAsString();
            }
            if (data.has("spin_speed")) {
                speed = data.get("spin_speed").getAsFloat();
            }
        }
        if (mode.isEmpty()) {
            mode = ThrownWeapons.base(id).returning() ? "flat" : "none";
        }
        if (entity.isStuck()) {
            mode = "none";
        }
        state.mode = mode;
        state.spin = (entity.tickCount + partialTicks) * speed;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        switch (state.mode) {
            case "flat" -> {
                // laid flat and whirling about the vertical
                poseStack.rotate(Axis.YP.rotationDegrees(state.spin));
                poseStack.rotate(Axis.XP.rotationDegrees(90.0F));
            }
            case "tumble" -> {
                // flight along +X, the blade in the vertical plane of the flight, tumbling forward about Z
                poseStack.rotate(Axis.YP.rotationDegrees(state.yRot - 90.0F));
                poseStack.rotate(Axis.ZP.rotationDegrees(state.xRot));
                poseStack.rotate(Axis.ZP.rotationDegrees(-state.spin));
            }
            default -> {
                // head first along the flight: the sprite's top-right tip turned onto +X
                poseStack.rotate(Axis.YP.rotationDegrees(state.yRot - 90.0F));
                poseStack.rotate(Axis.ZP.rotationDegrees(state.xRot - 45.0F));
            }
        }
        poseStack.scale(0.85F, 0.85F, 0.85F);
        state.item.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }
}
