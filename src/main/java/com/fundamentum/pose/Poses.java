package com.fundamentum.pose;

import com.fundamentum.net.Net;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.eventbus.api.IEventBus;
import com.fundamentum.net.IPayloadContext;

/**
 * Pose cues from the server: {@link #cue} tells the entity's own client and everyone who can see it that a moment happened
 * ("swing", "cast", "charge", anything a binding names); which clip plays, if any, is the client's
 * ({@link PosePlayer}). Purely visual.
 */
public final class Poses {
    public static void cue(Entity entity, String cue) {
        if (!entity.level().isClientSide()) {
            Net.sendTrackingAndSelf(CHANNEL, entity, new CuePayload(entity.getId(), cue));
        }
    }

    private static final SimpleChannel CHANNEL = Net.channel("pose", "1");

    public static void init(IEventBus modBus) {
        Net.toClient(CHANNEL, 0, CuePayload.class, CuePayload::encode, CuePayload::decode, CuePayload::handle);
    }

    /** Server to client: a pose cue for an entity. */
    public record CuePayload(int entity, String cue) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(this.entity);
            buf.writeUtf(this.cue);
        }

        static CuePayload decode(FriendlyByteBuf buf) {
            return new CuePayload(buf.readVarInt(), buf.readUtf());
        }


        static void handle(CuePayload payload, IPayloadContext context) {
            PosePlayer.cue(payload.entity(), payload.cue());
        }
    }

    private Poses() {}
}
