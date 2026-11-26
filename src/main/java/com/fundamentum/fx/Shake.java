package com.fundamentum.fx;

import com.fundamentum.config.ConfigValue;
import com.fundamentum.config.FConfig;
import com.fundamentum.port.FId;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Screen shake for any mod: one small packet, and the client plays a fading curve on the camera. Shakes add up to a cap
 * of 3, so overlapping hits feel heavier without going wild. Each player scales or turns it off with the client config
 * {@code fundamentum:fx} {@code screen_shake} (0 to 2).
 */
public final class Shake {
    public static final FId ID = FId.of("fundamentum", "shake");

    /** Strength (1 is a firm hit) and how many ticks it takes to fade. */
    public record Msg(float strength, int ticks) {
        public static final FStreamCodec<Msg> CODEC = FStreamCodec.composite(FStreamCodec.FLOAT, Msg::strength,
            FStreamCodec.INT, Msg::ticks, Msg::new);
    }

    private static ConfigValue<Double> scale;
    private static boolean initialised;

    /** Client state, read by the camera hook. */
    static float strength;
    static float decay = 0.85F;

    private Shake() { }

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        FConfig c = FConfig.create(FId.of("fundamentum", "fx"));
        scale = c.decimal("screen_shake", 1.0, 0.0, 2.0).side(FConfig.Side.CLIENT);
        FNet.register(ID, Msg.class, Msg.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) -> local(msg.strength(), msg.ticks()));
    }

    /** Shakes one player's view. */
    public static void send(ServerPlayer player, float strength, int ticks) {
        if (strength <= 0 || ticks <= 0) return;
        FNet.toPlayer(player, new Msg(strength, ticks));
    }

    /** Shakes every player within {@code radius} of {@code at}, weaker with distance. */
    public static void around(ServerLevel level, Vec3 at, double radius, float strength, int ticks) {
        for (ServerPlayer p : level.players()) {
            double d = p.position().distanceTo(at);
            if (d <= radius) send(p, (float) (strength * (1 - d / radius)), ticks);
        }
    }

    /** Client side: starts a shake on the local view (for client-only effects). */
    public static void local(float s, int ticks) {
        double k = scale == null ? 1.0 : scale.get();
        if (k <= 0) return;
        strength = Math.min(3F, strength + (float) (s * k));
        decay = (float) Math.pow(0.05, 1.0 / Math.max(1, ticks)); // down to 5% after the given ticks
    }

    /** The current strength (client side), for mods that draw their own effect with it. */
    public static float strength() {
        return strength;
    }
}
