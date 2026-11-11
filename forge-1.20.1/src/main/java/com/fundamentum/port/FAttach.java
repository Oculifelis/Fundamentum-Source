package com.fundamentum.port;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Data kept on an entity, chunk, level or block entity, the same calls on every version. NeoForge data attachments
 * underneath on 26.x and 1.21.1, a Forge capability on 1.20.1. Make one with {@link #builder} while the mod is being
 * constructed (a static field), then {@link #get}/{@link #set} it anywhere on the server.
 *
 * <ul>
 * <li>{@code codec(...)}: saved with the holder; without a codec the value lives only in memory.</li>
 * <li>{@code copyOnDeath()}: a player's value survives death (needs a codec). Returning from the End keeps every
 * saved value either way.</li>
 * <li>{@code sync(Sync, FStreamCodec)}: sent to the owning player only or to everyone who can see the holder, on
 * every {@link #set}/{@link #remove} and when a player starts seeing it. After changing a mutable value in place, call
 * {@link #set} again (or {@link #resync}). On 1.20.1 only entity values are sent.</li>
 * </ul>
 */
public final class FAttach<T> {
    public enum Sync { NONE, OWNER, TRACKING }

    private static final List<FAttach<?>> ALL = new ArrayList<>();
    private static final Map<String, FAttach<?>> BY_ID = new HashMap<>();
    private static boolean frozen;

    private final FId id;
    private final Supplier<T> defaultValue;
    private final Codec<T> codec;
    private final boolean copyOnDeath;
    private final Sync sync;
    private final FStreamCodec<T> streamCodec;

    private FAttach(Builder<T> b) {
        this.id = b.id;
        this.defaultValue = b.defaultValue;
        this.codec = b.codec;
        this.copyOnDeath = b.copyOnDeath;
        this.sync = b.sync;
        this.streamCodec = b.streamCodec;
    }

    public static <T> Builder<T> builder(FId id, Supplier<T> defaultValue) {
        return new Builder<>(id, defaultValue);
    }

    public FId id() {
        return this.id;
    }

    public boolean saved() {
        return this.codec != null;
    }

    public boolean copiesOnDeath() {
        return this.copyOnDeath;
    }

    public Sync sync() {
        return this.sync;
    }

    // ---- entity

    /** The value, creating and storing the default when there is none. */
    public T get(Entity holder) {
        return getIn(holder);
    }

    /** The value, or null when none was ever stored. */
    public T getOrNull(Entity holder) {
        return store(holder).get(this);
    }

    public boolean has(Entity holder) {
        return store(holder).get(this) != null;
    }

    public void set(Entity holder, T value) {
        store(holder).put(this, value);
        send(holder, value);
    }

    public void remove(Entity holder) {
        store(holder).remove(this);
        send(holder, null);
    }

    public T update(Entity holder, UnaryOperator<T> change) {
        T v = change.apply(get(holder));
        set(holder, v);
        return v;
    }

    public void resync(Entity holder) {
        send(holder, getOrNull(holder));
    }

    // ---- chunk

    public T get(LevelChunk holder) {
        return getIn(holder);
    }

    public T getOrNull(LevelChunk holder) {
        return store(holder).get(this);
    }

    public boolean has(LevelChunk holder) {
        return store(holder).get(this) != null;
    }

    public void set(LevelChunk holder, T value) {
        store(holder).put(this, value);
        holder.setUnsaved(true);
    }

    public void remove(LevelChunk holder) {
        store(holder).remove(this);
        holder.setUnsaved(true);
    }

    public T update(LevelChunk holder, UnaryOperator<T> change) {
        T v = change.apply(get(holder));
        set(holder, v);
        return v;
    }

    /** Chunk values are not sent to clients on 1.20.1; this does nothing there. */
    public void resync(LevelChunk holder) {
    }

    // ---- level

    public T get(Level holder) {
        return getIn(holder);
    }

    public T getOrNull(Level holder) {
        return store(holder).get(this);
    }

    public boolean has(Level holder) {
        return store(holder).get(this) != null;
    }

    public void set(Level holder, T value) {
        store(holder).put(this, value);
    }

    public void remove(Level holder) {
        store(holder).remove(this);
    }

    public T update(Level holder, UnaryOperator<T> change) {
        T v = change.apply(get(holder));
        set(holder, v);
        return v;
    }

    /** Level values are not sent to clients on 1.20.1; this does nothing there. */
    public void resync(Level holder) {
    }

    // ---- block entity

    public T get(BlockEntity holder) {
        return getIn(holder);
    }

    public T getOrNull(BlockEntity holder) {
        return store(holder).get(this);
    }

    public boolean has(BlockEntity holder) {
        return store(holder).get(this) != null;
    }

    public void set(BlockEntity holder, T value) {
        store(holder).put(this, value);
        holder.setChanged();
    }

    public void remove(BlockEntity holder) {
        store(holder).remove(this);
        holder.setChanged();
    }

    public T update(BlockEntity holder, UnaryOperator<T> change) {
        T v = change.apply(get(holder));
        set(holder, v);
        return v;
    }

    /** Block entity values are not sent to clients on 1.20.1; this does nothing there. */
    public void resync(BlockEntity holder) {
    }

    // ----

    /**
     * Copies attached data from one entity to another, as a respawn or an End return does: with {@code death} only
     * the copy-on-death values, otherwise every saved value. The loader already does this for players.
     */
    public static void copy(Entity from, Entity to, boolean death) {
        Store a = store(from);
        Store b = store(to);
        for (FAttach<?> f : ALL) copyOne(f, a, b, death);
    }

    private static <T> void copyOne(FAttach<T> f, Store from, Store to, boolean death) {
        if (f.codec == null || (death && !f.copyOnDeath)) return;
        T v = from.get(f);
        if (v != null) to.put(f, f.copyValue(v));
    }

    /** Writes the value with its codec and reads it back, as a save and load would. */
    public T roundTrip(T value, RegistryAccess access) {
        if (this.codec == null) throw new IllegalStateException(this.id + " has no codec");
        DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, access);
        Tag tag = this.codec.encodeStart(ops, value).getOrThrow(false, msg -> { });
        return this.codec.parse(ops, tag).getOrThrow(false, msg -> { });
    }

    private T copyValue(T value) {
        DynamicOps<Tag> ops = ops();
        return this.codec.parse(ops, this.codec.encodeStart(ops, value).getOrThrow(false, msg -> { })).getOrThrow(false, msg -> { });
    }

    private T getIn(ICapabilityProvider holder) {
        Store s = store(holder);
        T v = s.get(this);
        if (v == null) {
            v = this.defaultValue.get();
            s.put(this, v);
        }
        return v;
    }

    // ---- the capability underneath

    private static final Capability<Store> CAP = CapabilityManager.get(new CapabilityToken<>() { });
    private static final ResourceLocation KEY = new ResourceLocation("fundamentum", "port_attach");

    private static Store store(ICapabilityProvider holder) {
        Store s = holder.getCapability(CAP).resolve().orElse(null);
        if (s == null) throw new IllegalStateException("FAttach has no storage on " + holder + " (not registered yet, or the holder was removed)");
        return s;
    }

    private static DynamicOps<Tag> ops() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? NbtOps.INSTANCE : RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
    }

    /** Every attached value on one holder; saves the ones with a codec under their id. */
    static final class Store implements ICapabilitySerializable<CompoundTag> {
        private final Map<FAttach<?>, Object> values = new LinkedHashMap<>();
        private final Map<String, Tag> unknown = new LinkedHashMap<>();
        private final LazyOptional<Store> self = LazyOptional.of(() -> this);

        @SuppressWarnings("unchecked")
        <T> T get(FAttach<T> a) {
            return (T) this.values.get(a);
        }

        <T> void put(FAttach<T> a, T value) {
            this.values.put(a, value);
        }

        void remove(FAttach<?> a) {
            this.values.remove(a);
        }

        @Override
        public <C> LazyOptional<C> getCapability(Capability<C> cap, Direction side) {
            return cap == CAP ? this.self.cast() : LazyOptional.empty();
        }

        @Override
        public CompoundTag serializeNBT() {
            CompoundTag tag = new CompoundTag();
            this.unknown.forEach(tag::put);
            DynamicOps<Tag> ops = ops();
            for (Map.Entry<FAttach<?>, Object> e : this.values.entrySet()) write(tag, ops, e.getKey(), e.getValue());
            return tag;
        }

        @SuppressWarnings("unchecked")
        private static <T> void write(CompoundTag tag, DynamicOps<Tag> ops, FAttach<T> a, Object value) {
            if (a.codec == null) return;
            a.codec.encodeStart(ops, (T) value).result().ifPresent(t -> tag.put(a.id.toString(), t));
        }

        @Override
        public void deserializeNBT(CompoundTag tag) {
            DynamicOps<Tag> ops = ops();
            for (String key : tag.getAllKeys()) {
                FAttach<?> a = BY_ID.get(key);
                if (a == null || a.codec == null) this.unknown.put(key, tag.get(key));
                else read(a, ops, tag.get(key));
            }
        }

        private <T> void read(FAttach<T> a, DynamicOps<Tag> ops, Tag tag) {
            a.codec.parse(ops, tag).result().ifPresent(v -> this.values.put(a, v));
        }
    }

    // ---- entity sync over FNet

    /** One attachment value for one entity; {@code data} is empty when the value was removed. */
    record SyncMessage(int entity, String attach, boolean present, byte[] data) { }

    static final FStreamCodec<SyncMessage> SYNC_CODEC = FStreamCodec.composite(
        FStreamCodec.INT, SyncMessage::entity, FStreamCodec.STRING, SyncMessage::attach,
        FStreamCodec.BOOL, SyncMessage::present, FStreamCodec.BYTES, SyncMessage::data, SyncMessage::new);

    private void send(Entity holder, T value) {
        if (this.sync == Sync.NONE || holder.level().isClientSide) return;
        Object msg = message(holder, value);
        if (this.sync == Sync.OWNER) {
            if (holder instanceof ServerPlayer p && p.connection != null) FNet.toPlayer(p, msg);
        } else {
            FNet.toTrackingAndSelf(holder, msg);
        }
    }

    private SyncMessage message(Entity holder, T value) {
        if (value == null) return new SyncMessage(holder.getId(), this.id.toString(), false, new byte[0]);
        var buf = FStreamCodec.buffer(holder.level().registryAccess());
        this.streamCodec.encode(buf, value);
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return new SyncMessage(holder.getId(), this.id.toString(), true, data);
    }

    static void receive(SyncMessage m, FNet.Context ctx) {
        Player player = ctx.player();
        if (player == null || ctx.isServer()) return;
        Entity e = player.level().getEntity(m.entity());
        FAttach<?> a = BY_ID.get(m.attach());
        if (e == null || a == null || a.streamCodec == null) return;
        receiveOne(a, e, m);
    }

    private static <T> void receiveOne(FAttach<T> a, Entity e, SyncMessage m) {
        Store s = e.getCapability(CAP).resolve().orElse(null);
        if (s == null) return;
        if (!m.present()) {
            s.remove(a);
            return;
        }
        var buf = FStreamCodec.buffer(e.level().registryAccess());
        buf.writeBytes(m.data());
        s.put(a, a.streamCodec.decode(buf));
    }

    /** Sends every synced value of the entity to one player (the owner's values only to the owner). */
    static void sendAll(Entity holder, ServerPlayer to) {
        Store s = holder.getCapability(CAP).resolve().orElse(null);
        if (s == null || to.connection == null) return;
        for (FAttach<?> a : ALL) {
            if (a.sync == Sync.TRACKING || (a.sync == Sync.OWNER && holder == to)) sendOne(a, s, holder, to);
        }
    }

    private static <T> void sendOne(FAttach<T> a, Store s, Entity holder, ServerPlayer to) {
        T v = s.get(a);
        if (v != null) FNet.toPlayer(to, a.message(holder, v));
    }

    // ---- loader wiring (Port)

    static void registerCapability(RegisterCapabilitiesEvent event) {
        frozen = true;
        event.register(Store.class);
    }

    static void attach(AttachCapabilitiesEvent<?> event) {
        if (ALL.isEmpty()) return;
        event.addCapability(KEY, new Store());
    }

    static void clone(PlayerEvent.Clone event) {
        Player original = event.getOriginal();
        original.reviveCaps();
        try {
            copy(original, event.getEntity(), event.isWasDeath());
        } finally {
            original.invalidateCaps();
        }
    }

    static void startTracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer p) sendAll(event.getTarget(), p);
    }

    static void playerReady(Player player) {
        if (player instanceof ServerPlayer p) sendAll(p, p);
    }

    static boolean frozen() {
        return frozen;
    }

    public static final class Builder<T> {
        private final FId id;
        private final Supplier<T> defaultValue;
        private Codec<T> codec;
        private boolean copyOnDeath;
        private Sync sync = Sync.NONE;
        private FStreamCodec<T> streamCodec;

        private Builder(FId id, Supplier<T> defaultValue) {
            this.id = id;
            this.defaultValue = defaultValue;
        }

        public Builder<T> codec(Codec<T> codec) {
            this.codec = codec;
            return this;
        }

        public Builder<T> copyOnDeath() {
            this.copyOnDeath = true;
            return this;
        }

        public Builder<T> sync(Sync sync, FStreamCodec<T> streamCodec) {
            this.sync = sync;
            this.streamCodec = streamCodec;
            return this;
        }

        public FAttach<T> build() {
            if (frozen) throw new IllegalStateException("FAttach " + this.id + " made after registration; make it while the mod loads");
            if (this.copyOnDeath && this.codec == null) throw new IllegalStateException("FAttach " + this.id + ": copyOnDeath needs a codec");
            if (this.sync != Sync.NONE && this.streamCodec == null) throw new IllegalStateException("FAttach " + this.id + ": sync needs a stream codec");
            if (BY_ID.containsKey(this.id.toString())) throw new IllegalStateException("FAttach " + this.id + " made twice");
            FAttach<T> a = new FAttach<>(this);
            ALL.add(a);
            BY_ID.put(this.id.toString(), a);
            return a;
        }
    }
}
