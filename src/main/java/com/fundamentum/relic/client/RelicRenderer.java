package com.fundamentum.relic.client;

import com.fundamentum.relic.RelicBases;
import com.fundamentum.relic.RelicBlock;
import com.fundamentum.relic.RelicBlockEntity;
import com.fundamentum.relic.RelicDef;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
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
 * <li>Model mode: a ready-made model layer with its own gold texture and glow, at its declared offset and scale.</li>
 * </ul>
 * The base is the anchor block's look ({@link RelicBases.Look}): its block model, or a model layer scaled to the stand's
 * size, retinted when the relic's file gives the base a tint. A wide stand draws round its true centre. A stand whose relic
 * is unknown draws its base alone.
 * A boss whose stand-in, renderer or layer fails is left off (the base still stands) and named once in the log.
 */
public class RelicRenderer implements BlockEntityRenderer<RelicBlockEntity, RelicRenderer.State> {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");

    /** One entity-mode figure on the relic: where it stands, the box it fits and its stand-in's drawn state. */
    static final class Figure {
        EntityRenderState state;
        String key;
        float x;
        float z;
        float wide;
        float tall;
        float lift;
        float yaw;
        float scale;
        float @Nullable [] bounds;
    }

    public static class State extends BlockEntityRenderState {
        final List<Figure> figures = new ArrayList<>();
        @Nullable RelicDef def;
        RelicBases.Look look = RelicBases.Look.block(16F);
        float cx;
        float cz;
        Direction facing = Direction.NORTH;
        float angle;
        float bob;
    }

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
        this.entities = ctx.entityRenderer();
        current = this;
        // renderers are made anew on every resource reload: the gilded sheets and measurements go with the old ones
        RelicGold.clear();
        BOUNDS.clear();
        ITEM_BOUNDS.clear();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    /** {@code ns:path} or {@code ns:path#layer} (default {@code main}) as a baked layer, or null (named once in the log). */
    private @Nullable ModelPart layer(String name) {
        ModelPart part = this.layers.get(name);
        if (part != null || this.badLayers.contains(name)) {
            return part;
        }
        int hash = name.indexOf('#');
        Identifier id = Identifier.tryParse(hash < 0 ? name : name.substring(0, hash));
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
            Identifier id = Identifier.tryParse(entity);
            EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
            try {
                e = type == null ? null : type.create(level, EntitySpawnReason.LOAD);
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
    public void extractRenderState(RelicBlockEntity be, State state, float partial, Vec3 camera, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, partial, camera, breakProgress);
        state.figures.clear();
        Level level = be.getLevel();
        RelicDef def = be.def();
        state.def = def;
        if (!(be.getBlockState().getBlock() instanceof RelicBlock anchor)) {
            return;
        }
        state.facing = be.getBlockState().getValue(RelicBlock.FACING);
        place(state, anchor, def);
        if (level == null || def == null) {
            return;
        }
        float time = (level.getGameTime() % 24000L) + partial;
        state.bob = def.bob(time);
        state.angle = be.spin().angle(partial);
        this.figures(state, def, level, partial);
    }

    /** The stand's look and true centre (a wide stand's middle is off its anchor block). */
    private static void place(State state, RelicBlock anchor, @Nullable RelicDef def) {
        state.look = RelicBases.look(anchor, def);
        double[] c = RelicBlock.centre(state.facing, anchor.size());
        state.cx = (float) c[0];
        state.cz = (float) c[1];
    }

    /** Entity mode: one stand-in's drawn state per declared phase, placed round the middle; nothing in model mode. */
    private void figures(State state, RelicDef def, Level level, float partial) {
        if (def.figure.model()) {
            return;
        }
        RelicDef.Figure fig = def.figure;
        int n = fig.phases.length;
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
            EntityRenderState s;
            try {
                s = this.entities.extractEntity(e, partial);
            } catch (RuntimeException ex) {
                LOG.warn("cannot draw the relic of {}", fig.entity, ex);
                FAILED.add(key);
                STAND_INS.remove(key);
                continue;
            }
            s.lightCoords = state.lightCoords;
            s.shadowPieces.clear();
            s.nameTag = null;
            s.displayFireAnimation = false;
            Figure f = new Figure();
            f.state = s;
            f.key = key;
            f.wide = fig.wide / 16F;
            f.tall = fig.tall / 16F;
            f.lift = state.look.top() / 16F + fig.floatBy / 16F + state.bob;
            f.scale = fig.scale;
            f.bounds = fig.bounds;
            if (n > 1) {
                // several figures over the base, evenly round, all turning together; each faces out
                double a = Math.toRadians(state.angle + i * 360.0 / n);
                f.x = (float) -Math.sin(a) * fig.radius;
                f.z = (float) Math.cos(a) * fig.radius;
                f.yaw = (float) (state.angle + i * 360.0 / n);
            } else {
                f.yaw = state.angle;
            }
            state.figures.add(f);
        }
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        RelicDef def = state.def;
        int light = state.lightCoords;
        RelicBases.Look look = state.look;
        if (look.layer() != null && look.texture() != null) {
            ModelPart base = this.layer(look.layer());
            if (base != null) {
                SubmitNodeCollector out = def != null && def.base.tint != null && def.base.tint.length > 0
                    ? new RelicGold(collector, def.base.tint, def.tintFloor) : collector;
                pose.pushPose();
                pose.translate(0.5F + state.cx, 0.0F, 0.5F + state.cz);
                pose.mulPose(Axis.YP.rotationDegrees(180.0F - state.facing.toYRot()));
                pose.scale(-1.0F, -1.0F, 1.0F);                 // model space, as for mobs: y down, front toward -z
                pose.scale(look.scale(), look.scale(), look.scale());
                part(out, pose, base, look.texture(), look.glow(), light);
                pose.popPose();
            }
        }
        if (def == null) {
            return;
        }
        if (def.figure.model()) {
            ModelPart model = this.layer(def.figure.layer);
            if (model != null) {
                float[] o = def.figure.offset;
                float s = def.figure.scale;
                SubmitNodeCollector out = def.tinted ? new RelicGold(collector, def.tint, def.tintFloor) : collector;
                pose.pushPose();
                pose.translate(0.5F + state.cx, 0.0F, 0.5F + state.cz);
                pose.mulPose(Axis.YP.rotationDegrees(180.0F + state.angle));
                pose.scale(-1.0F, -1.0F, 1.0F);
                pose.translate(o[0] / 16.0F, o[1] / 16.0F - state.bob, o[2] / 16.0F);
                pose.scale(s, s, s);
                part(out, pose, model, def.figure.texture, def.figure.glow, light);
                pose.popPose();
            }
            return;
        }
        if (state.figures.isEmpty()) {
            return;
        }
        RelicGold gold = new RelicGold(collector, def.tint, def.tintFloor);
        for (Figure f : state.figures) {
            float[] b = f.bounds;
            if (b == null) {
                b = BOUNDS.get(f.key);
                if (b == null) {
                    b = this.measure(f.state, camera);
                    BOUNDS.put(f.key, b);
                }
            }
            // fit the model as drawn (not its hitbox): the circle it sweeps as it turns and its height, centred, lowest point on the lift
            float across = Math.max(0.05F, (float) Math.hypot(b[3] - b[0], b[5] - b[2]));
            float high = Math.max(0.05F, b[4] - b[1]);
            float k = Float.isNaN(f.scale) ? Math.min(f.wide / across, f.tall / high) : f.scale;
            pose.pushPose();
            pose.translate(0.5F + state.cx + f.x, f.lift, 0.5F + state.cz + f.z);
            pose.mulPose(Axis.YP.rotationDegrees(f.yaw));
            pose.scale(k, k, k);
            pose.translate(-(b[0] + b[3]) / 2F, -b[1], -(b[2] + b[5]) / 2F);
            this.entities.submit(f.state, camera, 0.0, 0.0, 0.0, pose, gold);
            pose.popPose();
        }
    }

