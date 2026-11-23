package com.fundamentum.whip;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

/**
 * Whip effects any whip's hooks can fire (1.22.0): {@link #beam} draws a straight or jagged bolt of light between two
 * points for a few ticks (lasers, chain lightning; drawing only), and {@link #bolt} launches a small glowing projectile
 * that may home on a target and hurts what it meets (homing gems, crystal shards). Bolts are simulated on the server and
 * mirrored by every client near them; they are not entities, so they need no registration and are not saved.
 */
public final class WhipFx {
    /**
     * A bolt's numbers: blocks per tick, how hard it turns toward its target (0 straight, 1 snaps), how long it lives,
     * the damage, its colour (ARGB) and size (blocks), and whether blocks stop it.
     */
    public record BoltSpec(double speed, double turn, int lifeTicks, float damage, int color, float size, boolean blocks) {
        public static BoltSpec homing(float damage, int color) {
            return new BoltSpec(0.7, 0.18, 40, damage, color, 0.22F, true);
        }

        public static BoltSpec shard(float damage, int color) {
            return new BoltSpec(0.9, 0.0, 14, damage, color, 0.16F, true);
        }
    }

    private static final class Bolt {
        final int id;
        final ServerPlayer owner;
        final ServerLevel level;
        final BoltSpec spec;
        final @Nullable Entity target;
        final DamageSource source;
        final @Nullable BiConsumer<Vec3, @Nullable LivingEntity> onEnd;
        Vec3 pos, vel;
        int age;

        Bolt(int id, ServerPlayer owner, Vec3 pos, Vec3 vel, @Nullable Entity target, BoltSpec spec, DamageSource source,
             @Nullable BiConsumer<Vec3, @Nullable LivingEntity> onEnd) {
            this.id = id;
            this.owner = owner;
            this.level = owner.serverLevel();
            this.pos = pos;
            this.vel = vel;
            this.target = target;
            this.spec = spec;
            this.source = source;
            this.onEnd = onEnd;
        }
    }

    private static final boolean TRACE = Boolean.getBoolean("fundamentum.whiptest");
    private static final List<Bolt> BOLTS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger IDS = new AtomicInteger();

    /** A beam from {@code from} to {@code to} for {@code ticks}, {@code width} blocks wide; jagged for lightning. */
    public static void beam(ServerLevel level, Vec3 from, Vec3 to, int argb, float width, int ticks, boolean jagged) {
        Vec3 mid = from.add(to).scale(0.5);
        PacketDistributor.sendToPlayersNear(level, null, mid.x, mid.y, mid.z, 96,
            new BeamPayload(from.toVector3f(), to.toVector3f(), argb, width, ticks, jagged));
    }

    /**
     * Launches a bolt from {@code from} along {@code direction} (normalised here), turning toward {@code target} if given.
     * It hurts the first creature the owner's whips may hit (not spared, see {@link WhipTargets#spared}) with
     * {@code source}, then {@code onEnd} gets where it ended and what it hit (null when it expired or struck a block).
     */
    public static void bolt(ServerPlayer owner, Vec3 from, Vec3 direction, @Nullable Entity target, BoltSpec spec, DamageSource source,
                            @Nullable BiConsumer<Vec3, @Nullable LivingEntity> onEnd) {
        int id = IDS.incrementAndGet();
        Vec3 vel = direction.normalize().scale(spec.speed());
        BOLTS.add(new Bolt(id, owner, from, vel, target, spec, source, onEnd));
        PacketDistributor.sendToPlayersNear(owner.serverLevel(), null, from.x, from.y, from.z, 96,
            new BoltPayload(id, from.toVector3f(), vel.toVector3f(), target == null ? -1 : target.getId(), spec.turn(), spec.lifeTicks(),
                spec.color(), spec.size()));
    }

    /** The nearest creature to {@code at} within {@code range} the player's whips may hit and see, hostiles only. */
    public static @Nullable LivingEntity nearestHostile(ServerPlayer player, Vec3 at, double range, @Nullable Entity except) {
        LivingEntity best = null;
        double bestD = range * range;
        for (Entity e : player.level().getEntities(player, new AABB(at, at).inflate(range))) {
            if (e == except || !(e instanceof LivingEntity l) || !WhipTargets.touchable(player, e) || !WhipTargets.isHostile(e)
                || WhipTargets.spared(player, e)) {
                continue;
            }
            double d = l.getBoundingBox().getCenter().distanceToSqr(at);
            if (d < bestD) {
                bestD = d;
                best = l;
            }
        }
        return best;
    }

    /** Hostiles within {@code range} of {@code at} the player's whips may hit, nearest first. */
    public static List<LivingEntity> hostilesNear(ServerPlayer player, Vec3 at, double range) {
        List<LivingEntity> found = new ArrayList<>();
        for (Entity e : player.level().getEntities(player, new AABB(at, at).inflate(range))) {
            if (e instanceof LivingEntity l && WhipTargets.touchable(player, e) && WhipTargets.isHostile(e) && !WhipTargets.spared(player, e)
                && l.getBoundingBox().getCenter().distanceToSqr(at) <= range * range) {
                found.add(l);
            }
        }
        found.sort(java.util.Comparator.comparingDouble(l -> l.getBoundingBox().getCenter().distanceToSqr(at)));
        return found;
    }

    private static void tick(ServerTickEvent.Post event) {
        for (Iterator<Bolt> it = BOLTS.iterator(); it.hasNext(); ) {
            Bolt b = it.next();
            if (step(b)) {
                BOLTS.remove(b);
            }
        }
    }

