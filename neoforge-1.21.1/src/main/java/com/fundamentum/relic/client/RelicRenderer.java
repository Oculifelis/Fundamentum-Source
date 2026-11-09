package com.fundamentum.relic.client;

import com.fundamentum.relic.RelicBlock;
import com.fundamentum.relic.RelicBlockEntity;
import com.fundamentum.relic.RelicDef;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws every relic ({@link com.fundamentum.relic.Relics}): the base, then the boss's likeness floating over it, bobbing,
 * and turning with the block entity's {@link com.fundamentum.relic.RelicSpin} (slowly round, or eased to face forward under
 * a signal). On 1.21.1 only model mode draws (a ready-made model layer with its own gold texture and glow); a relic
 * declared in entity mode shows its base alone, and is named once in the log.
 */
public class RelicRenderer implements BlockEntityRenderer<RelicBlockEntity> {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    private static final Set<String> WARNED = new HashSet<>();

    private final BlockEntityRendererProvider.Context ctx;
    private final Map<String, ModelPart> layers = new HashMap<>();
    private final Set<String> badLayers = new HashSet<>();

    /** The latest renderer made (they are made anew on every reload); the relic items draw through it. */
    static @Nullable RelicRenderer current;
    /** Each relic's extents as an item draws it (blocks, at the rest angle, no bob), by relic. */
    private static final Map<RelicDef, float[]> ITEM_BOUNDS = new HashMap<>();

    public RelicRenderer(BlockEntityRendererProvider.Context ctx) {
        this.ctx = ctx;
        current = this;
        ITEM_BOUNDS.clear();
    }

    /** {@code ns:path} or {@code ns:path#layer} (default {@code main}) as a baked layer, or null (named once in the log). */
    private @Nullable ModelPart layer(String name) {
        ModelPart part = this.layers.get(name);
        if (part != null || this.badLayers.contains(name)) {
            return part;
        }
        int hash = name.indexOf('#');
        ResourceLocation id = ResourceLocation.tryParse(hash < 0 ? name : name.substring(0, hash));
        try {
            if (id == null) {
                throw new IllegalArgumentException("not an id");
            }
            part = this.ctx.bakeLayer(new ModelLayerLocation(id, hash < 0 ? "main" : name.substring(hash + 1)));
            this.layers.put(name, part);
        } catch (RuntimeException ex) {
            LOG.warn("relic model layer {} cannot be baked (is it registered?)", name, ex);
            this.badLayers.add(name);
        }
        return part;
    }

    @Override
    public void render(RelicBlockEntity be, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Level level = be.getLevel();
        RelicDef def = be.def();
        if (level == null || def == null) {
            return;
        }
        Direction facing = be.getBlockState().getValue(RelicBlock.FACING);
        float time = (level.getGameTime() % 24000L) + partial;
        float bob = def.bob(time);
        float angle = be.spin().angle(partial);
        this.draw(def, facing, angle, bob, pose, buffers, light);
    }

    /** The base and, in model mode, the figure: at {@code angle}, lifted by {@code bob}. */
    private void draw(RelicDef def, Direction facing, float angle, float bob, PoseStack pose, MultiBufferSource buffers, int light) {
        if (def.base.layer != null) {
            ModelPart base = this.layer(def.base.layer);
            if (base != null) {
                pose.pushPose();
                pose.translate(0.5F, 0.0F, 0.5F);
                pose.mulPose(Axis.YP.rotationDegrees(180.0F - facing.toYRot()));
                pose.scale(-1.0F, -1.0F, 1.0F);                 // model space, as for mobs: y down, front toward -z
                part(buffers, pose, base, def.base.texture, def.base.glow, light);
                pose.popPose();
            }
        }
        if (!def.figure.model()) {
            if (WARNED.add(def.figure.entity)) {
                LOG.info("relic of {}: entity-mode figures are not drawn on 1.21.1 (base only)", def.figure.entity);
            }
            return;
        }
        ModelPart model = this.layer(def.figure.layer);
        if (model != null) {
            float[] o = def.figure.offset;
            float s = def.figure.scale;
            pose.pushPose();
            pose.translate(0.5F, 0.0F, 0.5F);
            pose.mulPose(Axis.YP.rotationDegrees(180.0F + angle));
            pose.scale(-1.0F, -1.0F, 1.0F);
            pose.translate(o[0] / 16.0F, o[1] / 16.0F - bob, o[2] / 16.0F);
            pose.scale(s, s, s);
            part(buffers, pose, model, def.figure.texture, def.figure.glow, light);
            pose.popPose();
        }
    }