    private static void part(SubmitNodeCollector nodes, PoseStack pose, ModelPart model, String tex, @Nullable String glow, int light) {
        Identifier t = Identifier.tryParse(tex);
        if (t != null) {
            nodes.submitModelPart(model, pose, RenderTypes.entityCutout(t), light, OverlayTexture.NO_OVERLAY, null);
        }
        Identifier g = glow == null ? null : Identifier.tryParse(glow);
        if (g != null) {
            nodes.submitModelPart(model, pose, RenderTypes.eyes(g), light, OverlayTexture.NO_OVERLAY, null);
        }
    }

    /** The likeness's extents as its renderer draws it at scale 1 (blocks; min x, y, z, max x, y, z). */
    private float[] measure(EntityRenderState s, CameraRenderState camera) {
        RelicMeasure m = new RelicMeasure();
        try {
            this.entities.submit(s, camera, 0.0, 0.0, 0.0, new PoseStack(), m);
        } catch (RuntimeException ex) {
            LOG.warn("could not measure a relic likeness", ex);
        }
        return m.empty() ? new float[] {-0.5F, 0F, -0.5F, 0.5F, 1F, 0.5F} : m.b;
    }

    /**
     * The relic as an item: a small copy of the placed relic, front forward, at rest (no bob, not turning), fitted into the
     * one-block cube the item's display transforms expect (a wide relic shrinks to fit too). A block-model base is the
     * anchor's own model, and an entity-mode figure needs a world to make its stand-in in (without one, only the base is
     * drawn).
     */
    void drawItem(RelicDef def, PoseStack pose, SubmitNodeCollector collector, int light, int overlay, int outline) {
        RelicBlock anchor = RelicBases.anchorFor(def);
        if (anchor == null) {
            return;
        }
        State state = new State();
        state.def = def;
        state.facing = Direction.NORTH;
        place(state, anchor, def);
        state.angle = com.fundamentum.relic.RelicSpin.rest(Direction.NORTH.toYRot());
        state.bob = 0F;
        state.lightCoords = light;
        Level level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null) {
            this.figures(state, def, level, 0F);
        }
        CameraRenderState camera = new CameraRenderState();
        boolean whole = def.figure.model() || !state.figures.isEmpty();
        float[] b = whole ? ITEM_BOUNDS.get(def) : null;
        if (b == null) {
            RelicMeasure m = new RelicMeasure();
            float n = anchor.size();
            float lo0 = 0.5F + state.cx - n / 2F;
            float lo2 = 0.5F + state.cz - n / 2F;
            if (state.look.layer() == null) {
                m.box(lo0, 0F, lo2, lo0 + n, state.look.top() / 16F, lo2 + n);
            }
            try {
                this.submit(state, new PoseStack(), m, camera);
            } catch (RuntimeException ex) {
                LOG.warn("could not measure a relic item", ex);
            }
            b = m.empty() ? new float[] {lo0, 0F, lo2, lo0 + n, 1F, lo2 + n} : m.b.clone();
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
        if (state.look.layer() == null) {
            var model = net.minecraft.client.Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(anchor.defaultBlockState());
            List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(net.minecraft.util.RandomSource.create(42L), parts);
            collector.submitBlockModel(pose, net.minecraft.client.renderer.Sheets.cutoutBlockItemSheet(), parts, new int[0], light, overlay, outline);
        }
        this.submit(state, pose, collector, camera);
        pose.popPose();
    }

    /** The likeness rises well above the stand, and a wide stand spreads round its true centre. */
    @Override
    public AABB getRenderBoundingBox(RelicBlockEntity be) {
        BlockPos p = be.getBlockPos();
        int n = be.getBlockState().getBlock() instanceof RelicBlock r ? r.size() : 1;
        Direction facing = be.getBlockState().hasProperty(RelicBlock.FACING) ? be.getBlockState().getValue(RelicBlock.FACING) : Direction.NORTH;
        double[] c = RelicBlock.centre(facing, n);
        double x = p.getX() + 0.5 + c[0];
        double z = p.getZ() + 0.5 + c[1];
        double r = n / 2.0 + 0.5;
        return new AABB(x - r, p.getY(), z - r, x + r, p.getY() + 2.0 + 1.7 * n, z + r);
    }
}
