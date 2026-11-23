package com.fundamentum.thrown;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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
public class ThrownWeaponRenderer<T extends ThrownWeapon> extends EntityRenderer<T> {
    private final ItemRenderer items;
    private final String spinMode;
    private final float spinSpeed;

    public ThrownWeaponRenderer(EntityRendererProvider.Context context) {
        this(context, "", 36.0F);
    }

    /** {@code spin}: "flat", "tumble", "none", or "" to pick by whether the weapon returns. */
    public ThrownWeaponRenderer(EntityRendererProvider.Context context, String spin, float spinSpeed) {
        super(context);
        this.items = context.getItemRenderer();
        this.spinMode = spin;
        this.spinSpeed = spinSpeed;
    }

    @Override
    public void render(T entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int light) {
        float yRot;
        float xRot;
        Vec3 move = entity.position().subtract(entity.xo, entity.yo, entity.zo);
        if (move.lengthSqr() < 1.0E-6) {
            move = entity.getDeltaMovement();
        }
        if (!entity.isStuck() && move.lengthSqr() > 1.0E-6) {
            yRot = (float) (Mth.atan2(move.x, move.z) * Mth.RAD_TO_DEG);
            xRot = (float) (Mth.atan2(move.y, move.horizontalDistance()) * Mth.RAD_TO_DEG);
        } else {
            yRot = entity.getViewYRot(partialTicks);
            xRot = entity.getViewXRot(partialTicks);
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(entity.getItem().getItem());
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
        float spin = (entity.tickCount + partialTicks) * speed;
        poseStack.pushPose();
        switch (mode) {
            case "flat" -> {
                // laid flat and whirling about the vertical
                poseStack.mulPose(Axis.YP.rotationDegrees(spin));
                poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            }
            case "tumble" -> {
                // flight along +X, the blade in the vertical plane of the flight, tumbling forward about Z
                poseStack.mulPose(Axis.YP.rotationDegrees(yRot - 90.0F));
                poseStack.mulPose(Axis.ZP.rotationDegrees(xRot));
                poseStack.mulPose(Axis.ZP.rotationDegrees(-spin));
            }
            default -> {
                // head first along the flight: the sprite's top-right tip turned onto +X
                poseStack.mulPose(Axis.YP.rotationDegrees(yRot - 90.0F));
                poseStack.mulPose(Axis.ZP.rotationDegrees(xRot - 45.0F));
            }
        }
        poseStack.scale(0.85F, 0.85F, 0.85F);
        items.renderStatic(entity.getItem(), ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, poseStack, buffers,
            entity.level(), entity.getId());
        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffers, light);
    }

    @Override
    @SuppressWarnings("deprecation")
    public ResourceLocation getTextureLocation(T entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
