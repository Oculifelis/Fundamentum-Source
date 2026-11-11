package com.fundamentum.wings;

import com.fundamentum.flail.Flails;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

/**
 * How worn wings move, for every player this client can see (1.28.0). Each wearer's flight phase comes from
 * {@link WingsClient} for the local player and from {@link Wings.PhasePayload} for the others. Folded, the wings hang
 * like a resting elytra and give a small twitch now and then; rising, they spread and beat, faster the faster the
 * wearer climbs; gliding, they hold wide and level with a slow ripple. The pose is the elytra model's wing rotation
 * (left wing; the model mirrors the right), so any elytra-shaped wings follow it. The optional {@link Wings.Fx} of the
 * worn wings adds a burst on take-off, particles and a sound on every downstroke, and a trail while gliding.
 */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WingsAnim {
    /** Vanilla's resting and fully spread elytra angles, and its crouching ones. */
    static final float REST_X = Mth.PI / 12, REST_Z = -Mth.PI / 12, SPREAD_X = Mth.PI / 9, SPREAD_Z = -Mth.PI / 2;
    static final float CROUCH_X = Mth.PI * 2 / 9, CROUCH_Y = 0.08726646F, CROUCH_Z = -Mth.PI / 4;
    /** How far a beat swings the wings (radians of zRot) and how much higher than flat they sit while beating. */
    static final float BEAT = 0.55F, BEAT_LIFT = 0.15F;
    /** Rising beat speed (radians per tick) and how much faster it gets per block/tick of climb (capped at 0.5); about
     * half a second a flap. The first beats after take-off run slower while the beat strength ramps up. */
    static final float RISE_FLAP = 0.42F, RISE_FLAP_CLIMB = 0.6F;
    /** No animation or effects for players further than this. */
    private static final double RANGE = 64;

    /** The left wing's rotation: the elytra model's {@code elytraRotX/Y/Z}. */
    public record Pose(float xRot, float yRot, float zRot) {}

    /** Phases the server announced for other players, by entity id. */
    private static final Map<Integer, Byte> REMOTE = new HashMap<>();
    private static final Map<Integer, State> STATES = new HashMap<>();

    static void remotePhase(int entity, byte phase) {
        if (phase == Wings.FOLDED) {
            REMOTE.remove(entity);
        } else {
            REMOTE.put(entity, phase);
        }
    }

    /** This frame's wing pose for a player, or null when Fundamentum is not animating it. */
    public static @Nullable Pose pose(Player player, float partialTick) {
        State s = STATES.get(player.getId());
        return s == null ? null : s.pose(partialTick);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer local = mc.player;
        if (level == null || local == null) {
            STATES.clear();
            REMOTE.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        Map<Integer, State> seen = new HashMap<>();
        for (Player player : level.players()) {
            Wings.Stats stats = player.distanceToSqr(local) > RANGE * RANGE ? null : Wings.stats(player);
            if (stats == null) {
                continue;
            }
            byte phase = player == local ? WingsClient.phase() : REMOTE.getOrDefault(player.getId(), Wings.FOLDED);
            State s = STATES.computeIfAbsent(player.getId(), id -> new State());
            s.tick(player, phase, stats.fx(), level);
            seen.put(player.getId(), s);
        }
        STATES.keySet().retainAll(seen.keySet());
        REMOTE.keySet().removeIf(id -> level.getEntity(id) == null);
    }

    /** One wearer's wing motion. */
    static final class State {
        float x = REST_X, y, z = REST_Z, xO = REST_X, yO, zO = REST_Z;
        /** Beat strength (0 none, 1 full) and the beat's phase angle. */
        float amp, ampO, beat, beatO;
        /** Folded twitch: ticks until the next one, and how far into one we are (0 when none). */
        int twitchIn = 60, twitch;
        float twitchZ, twitchZO;
        byte phase = Wings.FOLDED;
        double trail;

        void tick(Player player, byte now, @Nullable Wings.Fx fx, ClientLevel level) {
            RandomSource random = player.getRandom();
            xO = x;
            yO = y;
            zO = z;
            ampO = amp;
            beatO = beat;
            twitchZO = twitchZ;

            boolean airborne = now != Wings.FOLDED;
            boolean crouch = !airborne && player.isCrouching();
            float tx = airborne ? SPREAD_X : crouch ? CROUCH_X : REST_X;
            float ty = crouch ? CROUCH_Y : 0;
            float tz = airborne ? SPREAD_Z + (now == Wings.RISING ? BEAT_LIFT : 0) : crouch ? CROUCH_Z : REST_Z;
            x += (tx - x) * 0.3F;
            y += (ty - y) * 0.3F;
            z += (tz - z) * 0.3F;

            float targetAmp = now == Wings.RISING ? 1 : now == Wings.GLIDING ? 0.08F : 0;
            amp += (targetAmp - amp) * 0.35F;
            float speed = fx == null ? 1 : fx.flapSpeed();
            double climb = player.getY() - player.yo;
            float step = now == Wings.RISING ? (RISE_FLAP + (float) Mth.clamp(climb, 0, 0.5) * RISE_FLAP_CLIMB) * speed * (0.5F + 0.5F * amp) : now == Wings.GLIDING ? 0.35F : amp > 0.01F ? 0.6F : 0;
            beat += step;
            // A downstroke passes its middle (beat = PI mod 2 PI) during this tick.
            boolean downstroke = now == Wings.RISING && Math.floor((beat - Mth.PI) / Mth.TWO_PI) != Math.floor((beatO - Mth.PI) / Mth.TWO_PI);
            if (beat > 1000) {
                beat -= 100 * Mth.TWO_PI;
                beatO -= 100 * Mth.TWO_PI;
            }

            // Folded on the ground: a short flick of the wings every four to eight seconds.
            if (!airborne && player.onGround()) {
                if (twitch > 0) {
                    twitch++;
                    twitchZ = -0.22F * Mth.sin(twitch / 8F * Mth.PI);
                    if (twitch >= 8) {
                        twitch = 0;
                        twitchZ = 0;
                    }
                } else if (--twitchIn <= 0) {
                    twitch = 1;
                    twitchIn = 80 + random.nextInt(81);
                }
            } else {
                twitch = 0;
                twitchZ = 0;
            }

            if (fx != null && !player.isInvisible()) {
                effects(player, now, fx, level, random, downstroke);
            }
            phase = now;
        }

        private void effects(Player player, byte now, Wings.Fx fx, ClientLevel level, RandomSource random, boolean downstroke) {
            if (phase == Wings.FOLDED && now != Wings.FOLDED) {
                spawn(player, fx.burst(), fx.burstCount(), level, random, 0.12, -0.04);
                sound(player, fx.takeoffSound(), fx, level, random);
            }
            if (downstroke) {
                spawn(player, fx.flap(), fx.flapCount(), level, random, 0.05, -0.08);
                sound(player, fx.flapSound(), fx, level, random);
            }
            if (now != Wings.FOLDED) {
                trail += now == Wings.GLIDING ? fx.trailRate() : fx.trailRate() / 2;
                int n = (int) trail;
                trail -= n;
                spawn(player, fx.trail(), n, level, random, 0.015, -0.01);
            } else {
                trail = 0;
            }
        }

        /** {@code count} of each listed particle along the wings' trailing edges, pushed out by {@code spread} and down by {@code drop}. */
        private void spawn(Player player, List<String> ids, int count, ClientLevel level, RandomSource random, double spread, double drop) {
            if (ids.isEmpty() || count <= 0) {
                return;
            }
            float yaw = player.yBodyRot * Mth.DEG_TO_RAD;
            double fx = -Mth.sin(yaw), fz = Mth.cos(yaw);   // forward
            double sx = Mth.cos(yaw), sz = Mth.sin(yaw);    // to one side
            float open = Mth.clamp((REST_Z - z) / (REST_Z - SPREAD_Z), 0, 1);
            for (String id : ids) {
                ParticleOptions particle = Flails.particle(id);
                if (particle == null) {
                    continue;
                }
                for (int i = 0; i < count; i++) {
                    double side = random.nextBoolean() ? 1 : -1;
                    double along = 0.2 + random.nextDouble() * (0.25 + 0.6 * open);
                    double back = 0.3 + random.nextDouble() * 0.15;
                    double px = player.getX() - fx * back + sx * side * along;
                    double py = player.getY() + (player.isCrouching() ? 0.9 : 1.2) - (1 - open) * along * 0.9 - random.nextDouble() * 0.25;
                    double pz = player.getZ() - fz * back + sz * side * along;
                    double vx = sx * side * spread * random.nextDouble() - fx * spread * 0.5 + random.nextGaussian() * 0.01;
                    double vz = sz * side * spread * random.nextDouble() - fz * spread * 0.5 + random.nextGaussian() * 0.01;
                    level.addParticle(particle, px, py, pz, vx, drop * (0.5 + random.nextDouble()), vz);
                }
            }
        }

        private static void sound(Player player, @Nullable String id, Wings.Fx fx, ClientLevel level, RandomSource random) {
            if (id == null) {
                return;
            }
            SoundEvent sound = Flails.sound(id);
            level.playLocalSound(player.getX(), player.getY() + 1, player.getZ(), sound, SoundSource.PLAYERS, fx.volume(),
                fx.pitch() * (0.92F + random.nextFloat() * 0.16F), false);
        }

        Pose pose(float pt) {
            float a = Mth.lerp(pt, ampO, amp);
            float b = Mth.lerp(pt, beatO, beat);
            float sin = Mth.sin(b), cos = Mth.cos(b);
            return new Pose(
                Mth.lerp(pt, xO, x) + a * 0.12F * cos,
                Mth.lerp(pt, yO, y) + a * 0.12F * cos,
                Mth.lerp(pt, zO, z) - a * BEAT * sin + Mth.lerp(pt, twitchZO, twitchZ));
        }
    }

    private WingsAnim() {}
}
