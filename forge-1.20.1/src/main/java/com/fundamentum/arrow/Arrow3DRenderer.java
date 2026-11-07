package com.fundamentum.arrow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.function.Function;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;

/**
 * Draws an arrow with {@link Arrow3DModel} instead of vanilla's flat crossed planes. Mods use it for their own arrows
 * with a texture in the model's layout: {@code event.registerEntityRenderer(MY_ARROW, ctx -> Arrow3DRenderer.of(ctx, MY_TEXTURE))}.
 */
public class Arrow3DRenderer<T extends AbstractArrow> extends EntityRenderer<T> {
    public static final ResourceLocation ARROW = texture("arrow");
    public static final ResourceLocation TIPPED = texture("arrow_tipped");
    public static final ResourceLocation SPECTRAL = texture("arrow_spectral");

    private final ModelPart model;
    private final Function<T, ResourceLocation> texture;

    public Arrow3DRenderer(EntityRendererProvider.Context context, Function<T, ResourceLocation> texture) {
        super(context);
        this.model = context.bakeLayer(Arrow3DModel.LAYER);
        this.texture = texture;
    }

    /** A renderer for an arrow with one texture. */
    public static <T extends AbstractArrow> Arrow3DRenderer<T> of(EntityRendererProvider.Context context, ResourceLocation texture) {
        return new Arrow3DRenderer<>(context, arrow -> texture);
    }

    /** Vanilla's arrow: the tipped texture when it carries a potion. */
    static Arrow3DRenderer<Arrow> tippable(EntityRendererProvider.Context context) {
        return new Arrow3DRenderer<>(context, arrow -> arrow.getColor() > 0 ? TIPPED : ARROW);
    }

    private static ResourceLocation texture(String name) {
        return new ResourceLocation("fundamentum", "textures/entity/arrow/" + name + ".png");
    }

    @Override
    public ResourceLocation getTextureLocation(T arrow) {
        return texture.apply(arrow);
    }

    @Override
    public void render(T arrow, float yaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffers, int light) {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTicks, arrow.yRotO, arrow.getYRot()) - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTicks, arrow.xRotO, arrow.getXRot())));
        float shake = arrow.shakeTime - partialTicks;
        if (shake > 0.0F) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(-Mth.sin(shake * 3.0F) * shake));
        }
        model.render(poseStack, buffers.getBuffer(RenderType.entityCutout(getTextureLocation(arrow))), light, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
        super.render(arrow, yaw, partialTicks, poseStack, buffers, light);
    }
}
