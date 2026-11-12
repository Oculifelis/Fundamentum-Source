package com.fundamentum.multiblock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Draws the multiblock ghost preview from {@link MultiblockProjector#ghosts()}: each missing block as its own block model
 * at 40% opacity, a block in the way as the wanted block tinted red with a red outline, and a cell that must be cleared
 * as a faint red box. The block quads are collected once per ghost list (or model reload), not every frame.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class GhostRenderer {
    static final int GHOST = 0x66FFFFFF;
    static final int WRONG = 0x80FF5050;
    static final int CLEAR_FILL = 0x30FF3030;
    static final int OUTLINE = 0xC0FF3030;

    private record Cell(BlockPos pos, MultiblockProjector.Kind kind, List<BakedQuad> quads) { }

    private static List<MultiblockProjector.Ghost> builtFrom;
    private static Object builtWith;
    private static List<Cell> cells = List.of();

    private GhostRenderer() { }

    /** Every quad of a block's model, for drawing it outside the world. */
    static List<BakedQuad> quads(BlockState state, BlockPos pos) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        List<BakedQuad> quads = new ArrayList<>();
        RandomSource random = RandomSource.create();
        long seed = state.getSeed(pos);
        random.setSeed(seed);
        quads.addAll(model.getQuads(state, null, random, ModelData.EMPTY, null));
        for (Direction d : Direction.values()) {
            random.setSeed(seed);
            quads.addAll(model.getQuads(state, d, random, ModelData.EMPTY, null));
        }
        return quads;
    }

    private static List<Cell> cells(List<MultiblockProjector.Ghost> ghosts, Object models) {
        if (ghosts == builtFrom && models == builtWith) return cells;
        List<Cell> out = new ArrayList<>(ghosts.size());
        for (MultiblockProjector.Ghost g : ghosts) {
            out.add(new Cell(g.pos(), g.kind(), g.expected() == null ? List.of() : quads(g.expected(), g.pos())));
        }
        builtFrom = ghosts;
        builtWith = models;
        cells = out;
        return out;
    }

    @SubscribeEvent
    public static void draw(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        List<MultiblockProjector.Ghost> ghosts = MultiblockProjector.ghosts();
        if (ghosts.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        List<Cell> list = cells(ghosts, mc.getModelManager().getBlockModelShaper());
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType blocks = Sheets.translucentItemSheet();
        VertexConsumer buffer = buffers.getBuffer(blocks);
        for (Cell c : list) {
            if (c.quads().isEmpty()) continue;
            ps.pushPose();
            ps.translate(c.pos().getX() - cam.x, c.pos().getY() - cam.y, c.pos().getZ() - cam.z);
            if (c.kind() == MultiblockProjector.Kind.WRONG) {
                ps.translate(0.5, 0.5, 0.5);
                ps.scale(1.01F, 1.01F, 1.01F);
                ps.translate(-0.5, -0.5, -0.5);
            }
            int color = c.kind() == MultiblockProjector.Kind.WRONG ? WRONG : GHOST;
            float r = (color >> 16 & 0xFF) / 255F, g = (color >> 8 & 0xFF) / 255F, b = (color & 0xFF) / 255F, a = (color >>> 24) / 255F;
            for (BakedQuad quad : c.quads()) buffer.putBulkData(ps.last(), quad, r, g, b, a, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, true);
            ps.popPose();
        }
        buffers.endBatch(blocks);
        VertexConsumer fill = buffers.getBuffer(RenderType.debugQuads());
        for (Cell c : list) {
            if (c.kind() == MultiblockProjector.Kind.CLEAR) box(ps.last(), fill, c.pos(), cam, 0.02F, CLEAR_FILL);
        }
        buffers.endBatch(RenderType.debugQuads());
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        float r = (OUTLINE >> 16 & 0xFF) / 255F, g = (OUTLINE >> 8 & 0xFF) / 255F, b = (OUTLINE & 0xFF) / 255F, a = (OUTLINE >>> 24) / 255F;
        for (Cell c : list) {
            if (c.kind() == MultiblockProjector.Kind.MISSING) continue;
            AABB box = new AABB(c.pos()).inflate(0.005).move(-cam.x, -cam.y, -cam.z);
            LevelRenderer.renderLineBox(ps, lines, box, r, g, b, a);
        }
        buffers.endBatch(RenderType.lines());
    }

    /** A filled box inset by {@code inset}, camera-relative. */
    private static void box(PoseStack.Pose pose, VertexConsumer b, BlockPos pos, Vec3 cam, float inset, int color) {
        float x0 = (float) (pos.getX() - cam.x) + inset, y0 = (float) (pos.getY() - cam.y) + inset, z0 = (float) (pos.getZ() - cam.z) + inset;
        float x1 = x0 + 1 - 2 * inset, y1 = y0 + 1 - 2 * inset, z1 = z0 + 1 - 2 * inset;
        quad(pose, b, color, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        quad(pose, b, color, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        quad(pose, b, color, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
        quad(pose, b, color, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
        quad(pose, b, color, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
        quad(pose, b, color, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer b, int color, float... v) {
        for (int i = 0; i < 12; i += 3) b.addVertex(pose, v[i], v[i + 1], v[i + 2]).setColor(color);
    }
}
