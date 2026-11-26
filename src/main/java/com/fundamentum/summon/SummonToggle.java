package com.fundamentum.summon;

import com.fundamentum.port.FAttach;
import com.fundamentum.port.FId;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStreamCodec;
import com.mojang.serialization.Codec;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Each player's on/off switch for summons: their minions from the summon slot and their placed sentries. Off sends
 * every minion and sentry away, keeps minions from being called while the summon items stay worn, and refuses new
 * sentry placements. On calls the minions back on the next check. The Toggle Summons key (K by default) flips it
 * through {@link Request}; the choice is saved with the player and kept through death. Other mods with their own
 * companions can read {@link #enabled} to follow the same switch.
 */
public final class SummonToggle {
    private SummonToggle() { }

    public static final FId REQUEST = FId.of("fundamentum", "summon_toggle");

    /** Whether the player's summons are on. Saved, kept through death; on by default. */
    public static final FAttach<Boolean> ENABLED = FAttach.builder(FId.of("fundamentum", "summons_enabled"), () -> true)
        .codec(Codec.BOOL).copyOnDeath().build();

    /** Client to server: flip the sender's switch. */
    public record Request() {
        public static final Request INSTANCE = new Request();
        public static final FStreamCodec<Request> CODEC = FStreamCodec.unit(INSTANCE);
    }

    private static boolean initialised;

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        FNet.register(REQUEST, Request.class, Request.CODEC, FNet.Side.TO_SERVER, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer p) toggle(p);
        });
    }

    /** True when the player's summons are on. */
    public static boolean enabled(Player player) {
        Boolean on = ENABLED.getOrNull(player);
        return on == null || on;
    }

    /** Flips the switch and shows the new state on the action bar. Returns the new state. */
    public static boolean toggle(ServerPlayer player) {
        boolean on = !enabled(player);
        set(player, on);
        player.sendOverlayMessage(Component.translatable(on ? "message.fundamentum.summons.on" : "message.fundamentum.summons.off")
            .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        return on;
    }

    /** Sets the switch. Turning it off sends the player's minions and sentries away at once. */
    public static void set(ServerPlayer player, boolean on) {
        ENABLED.set(player, on);
        if (!on) {
            Summons.dismiss(player);
            Sentries.dismiss(player);
        }
    }
}
