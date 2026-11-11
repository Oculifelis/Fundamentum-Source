package com.fundamentum.port;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Packets with one set of calls on every version: NeoForge payloads on 26.x and 1.21.1, one Forge SimpleChannel on
 * 1.20.1. Register each message class while the mod is being constructed, with an id, an {@link FStreamCodec}, which
 * way it travels and a handler; handlers always run on the main thread. Then send plain objects with
 * {@link #toPlayer}, {@link #toTracking}, {@link #toAll} or {@link #toServer}.
 */
public final class FNet {
    private FNet() { }

    public enum Side { TO_CLIENT, TO_SERVER, BOTH }

    @FunctionalInterface
    public interface Handler<T> {
        void handle(T message, Context context);
    }

    /** Where a message came from. On the client, {@link #player()} is the local player. */
    public interface Context {
        Player player();

        /** True when this handler runs on the server (the message came from a client). */
        boolean isServer();
    }

    private static final Map<Class<?>, Entry<?>> BY_CLASS = new LinkedHashMap<>();
    private static final Map<FId, Entry<?>> BY_ID = new LinkedHashMap<>();
    private static boolean frozen;
    private static SimpleChannel channel;

    private record Entry<T>(FId id, Class<T> type, FStreamCodec<T> codec, Side side, Handler<T> handler) { }

    public static <T> void register(FId id, Class<T> type, FStreamCodec<T> codec, Side side, Handler<T> handler) {
        if (frozen) throw new IllegalStateException("FNet message " + id + " registered too late; register while the mod loads");
        if (BY_ID.containsKey(id)) throw new IllegalStateException("FNet message " + id + " registered twice");
        if (BY_CLASS.containsKey(type)) throw new IllegalStateException("FNet class " + type.getName() + " registered twice");
        Entry<T> e = new Entry<>(id, type, codec, side, handler);
        BY_ID.put(id, e);
        BY_CLASS.put(type, e);
    }

    public static boolean registered(FId id) {
        return BY_ID.containsKey(id);
    }

    /** True once the loader has taken the registrations (no more may be added). */
    public static boolean frozen() {
        return frozen;
    }

    public static int count() {
        return BY_ID.size();
    }

    public static void toPlayer(ServerPlayer player, Object message) {
        channel().send(PacketDistributor.PLAYER.with(() -> player), wrap(message, Side.TO_CLIENT));
    }

    /** To every player who can see the entity (not the entity itself when it is a player). */
    public static void toTracking(Entity entity, Object message) {
        channel().send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), wrap(message, Side.TO_CLIENT));
    }

    /** To every player who can see the entity, and to the entity when it is a player. */
    public static void toTrackingAndSelf(Entity entity, Object message) {
        channel().send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity), wrap(message, Side.TO_CLIENT));
    }

    public static void toAll(Object message) {
        channel().send(PacketDistributor.ALL.noArg(), wrap(message, Side.TO_CLIENT));
    }

    /** From the client to the server. Call only on the client. */
    public static void toServer(Object message) {
        channel().sendToServer(wrap(message, Side.TO_SERVER));
    }

    /** Encodes and decodes the message with its registered codec, as the network would. */
    @SuppressWarnings("unchecked")
    public static <T> T roundTrip(T message, net.minecraft.core.RegistryAccess access) {
        Entry<T> e = (Entry<T>) entryFor(message);
        return e.codec.roundTrip(message, access);
    }

    private static Entry<?> entryFor(Object message) {
        Entry<?> e = BY_CLASS.get(message.getClass());
        if (e == null) throw new IllegalArgumentException("Not a registered FNet message: " + message.getClass().getName());
        return e;
    }

    private static Object wrap(Object message, Side direction) {
        Entry<?> e = entryFor(message);
        if (e.side != Side.BOTH && e.side != direction) throw new IllegalArgumentException("FNet message " + e.id + " is " + e.side + ", not " + direction);
        return message;
    }

    private static SimpleChannel channel() {
        if (channel == null || !frozen) throw new IllegalStateException("FNet is not set up yet");
        return channel;
    }

    /** Makes the channel; called while the mod is constructed (channels must exist before the network starts). */
    static void createChannel() {
        if (channel == null) {
            channel = NetworkRegistry.newSimpleChannel(new ResourceLocation("fundamentum", "port"), () -> "1", "1"::equals, "1"::equals);
        }
    }

    /** Registers every message on the channel, in registration order (both sides must register the same ones). */
    static void register() {
        if (frozen) return;
        frozen = true;
        int index = 0;
        for (Entry<?> e : BY_ID.values()) registerOne(e, index++);
    }

    private static <T> void registerOne(Entry<T> e, int index) {
        SimpleChannel.MessageBuilder<T> b = switch (e.side) {
            case TO_CLIENT -> channel.messageBuilder(e.type, index, NetworkDirection.PLAY_TO_CLIENT);
            case TO_SERVER -> channel.messageBuilder(e.type, index, NetworkDirection.PLAY_TO_SERVER);
            case BOTH -> channel.messageBuilder(e.type, index);
        };
        b.encoder((msg, buf) -> e.codec.encode(buf, msg)).decoder(e.codec::decode)
            .consumerMainThread((msg, ctx) -> handle(e, msg, ctx)).add();
    }

    private static <T> void handle(Entry<T> e, T message, Supplier<NetworkEvent.Context> ctx) {
        boolean server = ctx.get().getDirection().getReceptionSide().isServer();
        Player player = server ? ctx.get().getSender() : com.fundamentum.net.ClientNet.player();
        e.handler.handle(message, new Context() {
            @Override
            public Player player() {
                return player;
            }

            @Override
            public boolean isServer() {
                return server;
            }
        });
    }
}
