package com.fundamentum.multiblock.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;

/**
 * Draws a multiblock scene as real block models inside a GUI box, lit like a block item. The quads are collected once
 * per scene (and again after a resource reload), and drawing them is one batch. Screens call {@link #submit}.
 */
public final class GuiMultiblockRenderer {
    /** The usual view, a three-quarter angle from the front left, looking down. */
    public static final float YAW = 225;
    public static final float PITCH = 30;

    private record Placed(BlockPos pos, List<BakedQuad> quads) { }

    private static final Map<MultiblockScene.Scene, List<Placed>> QUADS = new WeakHashMap<>();
    private static Object quadsWith;

    private GuiMultiblockRenderer() { }

    /**
     * Draws a scene to fit the box {@code x}, {@code y}, {@code w} by {@code h} in GUI pixels, turned {@code yaw} and
     * tilted {@code pitch} degrees.
     */
    public static void submit(GuiGraphics g, MultiblockScene.Scene scene, int x, int y, int w, int h, float yaw, float pitch) {
        if (scene == null || w <= 0 || h <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        float scale = fit(scene, w, h, pitch);
        float cx = (scene.min().getX() + scene.max().getX() + 1) / 2F;
        float cy = (scene.min().getY() + scene.max().getY() + 1) / 2F;
        float cz = (scene.min().getZ() + scene.max().getZ() + 1) / 2F;
        g.flush();
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(x + w / 2F, y + h / 2F, 150 + scale * scene.size());
        ps.scale(scale, -scale, scale);
        ps.mulPose(Axis.XP.rotationDegrees(pitch));
        ps.mulPose(Axis.YP.rotationDegrees(yaw));
        Lighting.setupFor3DItems();
        MultiBufferSource.BufferSource buffers = g.bufferSource();
        RenderType type = Sheets.translucentItemSheet();
        VertexConsumer buffer = buffers.getBuffer(type);
        for (Placed p : quads(scene, mc.getModelManager().getBlockModelShaper())) {
            ps.pushPose();
            ps.translate(p.pos().getX() - cx, p.pos().getY() - cy, p.pos().getZ() - cz);
            for (BakedQuad quad : p.quads()) buffer.putBulkData(ps.last(), quad, 1, 1, 1, 1, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, true);
            ps.popPose();
        }
        buffers.endBatch(type);
        ps.popPose();
        Lighting.setupForFlatItems();
    }

    /**
     * Pixels per block so the scene fills the box at any yaw. The widest the footprint gets is its diagonal, and the
     * height is the column seen at the pitch plus the footprint seen from above. A tenth is left as a margin.
     */
    static float fit(MultiblockScene.Scene scene, int w, int h, float pitch) {
        float dx = scene.max().getX() - scene.min().getX() + 1;
        float dy = scene.max().getY() - scene.min().getY() + 1;
        float dz = scene.max().getZ() - scene.min().getZ() + 1;
        float diag = (float) Math.sqrt(dx * dx + dz * dz);
        double p = Math.toRadians(pitch);
        float tall = (float) (dy * Math.cos(p) + diag * Math.abs(Math.sin(p)));
        return 0.9F * Math.min(w / diag, h / Math.max(1F, tall));
    }

    private static synchronized List<Placed> quads(MultiblockScene.Scene scene, Object models) {
        if (models != quadsWith) {
            QUADS.clear();
            quadsWith = models;
        }
        return QUADS.computeIfAbsent(scene, s -> {
            List<Placed> out = new ArrayList<>(s.cells().size());
            for (MultiblockScene.Cell c : s.cells()) out.add(new Placed(c.pos(), GhostRenderer.quads(c.state(), c.pos())));
            return out;
        });
    }
}
