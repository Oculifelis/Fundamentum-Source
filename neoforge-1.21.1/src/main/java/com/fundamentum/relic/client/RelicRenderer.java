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
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws every relic ({@link com.fundamentum.relic.Relics}): the base, then the boss's likeness floating over it, bobbing,
 * and turning with the block entity's {@link com.fundamentum.relic.RelicSpin} (slowly round, or eased to face forward under
 * a signal). Two ways, per declaration ({@link RelicDef}):
 * <ul>
 * <li>Entity mode: the boss's own renderer, given a stand-in that is never added to the world, drawn through
 * {@link RelicGold} on the relic's tint ramp and fitted to the declared width and height (measured on the model as drawn,
 * {@link RelicMeasure}); one figure per declared phase, several standing round the middle. The base is the block's model.</li>
 * <li>Model mode: a ready-made model layer with its own gold texture and glow, at its declared offset and scale; the base is
 * a model layer too.</li>
 * </ul>
 * A boss whose stand-in, renderer or layer fails is left off (the base still stands) and named once in the log.
 */
public class RelicRenderer implements BlockEntityRenderer<RelicBlockEntity> {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");

    /** One stand-in per entity and phase per world. */
    private static final Map<String, Entity> STAND_INS = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();
    /** Each figure's model extents at scale 1, read the first time it is drawn. */
    private static final Map<String, float[]> BOUNDS = new HashMap<>();
    private static @Nullable Level standInLevel;
    /** The latest renderer made (they are made anew on every reload); the relic items draw through it. */
    static @Nullable RelicRenderer current;
    /** Each relic's extents as an item draws it (blocks, at the rest angle, no bob), by relic. */
    private static final Map<RelicDef, float[]> ITEM_BOUNDS = new HashMap<>();

    private final BlockEntityRendererProvider.Context ctx;
    private final EntityRenderDispatcher entities;
    private final Map<String, ModelPart> layers = new HashMap<>();
    private final Set<String> badLayers = new HashSet<>();