    /** Moves a bolt one tick; true when it is done. */
    private static boolean step(Bolt b) {
        b.age++;
        if (b.owner.isRemoved() || b.age > b.spec.lifeTicks()) {
            end(b, b.pos, null);
            return true;
        }
        if (b.target != null && b.target.isAlive() && b.spec.turn() > 0) {
            b.vel = steer(b.pos, b.vel, b.target.getBoundingBox().getCenter(), b.spec.turn(), b.spec.speed(), b.age);
        }
        Vec3 next = b.pos.add(b.vel);
        if (TRACE) {
            com.mojang.logging.LogUtils.getLogger().info("WHIPTEST bolt {} age {} at {} vel {} target {}", b.id, b.age, b.pos, b.vel,
                b.target == null ? "none" : b.target.getBoundingBox().getCenter());
        }
        if (b.spec.blocks()) {
            var hit = b.level.clip(new ClipContext(b.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, b.owner));
            if (hit.getType() != HitResult.Type.MISS) {
                next = hit.getLocation();
                LivingEntity struck = touch(b, next);
                end(b, next, struck);
                return true;
            }
        }
        LivingEntity struck = touch(b, next);
        b.pos = next;
        if (struck != null) {
            end(b, next, struck);
            return true;
        }
        return false;
    }

    /**
     * A homing bolt's next velocity. The pull grows with age and snaps on when close, so a bolt never ends up circling
     * its target (a fixed pull at this speed orbits it). Client mirrors call this too, so both sides agree.
     */
    static Vec3 steer(Vec3 pos, Vec3 vel, Vec3 aim, double turn, double speed, int age) {
        Vec3 to = aim.subtract(pos);
        double pull = to.lengthSqr() < 2.25 ? 1.0 : Math.min(1.0, turn * (1.0 + age * 0.25));
        return vel.lerp(to.normalize().scale(speed), pull).normalize().scale(speed);
    }

    private static @Nullable LivingEntity touch(Bolt b, Vec3 to) {
        AABB box = new AABB(b.pos, to).inflate(b.spec.size() * 0.5 + 0.2);
        for (Entity e : b.level.getEntities(b.owner, box)) {
            Entity whole = WhipTargets.whole(e);
            if (whole instanceof LivingEntity l && WhipTargets.touchable(b.owner, whole) && !WhipTargets.spared(b.owner, whole)) {
                if (b.spec.damage() > 0 && l.hurt(b.source, b.spec.damage())) {
                    b.owner.setLastHurtMob(l);
                }
                return l;
            }
        }
        return null;
    }

    private static void end(Bolt b, Vec3 at, @Nullable LivingEntity hit) {
        PacketDistributor.sendToPlayersNear(b.level, null, at.x, at.y, at.z, 96, new BoltEndPayload(b.id, at.toVector3f()));
        if (b.onEnd != null) {
            b.onEnd.accept(at, hit);
        }
    }

    static void init(IEventBus modBus) {
        modBus.addListener(WhipFx::payloads);
        NeoForge.EVENT_BUS.addListener(WhipFx::tick);
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToClient(BeamPayload.TYPE, BeamPayload.STREAM_CODEC, (p, c) -> WhipFxClient.beam(p));
        r.playToClient(BoltPayload.TYPE, BoltPayload.STREAM_CODEC, (p, c) -> WhipFxClient.bolt(p));
        r.playToClient(BoltEndPayload.TYPE, BoltEndPayload.STREAM_CODEC, (p, c) -> WhipFxClient.boltEnd(p));
    }

    /** {@link ByteBufCodecs#VECTOR3F} carries the read-only view; the payloads keep mutable vectors. */
    public static final StreamCodec<ByteBuf, Vector3f> V3 = ByteBufCodecs.VECTOR3F.map(Vector3f::new, v -> v);

    public record BeamPayload(Vector3f from, Vector3f to, int color, float width, int ticks, boolean jagged) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BeamPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("fundamentum", "whip_beam"));
        public static final StreamCodec<ByteBuf, BeamPayload> STREAM_CODEC = StreamCodec.composite(
            V3, BeamPayload::from, V3, BeamPayload::to, ByteBufCodecs.INT, BeamPayload::color,
            ByteBufCodecs.FLOAT, BeamPayload::width, ByteBufCodecs.VAR_INT, BeamPayload::ticks, ByteBufCodecs.BOOL, BeamPayload::jagged,
            BeamPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BoltPayload(int id, Vector3f from, Vector3f vel, int target, double turn, int life, int color, float size)
        implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BoltPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("fundamentum", "whip_bolt"));
        public static final StreamCodec<ByteBuf, BoltPayload> STREAM_CODEC = StreamCodec.of((buf, p) -> {
            // 1.21.1's StreamCodec.composite stops at six fields
            ByteBufCodecs.VAR_INT.encode(buf, p.id()); V3.encode(buf, p.from()); V3.encode(buf, p.vel());
            ByteBufCodecs.VAR_INT.encode(buf, p.target()); ByteBufCodecs.DOUBLE.encode(buf, p.turn()); ByteBufCodecs.VAR_INT.encode(buf, p.life());
            ByteBufCodecs.INT.encode(buf, p.color()); ByteBufCodecs.FLOAT.encode(buf, p.size());
        }, buf -> new BoltPayload(ByteBufCodecs.VAR_INT.decode(buf), V3.decode(buf), V3.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.DOUBLE.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.INT.decode(buf), ByteBufCodecs.FLOAT.decode(buf)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record BoltEndPayload(int id, Vector3f at) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<BoltEndPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("fundamentum", "whip_bolt_end"));
        public static final StreamCodec<ByteBuf, BoltEndPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BoltEndPayload::id, V3, BoltEndPayload::at, BoltEndPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private WhipFx() {}
}
