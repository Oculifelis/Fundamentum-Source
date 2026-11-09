package com.fundamentum.relic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Measures a likeness for its relic ({@link RelicRenderer}): collects the corners of every model part a boss's renderer submits
 * and draws nothing, so the relic fits the model as it is drawn rather than its hitbox.
 */
public final class RelicMeasure implements SubmitNodeCollector {
    /** min x, y, z, max x, y, z; min > max while nothing was seen. */
    final float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

    boolean empty() {
        return b[0] > b[3];
    }

    /** Takes in a box (blocks) as if it had been drawn. */
    void box(float x0, float y0, float z0, float x1, float y1, float z1) {
        b[0] = Math.min(b[0], x0);
        b[1] = Math.min(b[1], y0);
        b[2] = Math.min(b[2], z0);
        b[3] = Math.max(b[3], x1);
        b[4] = Math.max(b[4], y1);
        b[5] = Math.max(b[5], z1);
    }

    private void add(Vector3fc v) {
        b[0] = Math.min(b[0], v.x());
        b[1] = Math.min(b[1], v.y());
        b[2] = Math.min(b[2], v.z());
        b[3] = Math.max(b[3], v.x());
        b[4] = Math.max(b[4], v.y());
        b[5] = Math.max(b[5], v.z());
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this;
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack pose, RenderType type, int light, int overlay, int tint,
                                @Nullable UvMapping uv, int outline) {
        model.setupAnim(state);
        model.root().getExtentsForGui(pose, this::add);
    }

    @Override
    public void submitCustomGeometry(PoseStack pose, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {
    }

    @Override
    public void submitShadow(PoseStack pose, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public void submitNameTag(PoseStack pose, @Nullable Vec3 at, int offset, Component name, boolean seeThrough, int light,
                              CameraRenderState camera) {
    }

    @Override
    public void submitFlame(PoseStack pose, EntityRenderState state, Quaternionf rotation) {
    }

    @Override
    public void submitLeash(PoseStack pose, EntityRenderState.LeashState leash) {
    }

    @Override
    public void submitText(PoseStack pose, float x, float y, FormattedCharSequence string, boolean dropShadow, Font.DisplayMode mode, int light,
                           int color, int background, int outline) {
    }

    @Override
    public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack pose, RenderType type, int light, int overlay, int tint,
                                           ModelFeatureRenderer.CrumblingOverlay crumbling) {
    }

    @Override
    public void submitTextBackground(PoseStack pose, float x0, float y0, float x1, float y1, int color, Font.DisplayMode mode, int light) {
    }

    @Override
    public void submitMovingBlock(PoseStack pose, MovingBlockRenderState state, int outline) {
    }

    @Override
    public void submitBlockModel(PoseStack pose, RenderType type, List<BlockStateModelPart> parts, int[] tints, int light, int overlay, int outline) {
    }

    @Override
    public void submitBreakingBlockModel(PoseStack pose, List<BlockStateModelPart> parts, int progress, boolean translucent) {
    }

    @Override
    public void submitShapeOutline(PoseStack pose, VoxelShape shape, RenderType type, int color, float width, boolean afterTerrain) {
    }

    @Override
    public void submitItem(PoseStack pose, ItemDisplayContext context, int light, int overlay, int outline, int[] tints, ItemQuads quads,
                           ItemStackRenderState.FoilType foil) {
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
    }
}
