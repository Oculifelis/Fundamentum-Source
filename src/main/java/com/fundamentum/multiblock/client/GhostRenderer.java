package com.fundamentum.multiblock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

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
    private static BlockStateModelSet builtWith;
    private static List<Cell> cells = List.of();

    private GhostRenderer() { }

    private static List<Cell> cells(List<MultiblockProjector.Ghost> ghosts, BlockStateModelSet models) {
        if (ghosts == builtFrom && models == builtWith) return cells;
        List<Cell> out = new ArrayList<>(ghosts.size());
        List<BlockStateModelPart> parts = new ArrayList<>();
        RandomSource random = RandomSource.create();
        for (MultiblockProjector.Ghost g : ghosts) {
            List<BakedQuad> quads = new ArrayList<>();
            if (g.expected() != null) {
                parts.clear();
                random.setSeed(g.expected().getSeed(g.pos()));
                models.get(g.expected()).collectParts(random, parts);
                for (BlockStateModelPart part : parts) {
                    quads.addAll(part.getQuads(null));
                    for (Direction d : Direction.values()) quads.addAll(part.getQuads(d));
                }
            }
            out.add(new Cell(g.pos(), g.kind(), quads));
        }
        builtFrom = ghosts;
        builtWith = models;
        cells = out;
        return out;
    }

    @SubscribeEvent
    public static void draw(SubmitCustomGeometryEvent event) {
        List<MultiblockProjector.Ghost> ghosts = MultiblockProjector.ghosts();
        if (ghosts.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        List<Cell> list = cells(ghosts, mc.getModelManager().getBlockStateModelSet());
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack ps = event.getPoseStack();
        var collector = event.getSubmitNodeCollector();
        var blocks = RenderTypes.itemTranslucent(TextureAtlas.LOCATION_BLOCKS);
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
            collector.submitCustomGeometry(ps, blocks, (pose, buffer) -> {
                QuadInstance q = new QuadInstance();
                q.setColor(color);
                q.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
                for (BakedQuad quad : c.quads()) buffer.putBakedQuad(pose, quad, q);
            });
            ps.popPose();
        }
        collector.submitCustomGeometry(ps, RenderTypes.debugFilledBox(), (pose, buffer) -> {
            for (Cell c : list) {
                if (c.kind() == MultiblockProjector.Kind.CLEAR) box(pose, buffer, c.pos(), cam, 0.02F, CLEAR_FILL);
            }
        });
        collector.submitCustomGeometry(ps, RenderTypes.lines(), (pose, buffer) -> {
            for (Cell c : list) {
                if (c.kind() != MultiblockProjector.Kind.MISSING) edges(pose, buffer, c.pos(), cam, OUTLINE);
            }
        });
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

    /** The twelve edges of a block, camera-relative. */
    private static void edges(PoseStack.Pose pose, VertexConsumer b, BlockPos pos, Vec3 cam, int color) {
        float x0 = (float) (pos.getX() - cam.x) - 0.005F, y0 = (float) (pos.getY() - cam.y) - 0.005F, z0 = (float) (pos.getZ() - cam.z) - 0.005F;
        float x1 = x0 + 1.01F, y1 = y0 + 1.01F, z1 = z0 + 1.01F;
        line(pose, b, color, x0, y0, z0, x1, y0, z0);
        line(pose, b, color, x0, y1, z0, x1, y1, z0);
        line(pose, b, color, x0, y0, z1, x1, y0, z1);
        line(pose, b, color, x0, y1, z1, x1, y1, z1);
        line(pose, b, color, x0, y0, z0, x0, y1, z0);
        line(pose, b, color, x1, y0, z0, x1, y1, z0);
        line(pose, b, color, x0, y0, z1, x0, y1, z1);
        line(pose, b, color, x1, y0, z1, x1, y1, z1);
        line(pose, b, color, x0, y0, z0, x0, y0, z1);
        line(pose, b, color, x1, y0, z0, x1, y0, z1);
        line(pose, b, color, x0, y1, z0, x0, y1, z1);
        line(pose, b, color, x1, y1, z0, x1, y1, z1);
    }

    private static void line(PoseStack.Pose pose, VertexConsumer b, int color, float ax, float ay, float az, float bx, float by, float bz) {
        float nx = bx - ax, ny = by - ay, nz = bz - az;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= len;
        ny /= len;
        nz /= len;
        b.addVertex(pose, ax, ay, az).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(2.0F);
        b.addVertex(pose, bx, by, bz).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(2.0F);
    }
}
