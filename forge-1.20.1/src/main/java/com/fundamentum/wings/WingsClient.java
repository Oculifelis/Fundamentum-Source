package com.fundamentum.wings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Moves the local player on wings (see {@link Wings}). Movement is the client's, as in vanilla. */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WingsClient {
    private static int flightLeft;
    private static byte phase;

    /** The local player's flight phase ({@link Wings#FOLDED}, {@link Wings#RISING} or {@link Wings#GLIDING}). */
    public static byte phase() {
        return phase;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (event.phase != TickEvent.Phase.END || player == null) {
            return;
        }
        Wings.Stats stats = Wings.stats(player);
        boolean grounded = player.onGround() || player.isInWater() || player.isInLava() || player.onClimbable() || player.isPassenger();
        if (stats == null || grounded || player.getAbilities().flying || player.isFallFlying() || player.isSpectator()) {
            if (stats != null && grounded) {
                flightLeft = Wings.flightTicks(player, stats);
            }
            setPhase(Wings.FOLDED);
            return;
        }
        if (!Minecraft.getInstance().options.keyJump.isDown()) {
            setPhase(Wings.FOLDED);
            return;
        }
        Vec3 v = player.getDeltaMovement();
        double vy;
        boolean rising = flightLeft > 0;
        if (rising) {
            vy = Math.min(v.y + stats.riseAcceleration(), stats.riseSpeed());
            flightLeft--;
        } else {
            vy = Math.max(v.y, -stats.glideFallSpeed());
        }
        double vx = v.x;
        double vz = v.z;
        double boost = 0.02 * (stats.horizontalSpeed() - 1);
        if (boost != 0 && (player.xxa != 0 || player.zza != 0)) {
            float yaw = player.getYRot() * Mth.DEG_TO_RAD;
            double len = Math.max(1, Math.sqrt(player.xxa * player.xxa + player.zza * player.zza));
            vx += (player.xxa * Mth.cos(yaw) - player.zza * Mth.sin(yaw)) / len * boost;
            vz += (player.zza * Mth.cos(yaw) + player.xxa * Mth.sin(yaw)) / len * boost;
        }
        player.setDeltaMovement(vx, vy, vz);
        player.resetFallDistance();
        setPhase(rising ? Wings.RISING : Wings.GLIDING);
    }

    private static void setPhase(byte now) {
        if (now != phase) {
            phase = now;
            Wings.CHANNEL.sendToServer(new Wings.StatePayload(now));
        }
    }

    private WingsClient() {}
}