    public RelicRenderer(BlockEntityRendererProvider.Context ctx) {
        this.ctx = ctx;
        this.entities = ctx.getEntityRenderer();
        current = this;
        // renderers are made anew on every resource reload: the gilded sheets and measurements go with the old ones
        RelicGold.clear();
        BOUNDS.clear();
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

    private static @Nullable Entity standIn(String entity, int phase, Level level) {
        if (standInLevel != level) {
            STAND_INS.clear();
            FAILED.clear();
            BOUNDS.clear();
            standInLevel = level;
        }
        String key = entity + "#" + phase;
        if (FAILED.contains(key)) {
            return null;
        }
        Entity e = STAND_INS.get(key);
        if (e == null) {
            ResourceLocation id = ResourceLocation.tryParse(entity);
            EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
            try {
                e = type == null ? null : type.create(level);
                if (e != null) {
                    RelicClient.pose(e, phase);
                }
            } catch (RuntimeException ex) {
                LOG.warn("no relic stand-in for {}", entity, ex);
                e = null;
            }
            if (e == null) {
                FAILED.add(key);
                return null;
            }
            STAND_INS.put(key, e);
        }
        return e;
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
        this.draw(def, level, facing, angle, bob, partial, pose, buffers, light);
    }

    /** The base and the figure (model mode, or entity mode when there is a world): at {@code angle}, lifted by {@code bob}. */
    private void draw(RelicDef def, @Nullable Level level, Direction facing, float angle, float bob, float partial, PoseStack pose,
                      MultiBufferSource buffers, int light) {
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
        if (def.figure.model()) {
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
            return;
        }
        if (level != null) {
            this.figures(def, level, angle, bob, partial, pose, buffers, light);
        }
    }

    /** Entity mode: one stand-in per declared phase, placed round the middle, fitted and gilded. */
    private void figures(RelicDef def, Level level, float angle, float bob, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        RelicDef.Figure fig = def.figure;
        int n = fig.phases.length;
        RelicGold gold = null;
        RelicGold through = null;
        for (int i = 0; i < n; i++) {
            String key = fig.entity + "#" + fig.phases[i];
            Entity e = standIn(fig.entity, fig.phases[i], level);
            if (e == null) {
                continue;
            }
            // the stand-in idles: its age runs with the world's clock, and it looks straight ahead
            e.tickCount = (int) level.getGameTime();
            e.setYRot(0F);
            e.yRotO = 0F;
            e.setXRot(0F);
            e.xRotO = 0F;
            if (e instanceof LivingEntity l) {
                l.yBodyRot = l.yBodyRotO = l.yHeadRot = l.yHeadRotO = 0F;
                l.hurtTime = 0;
                l.deathTime = 0;
            }
            EntityRenderer<? super Entity> renderer;
            float[] b = fig.bounds;
            try {
                renderer = this.entities.getRenderer(e);
                if (b == null) {
                    b = BOUNDS.get(key);
                    if (b == null) {
                        b = measure(renderer, e, partial, light);
                        BOUNDS.put(key, b);
                    }
                }
            } catch (RuntimeException ex) {
                LOG.warn("cannot draw the relic of {}", fig.entity, ex);
                FAILED.add(key);
                STAND_INS.remove(key);
                continue;
            }
            float x = 0F;
            float z = 0F;
            float yaw = angle;
            if (n > 1) {
                // several figures over the base, evenly round, all turning together; each faces out
                double a = Math.toRadians(angle + i * 360.0 / n);
                x = (float) -Math.sin(a) * fig.radius;
                z = (float) Math.cos(a) * fig.radius;
                yaw = (float) (angle + i * 360.0 / n);
            }
            float lift = def.base.top / 16F + fig.floatBy / 16F + bob;
            // fit the model as drawn (not its hitbox): the circle it sweeps as it turns and its height, centred, lowest point on the lift
            float across = Math.max(0.05F, (float) Math.hypot(b[3] - b[0], b[5] - b[2]));
            float high = Math.max(0.05F, b[4] - b[1]);
            float k = Float.isNaN(fig.scale) ? Math.min(fig.wide / 16F / across, fig.tall / 16F / high) : fig.scale;
            if (gold == null) {
                gold = new RelicGold(buffers, def.tint, def.tintFloor, false);
                through = new RelicGold(buffers, def.tint, def.tintFloor, true);
            }
            pose.pushPose();
            pose.translate(0.5F + x, lift, 0.5F + z);
            pose.mulPose(Axis.YP.rotationDegrees(yaw));
            pose.scale(k, k, k);
            pose.translate(-(b[0] + b[3]) / 2F, -b[1], -(b[2] + b[5]) / 2F);
            try {
                renderer.render(e, 0F, partial, pose, gold, light);
                renderer.render(e, 0F, partial, pose, through, light);
            } catch (RuntimeException ex) {
                LOG.warn("cannot draw the relic of {}", fig.entity, ex);
                FAILED.add(key);
                STAND_INS.remove(key);
            }
            pose.popPose();
        }
    }

    /** The likeness's extents as its renderer draws it at scale 1 (blocks; min x, y, z, max x, y, z). */
    private static float[] measure(EntityRenderer<? super Entity> renderer, Entity e, float partial, int light) {
        RelicMeasure m = new RelicMeasure();
        try {
            renderer.render(e, 0F, partial, new PoseStack(), m, light);
        } catch (RuntimeException ex) {
            LOG.warn("could not measure a relic likeness", ex);
        }
        return m.empty() ? new float[] {-0.5F, 0F, -0.5F, 0.5F, 1F, 0.5F} : m.b.clone();
    }

    /**
     * The relic as an item: a small copy of the placed relic, front forward, at rest (no bob, not turning), fitted into the
     * one-block cube the item's display transforms expect (a grand relic shrinks to fit too). In entity mode the base is the
     * block's own model, and the figure needs a world to make its stand-in in (without one, only the base is drawn).
     */
    void drawItem(RelicBlock block, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        RelicDef def = com.fundamentum.relic.Relics.def(block);
        Direction facing = Direction.NORTH;
        float angle = com.fundamentum.relic.RelicSpin.rest(facing.toYRot());
        Level level = net.minecraft.client.Minecraft.getInstance().level;
        boolean whole = def.figure.model() || level != null;
        float[] b = whole ? ITEM_BOUNDS.get(def) : null;
        if (b == null) {
            RelicMeasure m = new RelicMeasure();
            float lo = def.grand() ? -1F : 0F;
            float hi = def.grand() ? 2F : 1F;
            if (def.base.layer == null) {
                m.box(lo, 0F, lo, hi, def.base.top / 16F, hi);
            }
            try {
                this.draw(def, level, facing, angle, 0F, 0F, new PoseStack(), m, light);
            } catch (RuntimeException ex) {
                LOG.warn("could not measure the relic item {}", block, ex);
            }
            b = m.empty() ? new float[] {lo, 0F, lo, hi, 1F, hi} : m.b.clone();
            if (whole) {
                ITEM_BOUNDS.put(def, b);
            }
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
        this.draw(def, level, facing, angle, 0F, 0F, pose, buffers, light);
        pose.popPose();
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
