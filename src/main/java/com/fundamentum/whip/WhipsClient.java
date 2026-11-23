/*
 * The cord drawing follows Better Whips (https://github.com/my2167592261-cell/Better-Whips), whose code is under the
 * MIT License (none of its models, textures or sounds are used):
 *
 * Copyright (c) 2026 my2167592261-cell
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.fundamentum.whip;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.mojang.serialization.MapCodec;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import com.fundamentum.client.Argb;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.world.InteractionHand;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

/**
 * The client half of {@link Whips}: every swing the server announces is run here too, from the same aim, side and
 * tuning, and drawn as one textured ribbon per strand, turned to face the camera, tapering from the handle's width to
 * the tip's and tinted from the handle colour to the tip colour. Drawing only: hits are the server's. Between swings a
 * held whip lets {@link WhipDefinition#idleLength} of its cord hang from the hand, swaying and lying on blocks.
 *
 * <p>The handle end is drawn in the hand (in first person where vanilla draws the held item, otherwise in the player
 * model's posed hand), easing to the simulated cord along its length, so the tip stays where the server's tip is.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class WhipsClient {
    /** Idle cords are only kept for players this close to the camera. */
    private static final double IDLE_RANGE = 48.0;

    /** Something drawn: a whip's cords, as they were last tick and are now. */
    private abstract static class Shown {
        final ResourceLocation item;
        final WhipDefinition whip;
        final WhipCord[] cords;
        /** The fraction of the cord's length these cords are (1 for a swing). */
        double span;
        final WhipVec[][] prev, now;
        Vec3 hand;

        Shown(ResourceLocation item, WhipDefinition whip, WhipCord[] cords, double span, Vec3 hand) {
            this.item = item;
            this.whip = whip;
            this.cords = cords;
            this.span = span;
            this.hand = hand;
            prev = new WhipVec[cords.length][];
            now = new WhipVec[cords.length][];
            snapshot(now);
            snapshot(prev);
        }

        void snapshot(WhipVec[][] into) {
            for (int k = 0; k < cords.length; k++) {
                into[k] = cords[k].points.clone();
            }
        }
    }

    private static final class Swing extends Shown {
        final WhipSwing swing;
        /** Per strand: each strand strikes on its own (its crack is {@link WhipSwing#crack}). */
        final boolean[] struck;

        Swing(ResourceLocation item, WhipDefinition whip, WhipSwing swing, Vec3 hand) {
            super(item, whip, swing.cords, 1.0, hand);
            this.swing = swing;
            struck = new boolean[swing.cords.length];
        }
    }

    /** The loose cord of a held whip between swings: plain physics from the hand, no guide. */
    private static final class Idle extends Shown {
        /** Ticks a finished swing's cord takes to draw back in to the idle length and settle into the hand. */
        static final int RETURN_TICKS = 10;
        private final double fullRest, idleRest;
        /** Where a finished swing left the handle, from the hand, and which way it pointed. */
        private WhipVec reach = WhipVec.ZERO, reachDir;
        private int returned = RETURN_TICKS;
        /** Set for the tick a swing hands its cord over: the swing already stepped it, so this tick draws its last step. */
        private boolean carried;

        Idle(ResourceLocation item, WhipDefinition whip, Vec3 hand) {
            this(item, whip, cord(whip, hand), whip.idleLength, hand);
        }

        /**
         * Carries on from a finished swing with its first strand as it is (shape and speed), drawing it back in to the
         * idle length over {@link #RETURN_TICKS}, so the cord doesn't jump from the swing to hanging straight down.
         */
        Idle(Swing swing, Vec3 hand) {
            this(swing.item, swing.whip, swing.cords[0], 1.0, hand);
            WhipVec[] p = cords[0].points;
            reach = p[0].sub(Whips.vec(hand));
            reachDir = p[1].sub(p[0]).normalize(new WhipVec(0, -1, 0));
            returned = 0;
            prev[0] = swing.prev[0];
            carried = true;
        }

        private Idle(ResourceLocation item, WhipDefinition whip, WhipCord cord, double span, Vec3 hand) {
            super(item, whip, new WhipCord[] {cord}, span, hand);
            fullRest = cord.rest;
            idleRest = cord.rest * whip.idleLength / span;
        }

        private static WhipCord cord(WhipDefinition whip, Vec3 hand) {
            WhipTuning t = whip.tuning;
            WhipCord cord = new WhipCord(Math.max(3, (int) Math.round(t.segments() * whip.idleLength)), t.length() * whip.idleLength);
            cord.layOut(Whips.vec(hand), new WhipVec(0, -1, 0), null);
            return cord;
        }

        void tick(Vec3 to, Vec3 look, WhipCord.Solids solids) {
            if (carried) {
                carried = false;
                hand = to;
                return;
            }
            snapshot(prev);
            // The handle hangs a little forward of straight down; the rest is gravity, drag and the blocks.
            WhipVec hang = new WhipVec(look.x * 0.35, -1, look.z * 0.35).normalize(new WhipVec(0, -1, 0));
            WhipTuning t = whip.tuning;
            int n = 4;
            for (int i = 1; i <= n; i++) {
                WhipVec root = Whips.vec(hand.lerp(to, (double) i / n));
                WhipVec dir = hang;
                if (returned < RETURN_TICKS) {
                    double f = Math.min(1.0, (returned + (double) i / n) / RETURN_TICKS);
                    f = f * f * (3.0 - 2.0 * f);
                    span = Mth.lerp(f, 1.0, whip.idleLength);
                    cords[0].rest = Mth.lerp(f, fullRest, idleRest);
                    root = root.addScaled(reach, 1.0 - f);
                    dir = reachDir.lerp(hang, f).normalize(hang);
                }
                cords[0].step(root, dir, 0.05 / n, WhipCord.IDLE_GUIDE, t.lag(), t.stiffness(), Math.pow(0.86, 1.0 / n), t.gravity(), solids);
            }
            if (returned < RETURN_TICKS) {
                returned++;
            }
            hand = to;
            snapshot(now);
        }
    }

    /** Swings in progress, by player entity id. */
    private static final Map<Integer, Swing> SWINGS = new ConcurrentHashMap<>();
    /** Idle cords of held whips, by player entity id. */
    private static final Map<Integer, Idle> IDLE = new ConcurrentHashMap<>();

    /** Held whips shown coiled between swings ({@link WhipDefinition#idleCoil}), by player entity id. */
    private static final Map<Integer, WhipDefinition> COILED = new ConcurrentHashMap<>();

    /** A crack's ring ({@link WhipDefinition#shockwave}): where, which way the cord ran, colour, and the tick it began. */
    private record Ring(Vec3 at, Vec3 normal, int color, long start, boolean own, double size) {}
    private static final java.util.List<Ring> RINGS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final int RING_TICKS = 8;
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath("fundamentum", "textures/misc/white.png");

    /** Players charging a slam ({@link WhipSlam}): the whip and the game time the charge began. */
    private record Charging(WhipDefinition whip, long start) {}
    private static final Map<Integer, Charging> CHARGING = new ConcurrentHashMap<>();
    /** The whip at each player's hip ({@link WhipHip}), by player entity id, and where the model drew the hip this frame. */
    private static final Map<Integer, ResourceLocation> HIP = new ConcurrentHashMap<>();
    private record HipAt(Vec3 at, Vec3 forward, Vec3 out, Vec3 down) {}
    private static final Map<Integer, HipAt> HIPS = new ConcurrentHashMap<>();
    /** Whether this client last told the server use was down with a slam whip. */
    private static boolean useSent;

    static void charge(int entity, boolean charging) {
        ClientLevel level = Minecraft.getInstance().level;
        WhipDefinition whip = level != null && level.getEntity(entity) instanceof Player p ? Whips.definition(p.getMainHandItem()) : null;
        if (charging && whip != null) {
            CHARGING.put(entity, new Charging(whip, level.getGameTime()));
        } else {
            CHARGING.remove(entity);
        }
    }

    static void hip(int entity, @Nullable ResourceLocation item) {
        if (item == null) {
            HIP.remove(entity);
        } else {
            HIP.put(entity, item);
        }
    }

    /** A slam landed ({@link WhipSlam.SlamPayload}): a flat ring on the ground, and the slammer's view shakes. */
    static void slammed(int entity, Vec3 at, float radius, int color) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        boolean own = mc.player != null && mc.player.getId() == entity;
        RINGS.add(new Ring(at.add(0, 0.08, 0), new Vec3(0, 1, 0), color, mc.level.getGameTime(), own, Math.max(1.0, radius / 1.55)));
        if (own) {
            shake = 1.0F;
        }
    }

    /** Use (right click) with a slam whip charges instead of using the item or the block. */
    @SubscribeEvent
    public static void use(InputEvent.InteractionKeyMappingTriggered event) {
        Player player = Minecraft.getInstance().player;
        WhipDefinition whip = player == null ? null : Whips.definition(player.getMainHandItem());
        if (event.isUseItem() && event.getHand() == InteractionHand.MAIN_HAND && whip != null && whip.slam != null) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /** Client looks of whips, by item id (see {@link WhipStyle}). */
    private static final Map<ResourceLocation, WhipStyle> STYLES = new ConcurrentHashMap<>();

    /**
     * Gives the whip on {@code item} its own client look: geometry along the cord, crack and strike effects. Call from
     * client-only code (a class only the client loads) while the mod is constructed or in client setup.
     */
    public static void style(ResourceLocation item, WhipStyle style) {
        STYLES.put(item, style);
    }

    /** A swing announced by the server ({@link Whips.SwingPayload}). */
    static void start(int entity, ResourceLocation item, java.util.List<Vector3fc> looks, int side) {
        ClientLevel level = Minecraft.getInstance().level;
        WhipDefinition whip = Whips.definition(item);
        if (level == null || whip == null || !(level.getEntity(entity) instanceof Player player)) {
            return;
        }
        if (looks.isEmpty()) {
            return;
        }
        WhipVec[] aims = new WhipVec[looks.size()];
        for (int k = 0; k < aims.length; k++) {
            aims[k] = new WhipVec(looks.get(k).x(), looks.get(k).y(), looks.get(k).z());
        }
        WhipSwing swing = new WhipSwing(whip.tuning, aims, side);
        Vec3 hand = Whips.hand(player, 1.0F);
        swing.start(Whips.vec(hand), Whips.solids(level));
        SWINGS.put(entity, new Swing(item, whip, swing, hand));
        IDLE.remove(entity);
    }

    /** True while the player's whip is out (mid-swing), e.g. to hide a coiled-whip item model. */
    public static boolean swinging(Entity player) {
        return SWINGS.containsKey(player.getId());
    }

    /**
     * The item property {@code fundamentum:whip_swinging} (1.21.1: a model "overrides" predicate, where 26.x has an
     * item model condition): 1 while the holder's whip is out mid-swing, else 0. A whip with {@code idle_length} 0 can
     * show a coiled whip in the hand otherwise, and its bare handle (the cord leaving {@link #HANDLE_END_U},
     * {@link #HANDLE_END_V}) only while it swings:
     * <pre>"overrides": [{"predicate": {"fundamentum:whip_swinging": 1}, "model": ...}]</pre>
     * {@code fundamentum:whip_flat_handles} is 1 when {@link WhipsConfig#FLAT_HANDLES} is on. Both apply to every item.
     */
    static void registerProperties(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemProperties.registerGeneric(ResourceLocation.fromNamespaceAndPath("fundamentum", "whip_swinging"),
                (stack, level, owner, seed) -> owner != null && swinging(owner) ? 1.0F : 0.0F);
            ItemProperties.registerGeneric(ResourceLocation.fromNamespaceAndPath("fundamentum", "whip_flat_handles"),
                (stack, level, owner, seed) -> WhipsConfig.SPEC.isLoaded() && WhipsConfig.FLAT_HANDLES.getAsBoolean() ? 1.0F : 0.0F);
        });
    }

    private static boolean holds(Player player, ResourceLocation item) {
        return !player.isRemoved() && BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).equals(item);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            SWINGS.clear();
            IDLE.clear();
            return;
        }
        WhipCord.Solids solids = Whips.solids(level);
        for (Iterator<Map.Entry<Integer, Swing>> it = SWINGS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Swing> entry = it.next();
            Swing s = entry.getValue();
            if (!(level.getEntity(entry.getKey()) instanceof Player player) || !holds(player, s.item)) {
                it.remove();
                continue;
            }
            s.snapshot(s.prev);
            Vec3 hand = Whips.hand(player, 1.0F);
            WhipTuning t = s.whip.tuning;
            boolean more = s.swing.tick(Whips.vec(s.hand), Whips.vec(hand), solids, (strand, cord, tick, dt) -> {
                if (s.swing.crack(strand, cord, tick, dt)) {
                    WhipVec tip = cord.tip();
                    if (s.whip.shockwave != 0) {
                        WhipVec[] pts = cord.points;
                        Vec3 along = Whips.vec(tip).subtract(Whips.vec(pts[Math.max(0, pts.length - 3)]));
                        RINGS.add(new Ring(Whips.vec(tip), along, s.whip.shockwave, level.getGameTime(), player == mc.player, 1.0));
                    }
                    if (s.whip.crackParticle != null) {
                        for (int i = 0; i < 8; i++) {
                            level.addParticle(s.whip.crackParticle, tip.x(), tip.y(), tip.z(), (level.getRandom().nextDouble() - 0.5) * 0.4,
                                (level.getRandom().nextDouble() - 0.5) * 0.4, (level.getRandom().nextDouble() - 0.5) * 0.4);
                        }
                    }
                    WhipStyle style = STYLES.get(s.item);
                    if (style != null) {
                        style.crack(level, player, Whips.vec(tip), level.getRandom());
                    }
                }
                // The tip snapping against a block (the server plays the sound): chips of the block and a few sparks.
                if (!s.struck[strand] && tick > t.windupTicks() && cord.tipBlock != null && cord.tipImpact / dt >= t.crackSpeed() * WhipCord.STRIKE_SPEED) {
                    s.struck[strand] = true;
                    BlockState state = level.getBlockState(new BlockPos(cord.tipBlock[0], cord.tipBlock[1], cord.tipBlock[2]));
                    WhipVec tip = cord.tip();
                    RandomSource rnd = level.getRandom();
                    if (!state.isAir()) {
                        for (int i = 0; i < 8; i++) {
                            level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), tip.x(), tip.y(), tip.z(),
                                (rnd.nextDouble() - 0.5) * 0.3, rnd.nextDouble() * 0.2, (rnd.nextDouble() - 0.5) * 0.3);
                        }
                    }
                    if (s.whip.crackParticle != null) {
                        for (int i = 0; i < 4; i++) {
                            level.addParticle(s.whip.crackParticle, tip.x(), tip.y(), tip.z(), (rnd.nextDouble() - 0.5) * 0.5,
                                rnd.nextDouble() * 0.3, (rnd.nextDouble() - 0.5) * 0.5);
                        }
                    }
                    WhipStyle style = STYLES.get(s.item);
                    if (style != null) {
                        style.struck(level, player, Whips.vec(tip), state, rnd);
                    }
                }
            });
            s.hand = hand;
            s.snapshot(s.now);
            if (s.whip.particles != null) {
                for (int k = 0; k < s.now.length; k++) {
                    WhipVec[] pts = s.now[k];
                    for (int i = 0; i < pts.length; i++) {
                        double speed = pts[i].distanceTo(s.prev[k][i]) * 20.0;
                        s.whip.particles.spawn(level, k, i, (double) i / (pts.length - 1), Whips.vec(pts[i]), speed, level.getRandom());
                    }
                }
            }
            if (!more) {
                it.remove();
                if (s.whip.idleLength > 0) {
                    IDLE.put(entry.getKey(), new Idle(s, hand));
                }
            }
        }
        // Idle cords: every near player holding a whip that has one, and not swinging it.
        Entity camera = mc.getCameraEntity();
        IDLE.entrySet().removeIf(e -> !(level.getEntity(e.getKey()) instanceof Player p) || SWINGS.containsKey(e.getKey())
            || !holds(p, e.getValue().item) || camera != null && p.distanceToSqr(camera) > IDLE_RANGE * IDLE_RANGE);
        for (Player player : level.players()) {
            if (SWINGS.containsKey(player.getId()) || player.isSpectator()
                || camera != null && player.distanceToSqr(camera) > IDLE_RANGE * IDLE_RANGE) {
                continue;
            }
            WhipDefinition whip = Whips.definition(player.getMainHandItem());
            if (whip == null || whip.idleLength <= 0) {
                continue;
            }
            Vec3 hand = Whips.hand(player, 1.0F);
            Idle idle = IDLE.computeIfAbsent(player.getId(),
                id -> new Idle(BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()), whip, hand));
            idle.tick(hand, player.getLookAngle(), solids);
        }
        COILED.clear();
        for (Player player : level.players()) {
            WhipDefinition whip = Whips.definition(player.getMainHandItem());
            if (whip != null && whip.idleCoil && !SWINGS.containsKey(player.getId()) && !CHARGING.containsKey(player.getId())
                && !player.isSpectator()
                && (camera == null || player.distanceToSqr(camera) <= IDLE_RANGE * IDLE_RANGE)) {
                COILED.put(player.getId(), whip);
            }
        }
        RINGS.removeIf(r -> level.getGameTime() - r.start() > RING_TICKS * (r.size() > 1.0 ? 2 : 1));
        CHARGING.keySet().removeIf(id -> !(level.getEntity(id) instanceof Player));
        HIP.keySet().removeIf(id -> !(level.getEntity(id) instanceof Player));
        Player self = mc.player;
        WhipDefinition held = self == null ? null : Whips.definition(self.getMainHandItem());
        boolean down = held != null && held.slam != null && mc.screen == null && mc.options.keyUse.isDown();
        if (down != useSent) {
            useSent = down;
            PacketDistributor.sendToServer(new WhipSlam.UsePayload(down));
        }
    }

    /**
     * Where the cord leaves the held item: the end of the handle on a 16x16 in-hand sprite drawn with
     * {@code minecraft:item/handheld}, as a fraction across (u, from the left) and down (v, from the top); pixel (11, 4).
     * Draw a whip's in-hand sprite as its handle (and the thick start of the lash) along the usual tool diagonal,
     * ending there; keep a coiled icon for the inventory by selecting on {@code minecraft:display_context} (see
     * {@code fundamentum:example_whip}).
     */
    public static final float HANDLE_END_U = 11.5F / 16.0F, HANDLE_END_V = 4.5F / 16.0F;

    /** Where each player's model drew its held item this frame: the handheld frame's pose and the camera, from {@link Grip}. */
    private static final Map<Integer, GripAt> GRIPS = new ConcurrentHashMap<>();

    private record GripAt(Matrix4f pose, Vec3 cam) {}

    /**
     * The handle end (u, v as fractions of the handheld frame) of the whip the player holds: its own
     * {@link WhipDefinition#handleEndU} for a 3D handle, the flat sprite's end when {@code whips.flatHandles} is on.
     */
    private static float[] handleEnd(Player player) {
        WhipDefinition whip = Whips.definition(player.getMainHandItem());
        boolean flat = WhipsConfig.SPEC.isLoaded() && WhipsConfig.FLAT_HANDLES.getAsBoolean();
        if (whip == null || flat) {
            return new float[] {HANDLE_END_U, HANDLE_END_V};
        }
        return new float[] {whip.handleEndU / 16.0F, whip.handleEndV / 16.0F};
    }

    /**
     * Records where the player model's main hand is, posed as drawn (walking, crouching, swinging, swimming, other mods'
     * animations), at the point vanilla puts a held item. Players are submitted before the custom geometry, so the
     * cord's handle is drawn in the hand of the same frame.
     */
    private static final class Grip extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        Grip(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> renderer) {
            super(renderer);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffers, int light, AbstractClientPlayer state, float limbSwing, float limbSwingAmount,
                           float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            if (HIP.containsKey(state.getId())) {
                // The hip on the main arm's side, at the belt (model space: y down from the neck, -z forward).
                poseStack.pushPose();
                getParentModel().body.translateAndRotate(poseStack);
                int side = state.getMainArm() == HumanoidArm.LEFT ? 1 : -1;
                Matrix4f m = poseStack.last().pose();
                Vector3f at = m.transformPosition(side * 4.6F / 16.0F, 11.0F / 16.0F, -0.5F / 16.0F, new Vector3f());
                Vector3f fwd = m.transformDirection(0, 0, -1, new Vector3f()).normalize();
                Vector3f out = m.transformDirection(side, 0, 0, new Vector3f()).normalize();
                Vector3f dn = m.transformDirection(0, 1, 0, new Vector3f()).normalize();
                poseStack.popPose();
                Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                HIPS.put(state.getId(), new HipAt(cam.add(at.x, at.y, at.z), new Vec3(fwd), new Vec3(out), new Vec3(dn)));
            }
            if (!SWINGS.containsKey(state.getId()) && !IDLE.containsKey(state.getId()) && !COILED.containsKey(state.getId()) && !CHARGING.containsKey(state.getId())) {
                return;
            }
            // As ItemInHandLayer places the held item.
            poseStack.pushPose();
            getParentModel().translateToHand(state.getMainArm(), poseStack);
            poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            int invert = state.getMainArm() == HumanoidArm.LEFT ? -1 : 1;
            poseStack.translate(invert / 16.0F, 2 / 16.0F, -10 / 16.0F);
            // Then minecraft:item/handheld's third-person transform, to the end of the handle sprite. The left hand's
            // entry is the right's mirrored ([0, 90, -55]), and vanilla negates its y and z turns again for a left hand
            // (ItemTransform.apply), so either hand is drawn turned [0, -90, 55] (1.25.4; before, the left hand's cord
            // started beside the handle).
            poseStack.translate(0, 4.0F / 16.0F, 0.5F / 16.0F);
            poseStack.mulPose(new Quaternionf().rotationXYZ(0, -90.0F * Mth.DEG_TO_RAD, 55.0F * Mth.DEG_TO_RAD));
            poseStack.scale(0.85F, 0.85F, 0.85F);
            Matrix4f pose = new Matrix4f(poseStack.last().pose());
            poseStack.popPose();
            GRIPS.put(state.getId(), new GripAt(pose, Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()));
        }
    }

    static void addLayers(EntityRenderersEvent.AddLayers event) {
        for (PlayerSkin.Model skin : event.getSkins()) {
            if (event.getSkin(skin) instanceof PlayerRenderer renderer) {
                renderer.addLayer(new Grip(renderer));
            }
        }
    }

    /** The mod bus half (1.21.1's subscribers do not pick their bus by the event). */
    @EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    static final class ModBus {
        @SubscribeEvent
        public static void addLayers(EntityRenderersEvent.AddLayers event) {
            WhipsClient.addLayers(event);
        }

        @SubscribeEvent
        public static void setup(FMLClientSetupEvent event) {
            registerProperties(event);
            com.fundamentum.pose.FirstPersonHands.registerFrame(new com.fundamentum.pose.FirstPersonHands.Frame() {
                @Override
                public boolean place(PoseStack pose, net.minecraft.client.player.AbstractClientPlayer player, net.minecraft.world.item.ItemStack stack,
                                     HumanoidArm arm, float pt, float equip) {
                    return false;
                }

                @Override
                public com.fundamentum.pose.PosePlayer.FirstPerson firstPerson(net.minecraft.world.item.ItemStack stack) {
                    return Whips.definition(stack) != null ? FIRST_PERSON : null;
                }
            });
        }
    }

    /**
     * The player's own arm holding a whip in first person (1.25.4), through
     * {@link com.fundamentum.pose.FirstPersonHands}: the fist closed on the middle of the grip of a 3D handle held as
     * {@code minecraft:item/handheld} (Ostentum's handle3d handles; a flat handle sprite's grip is about there too),
     * pushed 2 pixels on so the handle runs through the fist. A whip item's own player_pose binding with a
     * "first_person" block wins over this.
     */
    public static final com.fundamentum.pose.PosePlayer.FirstPerson FIRST_PERSON =
        new com.fundamentum.pose.PosePlayer.FirstPerson("grip", new float[] {1.13F, -0.86F, -0.35F}, null, null, true, 2.0F);

    /**
     * Where the handle is drawn: the end of the held handle sprite ({@link #HANDLE_END_U}). In first person from
     * {@link #firstPersonHandle}; the held item is drawn with
     * its own fixed FOV, so the offsets across the view are scaled by {@code fovScale} to land on it whatever the world
     * FOV is (sprinting, zooming, the FOV setting). Otherwise the model's hand from {@link Grip}, or the estimate.
     */
    private static Vec3 drawnHand(Player player, float pt, double fovScale) {
        Minecraft mc = Minecraft.getInstance();
        if (player == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson()) {
            float[] end = handleEnd(player);
            Vector3f v = firstPersonHandle(player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1, player.getAttackAnim(pt), end[0], end[1]);
            Vec3 look = player.getViewVector(pt);
            Vec3 right = look.cross(new Vec3(0, 1, 0));
            right = right.lengthSqr() < 1.0E-8 ? new Vec3(1, 0, 0) : right.normalize();
            Vec3 up = right.cross(look).normalize();
            // View space: x right, y up, z back toward the eye.
            return player.getEyePosition(pt).add(right.scale(v.x * fovScale)).add(up.scale(v.y * fovScale)).add(look.scale(-v.z));
        }
        GripAt grip = GRIPS.get(player.getId());
        if (grip == null) {
            return Whips.hand(player, pt);
        }
        float[] end = handleEnd(player);
        Vector3f at = grip.pose().transformPosition(end[0] - 0.5F, 0.5F - end[1], 0, new Vector3f());
        return grip.cam().add(at.x, at.y, at.z);
    }

    /**
     * The handle end in first-person view space, as ItemInHandRenderer draws a held item (the arm's place, the attack
     * swing, then {@code minecraft:item/handheld}'s first-person transform). Equip and view-bob sway are left out.
     */
    static Vector3f firstPersonHandle(int invert, float attack, float endU, float endV) {
        PoseStack pose = new PoseStack();
        pose.translate(invert * 0.56F, -0.52F, -0.72F);
        float root = Mth.sqrt(attack);
        pose.translate(invert * -0.4F * Mth.sin(root * Mth.PI), 0.2F * Mth.sin(root * Mth.PI * 2), -0.2F * Mth.sin(attack * Mth.PI));
        pose.mulPose(Axis.YP.rotationDegrees(invert * (45.0F + Mth.sin(attack * attack * Mth.PI) * -20.0F)));
        pose.mulPose(Axis.ZP.rotationDegrees(invert * Mth.sin(root * Mth.PI) * -20.0F));
        pose.mulPose(Axis.XP.rotationDegrees(Mth.sin(root * Mth.PI) * -80.0F));
        pose.mulPose(Axis.YP.rotationDegrees(invert * -45.0F));
        // A left hand: vanilla negates the x shift and the y and z turns of the (already mirrored) left-hand entry, so
        // the shift is mirrored and the turn is the right hand's (1.25.4).
        pose.translate(invert * 1.13F / 16.0F, 3.2F / 16.0F, 1.13F / 16.0F);
        pose.mulPose(new Quaternionf().rotationXYZ(0, -90.0F * Mth.DEG_TO_RAD, 25.0F * Mth.DEG_TO_RAD));
        pose.scale(0.68F, 0.68F, 0.68F);
        return pose.last().pose().transformPosition(endU - 0.5F, 0.5F - endV, 0, new Vector3f());
    }

    /** One frame's drawing (1.21.1): the level pose stack at the camera and the buffers, in place of 26.x's collector. */
    private record Frame(PoseStack pose, MultiBufferSource buffers) {}

    private static void geom(Frame frame, RenderType type, java.util.function.BiConsumer<PoseStack.Pose, VertexConsumer> draw) {
        draw.accept(frame.pose().last(), frame.buffers().getBuffer(type));
    }

    /** The world FOV this frame (ViewportEvent.ComputeFov), for scaling first-person offsets to the hand's fixed 70. */
    private static double worldFov = 70.0;

    @SubscribeEvent
    public static void fov(ViewportEvent.ComputeFov event) {
        if (event.usedConfiguredFov()) {
            worldFov = event.getFOV();
        }
    }

    @SubscribeEvent
    public static void draw(RenderLevelStageEvent stage) {
        // After the entities, so every player's Grip layer has run this frame.
        if (stage.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        if (SWINGS.isEmpty() && IDLE.isEmpty() && COILED.isEmpty() && RINGS.isEmpty() && CHARGING.isEmpty() && HIP.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        float pt = mc.getTimer().getGameTimeDeltaPartialTick(false);
        Vec3 cam = stage.getCamera().getPosition();
        double fovScale = Math.tan(Math.toRadians(worldFov / 2.0)) / Math.tan(Math.toRadians(70.0 / 2.0));
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Frame event = new Frame(stage.getPoseStack(), buffers);
        SWINGS.forEach((id, s) -> draw(event, level, id, s, pt, cam, fovScale));
        IDLE.forEach((id, s) -> draw(event, level, id, s, pt, cam, fovScale));
        COILED.forEach((id, whip) -> coil(event, level, id, whip, pt, cam, fovScale));
        CHARGING.forEach((id, c) -> spin(event, level, id, c, pt, cam, fovScale));
        HIP.forEach((id, item) -> {
            WhipDefinition whip = Whips.definition(item);
            HipAt at = HIPS.get(id);
            if (whip != null && at != null && level.getEntity(id) instanceof Player player && !player.isInvisible()
                && !(player == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson())) {
                coilAt(event, level, player, item, whip, at.at(), at.forward(), at.out(), at.down(), 0.75 * player.getScale(), cam);
            }
        });
        HIPS.clear();
        for (Ring r : RINGS) {
            ring(event, r, level.getGameTime() + pt, cam);
        }
        GRIPS.clear();
        buffers.endLastBatch();
    }

    /**
     * A held whip's cord coiled between swings: it leaves the handle end and winds into three loops hanging below it,
     * each a little further out from the body, then a short loose end, like a coiled rope at the hip.
     */
    private static void coil(Frame event, ClientLevel level, int id, WhipDefinition whip, float pt, Vec3 cam,
                             double fovScale) {
        if (!(level.getEntity(id) instanceof Player player) || player.isInvisible()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean firstPerson = player == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();
        Vec3 start = drawnHand(player, pt, fovScale);
        float yaw = Mth.lerp(pt, player.yBodyRotO, player.yBodyRot) * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        Vec3 out = new Vec3(-Mth.cos(yaw), 0, -Mth.sin(yaw)).scale(player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1);
        coilAt(event, level, player, BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()), whip, start, forward, out,
            new Vec3(0, -1, 0), (firstPerson ? 0.55 : 1.0) * player.getScale(), cam);
    }

    /**
     * Three loops hanging from {@code start} in the forward/down plane, each a little further {@code out}, then a short
     * loose end: the held coil (under the handle) and the hip coil both.
     */
    private static void coilAt(Frame event, ClientLevel level, Player player, ResourceLocation item, WhipDefinition whip,
                               Vec3 start, Vec3 forward, Vec3 out, Vec3 down, double scale, Vec3 cam) {
        double r = 0.15 * scale;
        int turns = 3, per = 14, tail = 4;
        int n = turns * per + 1 + tail;
        Vec3 centre = start.add(down.scale(r * 1.15));
        Vec3[] world = new Vec3[n];
        Vec3[] p = new Vec3[n];
        int[] light = new int[n];
        for (int i = 0; i < n; i++) {
            if (i <= turns * per) {
                double a = Mth.TWO_PI * i / per;
                // From the top of the loop (the handle end), round and slightly tall, stepping outward each turn.
                world[i] = centre.add(down.scale(-Math.cos(a) * r * 1.15)).add(forward.scale(Math.sin(a) * r))
                    .add(out.scale(0.03 * scale * i / per));
            } else {
                int j = i - turns * per;
                world[i] = world[turns * per].add(down.scale(0.06 * scale * j)).add(forward.scale(0.01 * scale * j));
            }
            p[i] = world[i].subtract(cam);
            light[i] = LevelRenderer.getLightColor(level, BlockPos.containing(world[i]));
        }
        styled(event, player, item, whip, p, light, cam);
    }

    /**
     * Draws a coiled or whirling cord (not a swing's) like a swing's: the ribbon unless the item's style leaves it out,
     * then the style's own geometry (1.22.1; before, coils and slam spins drew the ribbon alone, so glowing parts did not
     * glow there).
     */
    private static void styled(Frame event, Player player, ResourceLocation item, WhipDefinition whip, Vec3[] p, int[] light,
                               Vec3 cam) {
        WhipStyle style = STYLES.get(item);
        if (style == null || style.ribbon()) {
            geom(event, WhipShaders.cord(whip),
                (at, buffer) -> ribbon(at, buffer, p, light, whip, 1.0));
        }
        haloed(event, whip, p, 1.0);
        if (style != null) {
            style.draw(new WhipStyle.Cord(player, whip, 0, p, light, 1.0, false, cam), event.pose(), event.buffers());
        }
    }

    /**
     * A slam charging: the cord rises from the hand and whirls in a ring over the head, faster and wider as the charge
     * builds (full at {@link WhipDefinition.Slam#chargeTicks}).
     */
    private static void spin(Frame event, ClientLevel level, int id, Charging c, float pt, Vec3 cam, double fovScale) {
        if (!(level.getEntity(id) instanceof Player player) || player.isInvisible() || c.whip().slam == null) {
            return;
        }
        double held = level.getGameTime() - c.start() + pt;
        double charge = Math.min(1.0, held / c.whip().slam.chargeTicks());
        Vec3 hand = drawnHand(player, pt, fovScale);
        double scale = player.getScale();
        Vec3 top = player.getPosition(pt).add(0, (player.getBbHeight() + 0.45) * scale, 0);
        double radius = Math.min(1.4, c.whip().tuning.length() * 0.22) * (0.55 + 0.45 * charge) * scale;
        double angle = held * (0.45 + 0.5 * charge);
        int rise = 6, round = 26;
        int n = rise + round;
        Vec3[] p = new Vec3[n];
        int[] light = new int[n];
        Vec3 first = top.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
        for (int i = 0; i < n; i++) {
            Vec3 w;
            if (i < rise) {
                double f = (double) i / rise;
                w = hand.lerp(first, f).add(0, Math.sin(f * Math.PI) * 0.15, 0);
            } else {
                // Trailing back round the ring from the leading point, dipping a little as it goes.
                double a = angle - (i - rise) * (Mth.TWO_PI * 0.9 / round);
                w = top.add(Math.cos(a) * radius, -0.12 * (i - rise) / round, Math.sin(a) * radius);
            }
            p[i] = w.subtract(cam);
            light[i] = LevelRenderer.getLightColor(level, BlockPos.containing(w));
        }
        styled(event, player, BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()), c.whip(), p, light, cam);
    }

    /** A crack's ring: a flat band across the cord's line, widening and fading over {@link #RING_TICKS}. */
    private static void ring(Frame event, Ring r, double now, Vec3 cam) {
        double t = (now - r.start()) / (RING_TICKS * (r.size() > 1.0 ? 2 : 1));
        if (t < 0 || t > 1) {
            return;
        }
        // A thin, soft band (1.22.1): clear at both edges, brightest in the middle, eased out and faded fast.
        double ease = 1 - (1 - t) * (1 - t) * (1 - t);
        double radius = (0.12 + 0.95 * ease) * r.size();
        double band = (0.08 * (1 - t) + 0.02) * Math.sqrt(r.size());
        int alpha = (int) (((r.color() >>> 24) & 0xFF) * 0.65 * (1 - t) * (1 - t));
        int mid = (alpha << 24) | (r.color() & 0xFFFFFF);
        int edge = r.color() & 0xFFFFFF;
        Vec3 c = r.at().subtract(cam);
        Vec3 n = r.normal().lengthSqr() < 1.0E-8 ? c : r.normal();
        n = n.lengthSqr() < 1.0E-8 ? new Vec3(0, 1, 0) : n.normalize();
        Vec3 a = n.cross(Math.abs(n.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
        Vec3 b = n.cross(a).normalize();
        Vec3 toEye = c.lengthSqr() < 1.0E-8 ? new Vec3(0, 1, 0) : c.scale(-1).normalize();
        int seg = 40;
        double inner = Math.max(0, radius - band), middle = radius - band * 0.5;
        geom(event, RenderType.entityTranslucentEmissive(WHITE),
            (at, buffer) -> {
                for (int i = 0; i < seg; i++) {
                    double a0 = Mth.TWO_PI * i / seg, a1 = Mth.TWO_PI * (i + 1) / seg;
                    Vec3 d0 = a.scale(Math.cos(a0)).add(b.scale(Math.sin(a0))), d1 = a.scale(Math.cos(a1)).add(b.scale(Math.sin(a1)));
                    Vec3 i0 = c.add(d0.scale(inner)), i1 = c.add(d1.scale(inner));
                    Vec3 m0 = c.add(d0.scale(middle)), m1 = c.add(d1.scale(middle));
                    Vec3 o0 = c.add(d0.scale(radius)), o1 = c.add(d1.scale(radius));
                    // Inner half fading in, outer half fading out; both faces, so it shows from either side.
                    Vec3[][] quads = {{i0, m0, m1, i1}, {m0, o0, o1, m1}};
                    int[][] colors = {{edge, mid, mid, edge}, {mid, edge, edge, mid}};
                    for (int q = 0; q < 2; q++) {
                        for (int k = 0; k < 4; k++) {
                            vertex(at, buffer, quads[q][k], colors[q][k], 0.5F, 0.5F, LightTexture.FULL_BRIGHT, toEye);
                        }
                        for (int k = 3; k >= 0; k--) {
                            vertex(at, buffer, quads[q][k], colors[q][k], 0.5F, 0.5F, LightTexture.FULL_BRIGHT, toEye);
                        }
                    }
                }
            });
        if (r.own() && t < 0.35) {
            // A crack's shake is a light tap; a slam (size above 1) keeps the full one.
            shake = Math.max(shake, (float) (1 - t / 0.35) * (r.size() > 1.0 ? 1.0F : 0.3F));
        }
    }

    /** The local player's own crack shakes the view a little, fading (0..1). */
    private static float shake;

    @SubscribeEvent
    public static void shake(ViewportEvent.ComputeCameraAngles event) {
        if (shake <= 0.01F) {
            shake = 0;
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        double time = event.getPartialTick() + (level == null ? 0 : level.getGameTime());
        event.setPitch(event.getPitch() + (float) (Math.sin(time * 9.1) * 0.6 * shake));
        event.setRoll(event.getRoll() + (float) (Math.sin(time * 7.3) * 0.5 * shake));
        shake *= 0.85F;
    }

    private static void draw(Frame event, ClientLevel level, int id, Shown s, float pt, Vec3 cam, double fovScale) {
        if (!(level.getEntity(id) instanceof Player player) || player.isInvisible()) {
            return;
        }
        Vec3 drawn = drawnHand(player, pt, fovScale);
        for (int k = 0; k < s.now.length; k++) {
            int n = s.now[k].length;
            // The cord's root sits WhipSwing.HANDLE_REACH out from the hand (hits use it); drawn, it starts at the end of
            // the held handle, and the difference fades out along the cord.
            Vec3 handShift = drawn.subtract(Whips.vec(s.prev[k][0].lerp(s.now[k][0], pt)));
            Vec3[] p = new Vec3[n];
            int[] light = new int[n];
            for (int i = 0; i < n; i++) {
                double ease = 1.0 - (double) i / (n - 1);
                Vec3 world = Whips.vec(s.prev[k][i].lerp(s.now[k][i], pt)).add(handShift.scale(ease * ease));
                p[i] = world.subtract(cam);
                light[i] = LevelRenderer.getLightColor(level, BlockPos.containing(world));
            }
            WhipStyle style = STYLES.get(s.item);
            if (style == null || style.ribbon()) {
                geom(event, WhipShaders.cord(s.whip),
                    (at, buffer) -> ribbon(at, buffer, p, light, s.whip, s.span));
            }
            haloed(event, s.whip, p, s.span);
            if (style != null) {
                style.draw(new WhipStyle.Cord(player, s.whip, k, p, light, s.span, s instanceof Swing, cam), event.pose(),
                    event.buffers());
            }
        }
    }

    /**
     * A camera-facing strip through the points (camera-relative), u across, v along. The points are the first
     * {@code span} of the cord (the handle end), so width, colour and v follow the whole cord's.
     */
    private static void ribbon(PoseStack.Pose at, VertexConsumer buffer, Vec3[] p, int[] light, WhipDefinition whip, double span) {
        int n = p.length;
        Vec3[] left = new Vec3[n], right = new Vec3[n];
        Vec3[] normal = new Vec3[n];
        // Under a shader pack the cord shader is replaced: animate the colours (and the lightning's jag) here (1.25.3).
        boolean pack = whip.cordFx != WhipCordFx.NONE && whip.cordFx != WhipCordFx.GLOW && com.fundamentum.client.Emissive.shadersOn();
        double secs = net.minecraft.Util.getMillis() / 1000.0;
        for (int i = 0; i < n; i++) {
            Vec3 tangent = p[Math.min(n - 1, i + 1)].subtract(p[Math.max(0, i - 1)]);
            Vec3 toEye = p[i].scale(-1);
            Vec3 side = tangent.cross(toEye);
            side = side.lengthSqr() < 1.0E-10 ? new Vec3(0, 1, 0) : side.normalize();
            float t = (float) (span * i / (n - 1));
            double half = Mth.lerp(t, whip.width, whip.tipWidth) * 0.5;
            Vec3 mid = pack ? p[i].add(side.scale(WhipCordShade.jag(whip.cordFx, t, secs) * half * 2)) : p[i];
            left[i] = mid.add(side.scale(half));
            right[i] = mid.subtract(side.scale(half));
            normal[i] = toEye.lengthSqr() < 1.0E-10 ? new Vec3(0, 1, 0) : toEye.normalize();
        }
        for (int i = 0; i < n - 1; i++) {
            float v0 = (float) (span * i / (n - 1)), v1 = (float) (span * (i + 1) / (n - 1));
            int c0 = Argb.srgbLerp(v0, whip.color, whip.tipColor), c1 = Argb.srgbLerp(v1, whip.color, whip.tipColor);
            if (pack) {
                c0 = WhipCordShade.shade(whip.cordFx, c0, v0, secs);
                c1 = WhipCordShade.shade(whip.cordFx, c1, v1, secs);
            }
            vertex(at, buffer, left[i], c0, 0, v0, light[i], normal[i]);
            vertex(at, buffer, right[i], c0, 1, v0, light[i], normal[i]);
            vertex(at, buffer, right[i + 1], c1, 1, v1, light[i + 1], normal[i + 1]);
            vertex(at, buffer, left[i + 1], c1, 0, v1, light[i + 1], normal[i + 1]);
        }
    }

    /** The whip's halo colour (ARGB, 0 for none), resolving {@link WhipDefinition#AUTO_GLOW}. */
    public static int glowColor(WhipDefinition whip) {
        if (whip.glow != WhipDefinition.AUTO_GLOW) {
            return whip.glow;
        }
        return switch (whip.cordFx) {
            case GLOW, ENERGY, LIGHTNING, FIRE -> {
                int tint = Argb.multiply(textureColor(whip.texture), Argb.srgbLerp(0.5F, whip.color, whip.tipColor));
                yield 0xA0000000 | (tint & 0xFFFFFF);
            }
            default -> 0;
        };
    }

    private static final Map<ResourceLocation, Integer> TEXTURE_COLORS = new ConcurrentHashMap<>();

    /** The mean colour of a texture's opaque texels, brightened to full value so the halo reads as light (white if unreadable). */
    private static int textureColor(ResourceLocation texture) {
        return TEXTURE_COLORS.computeIfAbsent(texture, t -> {
            try (var in = Minecraft.getInstance().getResourceManager().open(t); NativeImage image = NativeImage.read(in)) {
                long r = 0, g = 0, b = 0, n = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int c = Argb.fromAbgr(image.getPixelRGBA(x, y));
                        if (Argb.alpha(c) > 128) {
                            r += Argb.red(c);
                            g += Argb.green(c);
                            b += Argb.blue(c);
                            n++;
                        }
                    }
                }
                if (n == 0) {
                    return 0xFFFFFFFF;
                }
                float max = Math.max(1, Math.max(r, Math.max(g, b)) / (float) n);
                return Argb.color(255, (int) (r / n * 255 / max), (int) (g / n * 255 / max), (int) (b / n * 255 / max));
            } catch (Exception e) {
                return 0xFFFFFFFF;
            }
        });
    }

    private static void haloed(Frame event, WhipDefinition whip, Vec3[] p, double span) {
        int color = glowColor(whip);
        if (Argb.alpha(color) > 0) {
            geom(event, WhipShaders.halo(),
                (at, buffer) -> halo(at, buffer, p, span, Math.max(0.16, whip.width * 3.5), Math.max(0.08, whip.tipWidth * 3.5), color));
        }
    }

    /**
     * A soft halo strip along camera-relative points (1.22.2), for {@link WhipShaders#halo}: {@code width} wide at the
     * handle to {@code tipWidth} at a full cord's tip, in {@code argb} (alpha is its strength). Public for styles that
     * glow along their own geometry.
     */
    public static void halo(PoseStack.Pose at, VertexConsumer buffer, Vec3[] p, double span, double width, double tipWidth, int argb) {
        argb = com.fundamentum.client.Emissive.packGlow(argb);
        int n = p.length;
        for (int i = 0; i < n - 1; i++) {
            Vec3[] edge = new Vec3[4];
            for (int k = 0; k < 2; k++) {
                int j = i + k;
                Vec3 tangent = p[Math.min(n - 1, j + 1)].subtract(p[Math.max(0, j - 1)]);
                Vec3 side = tangent.cross(p[j].scale(-1));
                side = side.lengthSqr() < 1.0E-10 ? new Vec3(0, 1, 0) : side.normalize();
                double half = Mth.lerp(span * j / (n - 1), width, tipWidth) * 0.5;
                edge[k * 2] = p[j].add(side.scale(half));
                edge[k * 2 + 1] = p[j].subtract(side.scale(half));
            }
            Vec3 toEye = p[i].lengthSqr() < 1.0E-10 ? new Vec3(0, 1, 0) : p[i].scale(-1).normalize();
            vertex(at, buffer, edge[0], argb, 0, 0.5F, LightTexture.FULL_BRIGHT, toEye);
            vertex(at, buffer, edge[1], argb, 1, 0.5F, LightTexture.FULL_BRIGHT, toEye);
            vertex(at, buffer, edge[3], argb, 1, 0.5F, LightTexture.FULL_BRIGHT, toEye);
            vertex(at, buffer, edge[2], argb, 0, 0.5F, LightTexture.FULL_BRIGHT, toEye);
        }
    }

    /** A round soft glow facing the camera at camera-relative {@code c} (1.22.2), for {@link WhipShaders#halo}. */
    public static void glowDot(PoseStack.Pose at, VertexConsumer buffer, Vec3 c, double radius, int argb) {
        argb = com.fundamentum.client.Emissive.packGlow(argb);
        Vec3 toEye = c.lengthSqr() < 1.0E-10 ? new Vec3(0, 0, 1) : c.scale(-1).normalize();
        Vec3 a = toEye.cross(Math.abs(toEye.y) < 0.95 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize().scale(radius);
        Vec3 b = toEye.cross(a).normalize().scale(radius);
        vertex(at, buffer, c.subtract(a).subtract(b), argb, 0, 0, LightTexture.FULL_BRIGHT, toEye);
        vertex(at, buffer, c.add(a).subtract(b), argb, 1, 0, LightTexture.FULL_BRIGHT, toEye);
        vertex(at, buffer, c.add(a).add(b), argb, 1, 1, LightTexture.FULL_BRIGHT, toEye);
        vertex(at, buffer, c.subtract(a).add(b), argb, 0, 1, LightTexture.FULL_BRIGHT, toEye);
    }

    private static void vertex(PoseStack.Pose at, VertexConsumer buffer, Vec3 p, int color, float u, float v, int light, Vec3 n) {
        buffer.addVertex(at, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light).setNormal(at, (float) n.x, (float) n.y, (float) n.z);
    }

    private WhipsClient() {}
}
