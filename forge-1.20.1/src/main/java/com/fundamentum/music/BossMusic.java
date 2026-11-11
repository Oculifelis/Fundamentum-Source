package com.fundamentum.music;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.IEventBus;
import com.fundamentum.net.Net;
import net.minecraftforge.network.simple.SimpleChannel;
import org.jetbrains.annotations.Nullable;

/**
 * Boss music: the server tells one player which track to play (or to stop), and the client crossfades into it over
 * {@code fade} ticks while vanilla music stays quiet. The boss package sends this; anything else may too.
 */
public final class BossMusic {
    private BossMusic() { }

    static final SimpleChannel CHANNEL = Net.optionalChannel("boss_music", "1");

    public static void init(IEventBus modBus) {
        Net.toClient(CHANNEL, 0, Payload.class, Payload::encode, Payload::decode,
            (payload, context) -> com.fundamentum.music.client.BossMusicPlayer.handle(payload.sound(), payload.fade()));
    }

    /** Starts (or crossfades to) {@code sound} for this player. */
    public static void play(ServerPlayer player, ResourceLocation sound, int fadeTicks) {
        send(player, sound, fadeTicks);
    }

    /** Fades this player's boss music out. */
    public static void stop(ServerPlayer player, int fadeTicks) {
        send(player, null, fadeTicks);
    }

    /** The boss track this player was last told to play, or null (server side). */
    public static @Nullable ResourceLocation current(ServerPlayer player) {
        return CURRENT.get(player.getUUID());
    }

    private static final java.util.Map<java.util.UUID, ResourceLocation> CURRENT = new java.util.concurrent.ConcurrentHashMap<>();

    private static void send(ServerPlayer player, @Nullable ResourceLocation sound, int fade) {
        ResourceLocation old = sound == null ? CURRENT.remove(player.getUUID()) : CURRENT.put(player.getUUID(), sound);
        if (java.util.Objects.equals(old, sound)) return;
        if (player.connection == null || player instanceof net.minecraftforge.common.util.FakePlayer) return;
        Net.sendTo(CHANNEL, player, new Payload(sound == null ? "" : sound.toString(), fade));
    }

    /** {@code sound} is a sound event id, or "" to stop. */
    public record Payload(String sound, int fade) {
        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(this.sound);
            buf.writeVarInt(this.fade);
        }

        static Payload decode(FriendlyByteBuf buf) {
            return new Payload(buf.readUtf(), buf.readVarInt());
        }
    }
}
