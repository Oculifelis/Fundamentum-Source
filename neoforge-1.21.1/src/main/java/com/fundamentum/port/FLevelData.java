package com.fundamentum.port;

import com.mojang.serialization.Codec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/**
 * One value saved per dimension (or once per world, on the overworld) in the world's {@code data} folder, the same calls
 * on every version. Vanilla saved data underneath; the file is {@code data/<namespace>/<path>.dat} on 26.x and
 * {@code data/<namespace>_<path>.dat} before. Values are treated as immutable: {@link #set} or {@link #update} them,
 * or call {@link #markDirty} after changing one in place.
 */
public final class FLevelData<T> {
    private final FId id;
    private final Codec<T> codec;
    private final Supplier<T> defaultValue;
    private final SavedData.Factory<Box<T>> type;

    private FLevelData(FId id, Codec<T> codec, Supplier<T> defaultValue) {
        this.id = id;
        this.codec = codec;
        this.defaultValue = defaultValue;
        this.type = new SavedData.Factory<>(() -> new Box<>(this, defaultValue.get()), (tag, registries) -> {
            Tag value = tag.get("value");
            T v = value == null ? defaultValue.get() : codec.parse(registries.createSerializationContext(NbtOps.INSTANCE), value).getOrThrow();
            return new Box<>(this, v);
        });
    }

    public static <T> FLevelData<T> of(FId id, Codec<T> codec, Supplier<T> defaultValue) {
        return new FLevelData<>(id, codec, defaultValue);
    }

    public FId id() {
        return this.id;
    }

    public T get(ServerLevel level) {
        return box(level).value;
    }

    public void set(ServerLevel level, T value) {
        Box<T> b = box(level);
        b.value = value;
        b.setDirty();
    }

    public T update(ServerLevel level, UnaryOperator<T> change) {
        T v = change.apply(get(level));
        set(level, v);
        return v;
    }

    public void markDirty(ServerLevel level) {
        box(level).setDirty();
    }

    /** The world-wide value, kept with the overworld. */
    public T get(MinecraftServer server) {
        return get(server.overworld());
    }

    public void set(MinecraftServer server, T value) {
        set(server.overworld(), value);
    }

    public T update(MinecraftServer server, UnaryOperator<T> change) {
        return update(server.overworld(), change);
    }

    public void markDirty(MinecraftServer server) {
        markDirty(server.overworld());
    }

    /** Where this value is saved for the level. */
    public Path file(ServerLevel level) {
        Path dim = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT));
        return dim.resolve("data").resolve(this.id.flat() + ".dat");
    }

    /** Reads the value straight from the saved file (what the next load would see), or null when there is no file yet. */
    public T readFromDisk(ServerLevel level) throws IOException {
        Path file = file(level);
        if (!Files.exists(file)) return null;
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        CompoundTag data = root.getCompound("data");
        Tag value = data.get("value");
        if (value == null) return null;
        return this.codec.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), value).getOrThrow();
    }

    private Box<T> box(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(this.type, this.id.flat());
    }

    private static final class Box<T> extends SavedData {
        private final FLevelData<T> owner;
        T value;

        Box(FLevelData<T> owner, T value) {
            this.owner = owner;
            this.value = value;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.put("value", this.owner.codec.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), this.value).getOrThrow());
            return tag;
        }
    }
}
