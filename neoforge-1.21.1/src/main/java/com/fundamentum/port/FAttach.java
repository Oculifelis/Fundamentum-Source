package com.fundamentum.port;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;

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
 * {@link #set} again (or {@link #resync}).</li>
 * </ul>
 */
public final class FAttach<T> {
    public enum Sync { NONE, OWNER, TRACKING }

    private static final List<FAttach<?>> ALL = new ArrayList<>();
    private static boolean frozen;

    private final FId id;
    private final Supplier<T> defaultValue;
    private final Codec<T> codec;
    private final boolean copyOnDeath;
    private final Sync sync;
    private final FStreamCodec<T> streamCodec;
    private AttachmentType<T> type;

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
        return holder.getExistingDataOrNull(type());
    }

    public boolean has(Entity holder) {
        return holder.hasData(type());
    }

    public void set(Entity holder, T value) {
        holder.setData(type(), value);
    }

    public void remove(Entity holder) {
        holder.removeData(type());
    }

    public T update(Entity holder, UnaryOperator<T> change) {
        T v = change.apply(get(holder));
        set(holder, v);
        return v;
    }

    public void resync(Entity holder) {
        holder.syncData(type());
    }

    // ---- chunk

    public T get(LevelChunk holder) {
        return getIn(holder);
    }

    public T getOrNull(LevelChunk holder) {
        return holder.getExistingDataOrNull(type());
    }

    public boolean has(LevelChunk holder) {
        return holder.hasData(type());
    }

    public void set(LevelChunk holder, T value) {
        holder.setData(type(), value);
        holder.setUnsaved(true);
    }

    public void remove(LevelChunk holder) {
        holder.removeData(type());
        holder.setUnsaved(true);
    }

    public T update(LevelChunk holder, UnaryOperator<T> change) {
        T v = change.apply(get(holder));
        set(holder, v);
        return v;
    }

    public void resync(LevelChunk holder) {
        holder.syncData(type());
    }

    // ---- level

    public T get(Level holder) {
        return getIn(holder);
    }

    public T getOrNull(Level holder) {
        return holder.getExistingDataOrNull(type());
    }

    public boolean has(Level holder) {
        return holder.hasData(type());
    }

    public void set(Level holder, T value) {
        holder.setData(type(), value);
    }

    public void remove(Level holder) {
        holder.removeData(type());
    }

    public T update(Level holder, UnaryOperator<T> change) {
        T v = change.apply(get(holder));
        set(holder, v);
        return v;
    }

    public void resync(Level holder) {
        holder.syncData(type());
    }

    // ---- block entity

    public T get(BlockEntity holder) {
        return getIn(holder);
    }

    public T getOrNull(BlockEntity holder) {
        return holder.getExistingDataOrNull(type());
    }

    public boolean has(BlockEntity holder) {
        return holder.hasData(type());
    }

    public void set(BlockEntity holder, T value) {
        holder.setData(type(), value);
        holder.setChanged();
    }

    public void remove(BlockEntity holder) {
        holder.removeData(type());
        holder.setChanged();
    }

    public T update(BlockEntity holder, UnaryOperator<T> change) {
        T v = change.apply(get(holder));
        set(holder, v);
        return v;
    }

    public void resync(BlockEntity holder) {
        holder.syncData(type());
    }

    // ----

    /**
     * Copies attached data from one entity to another, as a respawn or an End return does: with {@code death} only
     * the copy-on-death values, otherwise every saved value. The loader already does this for players.
     */
    public static void copy(Entity from, Entity to, boolean death) {
        to.copyAttachmentsFrom(from, death);
    }

    /** Writes the value with its codec and reads it back, as a save and load would. */
    public T roundTrip(T value, RegistryAccess access) {
        if (this.codec == null) throw new IllegalStateException(this.id + " has no codec");
        var ops = access.createSerializationContext(NbtOps.INSTANCE);
        Tag tag = this.codec.encodeStart(ops, value).getOrThrow();
        return this.codec.parse(ops, tag).getOrThrow();
    }

    private T getIn(IAttachmentHolder holder) {
        return holder.getData(type());
    }

    private AttachmentType<T> type() {
        if (this.type == null) throw new IllegalStateException("FAttach " + this.id + " is not registered yet");
        return this.type;
    }

    private AttachmentType<T> build() {
        AttachmentType.Builder<T> b = AttachmentType.builder(this.defaultValue);
        if (this.codec != null) b.serialize(this.codec);
        if (this.copyOnDeath) b.copyOnDeath();
        if (this.sync == Sync.OWNER) {
            b.sync((holder, player) -> holder == player, this.streamCodec.vanilla());
        } else if (this.sync == Sync.TRACKING) {
            b.sync(this.streamCodec.vanilla());
        }
        return b.build();
    }

    static void register(RegisterEvent event) {
        if (!event.getRegistryKey().equals(NeoForgeRegistries.Keys.ATTACHMENT_TYPES)) return;
        frozen = true;
        for (FAttach<?> a : ALL) registerOne(event, a);
    }

    private static <T> void registerOne(RegisterEvent event, FAttach<T> a) {
        AttachmentType<T> t = a.build();
        a.type = t;
        event.register(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, a.id.vanilla(), () -> t);
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
            for (FAttach<?> a : ALL) {
                if (a.id.equals(this.id)) throw new IllegalStateException("FAttach " + this.id + " made twice");
            }
            FAttach<T> a = new FAttach<>(this);
            ALL.add(a);
            return a;
        }
    }
}
