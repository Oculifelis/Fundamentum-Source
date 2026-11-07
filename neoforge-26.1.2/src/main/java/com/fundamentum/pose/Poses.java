package com.fundamentum.pose;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Pose cues from the server: {@link #cue} tells the entity's own client and everyone who can see it that a moment happened
 * ("swing", "cast", "charge", anything a binding names); which clip plays, if any, is the client's
 * ({@link PosePlayer}). Purely visual.
 */
public final class Poses {
    public static void cue(Entity entity, String cue) {
        if (!entity.level().isClientSide()) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, new CuePayload(entity.getId(), cue));
        }
    }

    public static void init(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent event) ->
            event.registrar("1").playToClient(CuePayload.TYPE, CuePayload.STREAM_CODEC, CuePayload::handle));
    }

    /** Server to client: a pose cue for an entity. */
    public record CuePayload(int entity, String cue) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<CuePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("fundamentum", "pose_cue"));
        public static final StreamCodec<ByteBuf, CuePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CuePayload::entity, ByteBufCodecs.STRING_UTF8, CuePayload::cue, CuePayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(CuePayload payload, IPayloadContext context) {
            PosePlayer.cue(payload.entity(), payload.cue());
        }
    }

    private Poses() {}
}
