package com.fundamentum.port;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

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

    private record Entry<T>(FId id, Class<T> type, FStreamCodec<T> codec, Side side, Handler<T> handler,
                            CustomPacketPayload.Type<Packet<T>> payloadType) { }

    /** The payload every registered message travels in. */
    private record Packet<T>(Entry<T> entry, T message) implements CustomPacketPayload {
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return this.entry.payloadType;
        }
    }

    public static <T> void register(FId id, Class<T> type, FStreamCodec<T> codec, Side side, Handler<T> handler) {
        if (frozen) throw new IllegalStateException("FNet message " + id + " registered too late; register while the mod loads");
        if (BY_ID.containsKey(id)) throw new IllegalStateException("FNet message " + id + " registered twice");
        if (BY_CLASS.containsKey(type)) throw new IllegalStateException("FNet class " + type.getName() + " registered twice");
        Entry<T> e = new Entry<>(id, type, codec, side, handler, new CustomPacketPayload.Type<>(id.vanilla()));
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
        PacketDistributor.sendToPlayer(player, wrap(message, Side.TO_CLIENT));
    }

    /** To every player who can see the entity (not the entity itself when it is a player). */
    public static void toTracking(Entity entity, Object message) {
        PacketDistributor.sendToPlayersTrackingEntity(entity, wrap(message, Side.TO_CLIENT));
    }

    /** To every player who can see the entity, and to the entity when it is a player. */
    public static void toTrackingAndSelf(Entity entity, Object message) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, wrap(message, Side.TO_CLIENT));
    }

    public static void toAll(Object message) {
        PacketDistributor.sendToAllPlayers(wrap(message, Side.TO_CLIENT));
    }

    /** From the client to the server. Call only on the client. */
    public static void toServer(Object message) {
        ClientPacketDistributor.sendToServer(wrap(message, Side.TO_SERVER));
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

    @SuppressWarnings("unchecked")
    private static <T> Packet<T> wrap(Object message, Side direction) {
        Entry<T> e = (Entry<T>) entryFor(message);
        if (e.side != Side.BOTH && e.side != direction) throw new IllegalArgumentException("FNet message " + e.id + " is " + e.side + ", not " + direction);
        return new Packet<>(e, (T) message);
    }

    static void register(RegisterPayloadHandlersEvent event) {
        frozen = true;
        PayloadRegistrar registrar = event.registrar("1");
        for (Entry<?> e : BY_ID.values()) registerOne(registrar, e);
    }

    private static <T> void registerOne(PayloadRegistrar registrar, Entry<T> e) {
        StreamCodec<RegistryFriendlyByteBuf, Packet<T>> codec = StreamCodec.of(
            (buf, p) -> e.codec.encode(buf, p.message),
            buf -> new Packet<>(e, e.codec.decode(buf)));
        switch (e.side) {
            case TO_CLIENT -> registrar.playToClient(e.payloadType, codec, (p, ctx) -> handle(e, p, ctx));
            case TO_SERVER -> registrar.playToServer(e.payloadType, codec, (p, ctx) -> handle(e, p, ctx));
            case BOTH -> registrar.playBidirectional(e.payloadType, codec, (p, ctx) -> handle(e, p, ctx), (p, ctx) -> handle(e, p, ctx));
        }
    }

    private static <T> void handle(Entry<T> e, Packet<T> packet, IPayloadContext ctx) {
        // payload handlers already run on the main thread unless registered otherwise
        boolean server = ctx.player() instanceof ServerPlayer;
        Player player = ctx.player();
        e.handler.handle(packet.message, new Context() {
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