    /**
     * The relic as an item: a small copy of the placed relic, front forward, at rest (no bob, not turning), fitted into the
     * one-block cube the item's display transforms expect (a grand relic shrinks to fit too). A relic in entity mode shows
     * its block's model, as it does when placed here.
     */
    void drawItem(RelicBlock block, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        RelicDef def = com.fundamentum.relic.Relics.def(block);
        Direction facing = Direction.NORTH;
        float angle = com.fundamentum.relic.RelicSpin.rest(facing.toYRot());
        float[] b = ITEM_BOUNDS.get(def);
        if (b == null) {
            float lo = def.grand() ? -1F : 0F;
            float hi = def.grand() ? 2F : 1F;
            float[] m = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            if (def.base.layer == null) {
                grow(m, lo, 0F, lo);
                grow(m, hi, def.base.top / 16F, hi);
            }
            this.draw(def, facing, angle, 0F, new PoseStack(), type -> new Measure(m), light);
            b = m[0] > m[3] ? new float[] {lo, 0F, lo, hi, 1F, hi} : m;
            ITEM_BOUNDS.put(def, b);
        }
        float span = Math.max(Math.max(b[3] - b[0], b[5] - b[2]), b[4] - b[1]);
        float k = 1F / Math.max(0.05F, span);
        pose.pushPose();
        pose.translate(0.5F, (1F - (b[4] - b[1]) * k) / 2F, 0.5F);
        pose.scale(k, k, k);
        pose.translate(-(b[0] + b[3]) / 2F, -b[1], -(b[2] + b[5]) / 2F);
        if (def.base.layer == null) {
            net.minecraft.client.Minecraft.getInstance().getBlockRenderer().renderSingleBlock(block.defaultBlockState(), pose, buffers, light, overlay);
        }
        this.draw(def, facing, angle, 0F, pose, buffers, light);
        pose.popPose();
    }

    private static void grow(float[] m, float x, float y, float z) {
        m[0] = Math.min(m[0], x);
        m[1] = Math.min(m[1], y);
        m[2] = Math.min(m[2], z);
        m[3] = Math.max(m[3], x);
        m[4] = Math.max(m[4], y);
        m[5] = Math.max(m[5], z);
    }

    /** A vertex sink that only notes where the vertices land (blocks), for {@link #drawItem}'s fitting. */
    private record Measure(float[] m) implements com.mojang.blaze3d.vertex.VertexConsumer {
        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            grow(this.m, x, y, z);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    private static void part(MultiBufferSource buffers, PoseStack pose, ModelPart model, String tex, @Nullable String glow, int light) {
        ResourceLocation t = ResourceLocation.tryParse(tex);
        if (t != null) {
            model.render(pose, buffers.getBuffer(RenderType.entityCutout(t)), light, OverlayTexture.NO_OVERLAY);
        }
        ResourceLocation g = glow == null ? null : ResourceLocation.tryParse(glow);
        if (g != null) {
            model.render(pose, buffers.getBuffer(RenderType.eyes(g)), light, OverlayTexture.NO_OVERLAY);
        }
    }

    /** The likeness rises well above the block, and a grand relic spreads a block and a half to each side. */
    @Override
    public AABB getRenderBoundingBox(RelicBlockEntity be) {
        BlockPos p = be.getBlockPos();
        AABB box = new AABB(p);
        return be.grand() ? box.inflate(1.5, 0.0, 1.5).expandTowards(0.0, 4.5, 0.0) : box.inflate(0.25, 0.0, 0.25).expandTowards(0.0, 2.0, 0.0);
    }
}
