package com.fundamentum.arrow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.ArrowRenderState;
import net.minecraft.client.renderer.entity.state.TippableArrowRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;

/**
 * Draws an arrow with {@link Arrow3DModel} instead of vanilla's flat crossed planes. Mods use it for their own arrows
 * with a texture in the model's layout: {@code event.registerEntityRenderer(MY_ARROW, ctx -> Arrow3DRenderer.of(ctx, MY_TEXTURE))}.
 */
public class Arrow3DRenderer<T extends AbstractArrow, S extends ArrowRenderState> extends EntityRenderer<T, S> {
    public static final Identifier ARROW = texture("arrow");
    public static final Identifier TIPPED = texture("arrow_tipped");
    public static final Identifier SPECTRAL = texture("arrow_spectral");

    private final Arrow3DModel model;
    private final Supplier<S> state;
    private final Function<S, Identifier> texture;

    public Arrow3DRenderer(EntityRendererProvider.Context context, Supplier<S> state, Function<S, Identifier> texture) {
        super(context);
        this.model = new Arrow3DModel(context.bakeLayer(Arrow3DModel.LAYER));
        this.state = state;
        this.texture = texture;
    }

    /** A renderer for an arrow with one texture. */
    public static <T extends AbstractArrow> Arrow3DRenderer<T, ArrowRenderState> of(EntityRendererProvider.Context context, Identifier texture) {
        return new Arrow3DRenderer<>(context, ArrowRenderState::new, s -> texture);
    }

    /** Vanilla's arrow: the tipped texture when it carries a potion. */
    static Arrow3DRenderer<Arrow, TippableArrowRenderState> tippable(EntityRendererProvider.Context context) {
        return new Arrow3DRenderer<>(context, TippableArrowRenderState::new, s -> s.isTipped ? TIPPED : ARROW) {
            @Override
            public void extractRenderState(Arrow entity, TippableArrowRenderState state, float partialTicks) {
                super.extractRenderState(entity, state, partialTicks);
                state.isTipped = entity.getColor() > 0;
            }
        };
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath("fundamentum", "textures/entity/arrow/" + name + ".png");
    }

    @Override
    public S createRenderState() {
        return state.get();
    }

    @Override
    public void extractRenderState(T entity, S state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.xRot = entity.getXRot(partialTicks);
        state.yRot = entity.getYRot(partialTicks);
        state.shake = entity.shakeTime - partialTicks;
    }

    @Override
    public void submit(S state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.rotate(Axis.YP.rotationDegrees(state.yRot - 90.0F));
        poseStack.rotate(Axis.ZP.rotationDegrees(state.xRot));
        collector.submitModel(model, state, poseStack, texture.apply(state), state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }
}
