package com.fundamentum.wings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Moves the local player on wings (see {@link Wings}). Movement is the client's, as in vanilla. */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class WingsClient {
    private static int flightLeft;
    private static boolean airborne;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Wings.Stats stats = Wings.stats(player);
        boolean grounded = player.onGround() || player.isInWater() || player.isInLava() || player.onClimbable() || player.isPassenger();
        if (stats == null || grounded || player.getAbilities().flying || player.isFallFlying() || player.isSpectator()) {
            if (stats != null && grounded) {
                flightLeft = Wings.flightTicks(player, stats);
            }
            setAirborne(false);
            return;
        }
        if (!Minecraft.getInstance().options.keyJump.isDown()) {
            setAirborne(false);
            return;
        }
        Vec3 v = player.getDeltaMovement();
        double vy;
        if (flightLeft > 0) {
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
        setAirborne(true);
    }

    private static void setAirborne(boolean now) {
        if (now != airborne) {
            airborne = now;
            PacketDistributor.sendToServer(new Wings.StatePayload(now));
        }
    }

    private WingsClient() {}
}
