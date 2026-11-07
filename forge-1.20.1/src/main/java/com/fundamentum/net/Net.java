package com.fundamentum.net;

import java.util.function.BiConsumer;
import net.minecraft.world.phys.Vec3;
import java.util.function.Function;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * The 1.20.1 stand-in for NeoForge's payload registrar: each Fundamentum piece keeps its own {@link SimpleChannel}
 * ({@code fundamentum:<name>}), and its messages are records with an encoder and a decoder. Handlers run on the main
 * thread. A client handler's lambda may call client-only classes: it is only run on a client.
 */
public final class Net {
    /** A channel both sides must have in the same version. */
    public static SimpleChannel channel(String name, String version) {
        return NetworkRegistry.newSimpleChannel(new ResourceLocation("fundamentum", name), () -> version, version::equals, version::equals);
    }

    /** A channel the other side may lack (a server without Fundamentum's sky, a client without it): it is then not used. */
    public static SimpleChannel optionalChannel(String name, String version) {
        return NetworkRegistry.newSimpleChannel(new ResourceLocation("fundamentum", name), () -> version,
            NetworkRegistry.acceptMissingOr(version), NetworkRegistry.acceptMissingOr(version));
    }

    public static <T> void toClient(SimpleChannel channel, int id, Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder,
                                    Function<FriendlyByteBuf, T> decoder, BiConsumer<T, IPayloadContext> handler) {
        channel.messageBuilder(type, id, NetworkDirection.PLAY_TO_CLIENT).encoder(encoder).decoder(decoder)
            .consumerMainThread((msg, context) -> handler.accept(msg, ClientNet::player)).add();
    }

    /** A message with no content to clients. */
    public static <T> void toClientUnit(SimpleChannel channel, int id, Class<T> type, T instance, BiConsumer<T, IPayloadContext> handler) {
        toClient(channel, id, type, (m, buf) -> {}, buf -> instance, handler);
    }

    public static <T> void toServer(SimpleChannel channel, int id, Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder,
                                    Function<FriendlyByteBuf, T> decoder, BiConsumer<T, IPayloadContext> handler) {
        channel.messageBuilder(type, id, NetworkDirection.PLAY_TO_SERVER).encoder(encoder).decoder(decoder)
            .consumerMainThread((msg, context) -> {
                ServerPlayer sender = context.get().getSender();
                if (sender != null) {
                    handler.accept(msg, () -> sender);
                }
            }).add();
    }

    /** A message with no content: encodes nothing, decodes to {@code instance}. */
    public static <T> void toServerUnit(SimpleChannel channel, int id, Class<T> type, T instance, BiConsumer<T, IPayloadContext> handler) {
        toServer(channel, id, type, (m, buf) -> {}, buf -> instance, handler);
    }

    public static void sendTo(SimpleChannel channel, ServerPlayer player, Object msg) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    /** To every player tracking {@code entity} (not the entity itself). */
    public static void sendTracking(SimpleChannel channel, Entity entity, Object msg) {
        channel.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), msg);
    }

    /** To every player tracking {@code entity}, and to it when it is a player. */
    public static void sendTrackingAndSelf(SimpleChannel channel, Entity entity, Object msg) {
        channel.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity), msg);
    }

    /** To every player in the level of {@code entity}'s dimension. */
    public static void sendDimension(SimpleChannel channel, net.minecraft.server.level.ServerLevel level, Object msg) {
        channel.send(PacketDistributor.DIMENSION.with(level::dimension), msg);
    }

    /** To every player within {@code radius} of a point in {@code level}. */
    public static void sendNear(SimpleChannel channel, net.minecraft.server.level.ServerLevel level, double x, double y, double z, double radius, Object msg) {
        channel.send(PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(x, y, z, radius, level.dimension())), msg);
    }

    public static void sendAll(SimpleChannel channel, Object msg) {
        channel.send(PacketDistributor.ALL.noArg(), msg);
    }

    public static void writeVec3(FriendlyByteBuf buf, Vec3 v) {
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    public static Vec3 readVec3(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    private Net() {}
}
