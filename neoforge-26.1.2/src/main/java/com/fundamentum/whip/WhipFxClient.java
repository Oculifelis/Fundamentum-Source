package com.fundamentum.whip;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/** Client side of {@link WhipFx}: beams drawn for their ticks, and bolts flown the way the server flies them. */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class WhipFxClient {
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath("fundamentum", "textures/misc/white.png");

    private record Beam(Vec3 from, Vec3 to, int color, float width, long start, int ticks, boolean jagged, long seed) {}

    private static final class Bolt {
        final int target, life, color;
        final double turn, speed;
        final float size;
        Vec3 pos, prev, vel;
        int age;

        Bolt(WhipFx.BoltPayload p) {
            pos = prev = new Vec3(p.from());
            vel = new Vec3(p.vel());
            speed = vel.length();
            target = p.target();
            turn = p.turn();
            life = p.life();
            color = p.color();
            size = p.size();
        }
    }

    private static final List<Beam> BEAMS = new CopyOnWriteArrayList<>();
    private static final Map<Integer, Bolt> BOLTS = new ConcurrentHashMap<>();

    static void beam(WhipFx.BeamPayload p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            BEAMS.add(new Beam(new Vec3(p.from()), new Vec3(p.to()), p.color(), p.width(), level.getGameTime(), p.ticks(), p.jagged(),
                level.getRandom().nextLong()));
        }
    }

    static void bolt(WhipFx.BoltPayload p) {
        BOLTS.put(p.id(), new Bolt(p));
    }

    static void boltEnd(WhipFx.BoltEndPayload p) {
        Bolt b = BOLTS.remove(p.id());
        ClientLevel level = Minecraft.getInstance().level;
        if (b != null && level != null) {
            Vec3 at = new Vec3(p.at());
            DustParticleOptions dust = new DustParticleOptions(b.color & 0xFFFFFF, 1.0F);
            RandomSource r = level.getRandom();
            for (int i = 0; i < 8; i++) {
                level.addParticle(dust, at.x, at.y, at.z, (r.nextDouble() - 0.5) * 0.3, (r.nextDouble() - 0.5) * 0.3, (r.nextDouble() - 0.5) * 0.3);
            }
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            BEAMS.clear();
            BOLTS.clear();
            return;
        }
        BEAMS.removeIf(b -> level.getGameTime() - b.start() > b.ticks());
        BOLTS.values().removeIf(b -> {
            b.age++;
            if (b.age > b.life + 5) {
                return true;
            }
            Entity target = b.target >= 0 ? level.getEntity(b.target) : null;
            if (target != null && target.isAlive() && b.turn > 0) {
                b.vel = WhipFx.steer(b.pos, b.vel, target.getBoundingBox().getCenter(), b.turn, b.speed, b.age);
            }
            b.prev = b.pos;
            b.pos = b.pos.add(b.vel);
            return false;
        });
    }

    @SubscribeEvent
    public static void draw(SubmitCustomGeometryEvent event) {
        if (BEAMS.isEmpty() && BOLTS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        double now = level.getGameTime() + pt;
        var type = WhipShaders.type(WhipCordFx.ENERGY, WHITE);
        for (Beam b : BEAMS) {
            double t = (now - b.start()) / Math.max(1, b.ticks());
            if (t < 0 || t > 1) {
                continue;
            }
            int alpha = (int) (((b.color() >>> 24) & 0xFF) * (1 - t * t));
            int color = (alpha << 24) | (b.color() & 0xFFFFFF);
            Vec3[] pts = path(b, (long) now);
            event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), type, (at, buffer) -> {
                for (int i = 0; i < pts.length - 1; i++) {
                    strip(at, buffer, pts[i].subtract(cam), pts[i + 1].subtract(cam), b.width() * (float) (1 - 0.5 * t), color);
                }
            });
        }
        for (Bolt b : BOLTS.values()) {
            Vec3 at = b.prev.lerp(b.pos, pt).subtract(cam);
            Vec3 tail = at.subtract(b.vel.scale(1.6));
            int color = b.color;
            float size = b.size;
            event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), type, (pose, buffer) -> {
                strip(pose, buffer, tail, at, size, (color & 0xFFFFFF) | 0x90000000);
                strip(pose, buffer, at.subtract(b.vel.normalize().scale(size)), at.add(b.vel.normalize().scale(size)), size * 2.2F, color);
            });
        }
    }

    /** The beam's points: two for a straight one, a zigzag (new every 2 ticks) for a jagged one. */
    private static Vec3[] path(Beam b, long now) {
        if (!b.jagged()) {
            return new Vec3[] {b.from(), b.to()};
        }
        Vec3 d = b.to().subtract(b.from());
        double len = d.length();
        int n = Math.max(3, (int) (len * 2.5));
        RandomSource r = RandomSource.create(b.seed() + now / 2);
        Vec3 dir = d.normalize();
        Vec3 a = dir.cross(Math.abs(dir.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
        Vec3 c = dir.cross(a).normalize();
        Vec3[] pts = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            double f = (double) i / n;
            double amp = (i == 0 || i == n) ? 0 : 0.35 * Math.sin(f * Math.PI);
            pts[i] = b.from().add(d.scale(f)).add(a.scale((r.nextDouble() - 0.5) * 2 * amp)).add(c.scale((r.nextDouble() - 0.5) * 2 * amp));
        }
        return pts;
    }

    /** A camera-facing strip from a to b (camera-relative), u across. */
    private static void strip(PoseStack.Pose at, VertexConsumer buffer, Vec3 a, Vec3 b, float width, int color) {
        Vec3 along = b.subtract(a);
        Vec3 side = along.cross(a.add(b).scale(-0.5));
        side = side.lengthSqr() < 1.0E-10 ? new Vec3(0, width * 0.5, 0) : side.normalize().scale(width * 0.5);
        vertex(at, buffer, a.add(side), color, 0, 0);
        vertex(at, buffer, a.subtract(side), color, 1, 0);
        vertex(at, buffer, b.subtract(side), color, 1, 1);
        vertex(at, buffer, b.add(side), color, 0, 1);
    }

    private static void vertex(PoseStack.Pose at, VertexConsumer buffer, Vec3 p, int color, float u, float v) {
        buffer.addVertex(at, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(at, 0, 1, 0);
    }

    private WhipFxClient() {}
}
