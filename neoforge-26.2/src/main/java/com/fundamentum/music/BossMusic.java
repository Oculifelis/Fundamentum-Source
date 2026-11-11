package com.fundamentum.music;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * Boss music: the server tells one player which track to play (or to stop), and the client crossfades into it over
 * {@code fade} ticks while vanilla music stays quiet. The boss package sends this; anything else may too.
 */
public final class BossMusic {
    private BossMusic() { }

    public static void init(IEventBus modBus) {
        modBus.addListener(BossMusic::payloads);
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Payload.TYPE, Payload.STREAM_CODEC,
            (payload, context) -> com.fundamentum.music.client.BossMusicPlayer.handle(payload.sound(), payload.fade()));
    }

    /** Starts (or crossfades to) {@code sound} for this player. */
    public static void play(ServerPlayer player, Identifier sound, int fadeTicks) {
        send(player, sound, fadeTicks);
    }

    /** Fades this player's boss music out. */
    public static void stop(ServerPlayer player, int fadeTicks) {
        send(player, null, fadeTicks);
    }

    /** The boss track this player was last told to play, or null (server side). */
    public static @Nullable Identifier current(ServerPlayer player) {
        return CURRENT.get(player.getUUID());
    }

    private static final java.util.Map<java.util.UUID, Identifier> CURRENT = new java.util.concurrent.ConcurrentHashMap<>();

    private static void send(ServerPlayer player, @Nullable Identifier sound, int fade) {
        Identifier old = sound == null ? CURRENT.remove(player.getUUID()) : CURRENT.put(player.getUUID(), sound);
        if (java.util.Objects.equals(old, sound)) return;
        if (player.connection == null || player instanceof net.neoforged.neoforge.common.util.FakePlayer) return;
        PacketDistributor.sendToPlayer(player, new Payload(sound == null ? "" : sound.toString(), fade));
    }

    /** {@code sound} is a sound event id, or "" to stop. */
    public record Payload(String sound, int fade) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Payload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("fundamentum", "boss_music"));
        public static final StreamCodec<FriendlyByteBuf, Payload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.sound);
                buf.writeVarInt(p.fade);
            },
            buf -> new Payload(buf.readUtf(), buf.readVarInt()));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
