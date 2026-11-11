package com.fundamentum.port;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * A typed value on an item stack, the same calls on every version: a data component on 26.x and 1.21.1, a tag
 * {@code "namespace:path"} inside the stack's NBT on 1.20.1. Make one with {@link #of} while the mod is being
 * constructed (a static field). Values must be immutable (records, strings, numbers): stacks compare and copy them.
 */
public final class FStackData<T> {
    private static final List<FStackData<?>> ALL = new ArrayList<>();
    private static boolean frozen;

    private final FId id;
    private final String key;
    private final Codec<T> codec;
    private final FStreamCodec<T> streamCodec;

    private FStackData(FId id, Codec<T> codec, FStreamCodec<T> streamCodec) {
        this.id = id;
        this.key = id.toString();
        this.codec = codec;
        this.streamCodec = streamCodec;
    }

    public static <T> FStackData<T> of(FId id, Codec<T> codec, FStreamCodec<T> streamCodec) {
        if (frozen) throw new IllegalStateException("FStackData " + id + " made after registration; make it while the mod loads");
        for (FStackData<?> d : ALL) {
            if (d.id.equals(id)) throw new IllegalStateException("FStackData " + id + " made twice");
        }
        FStackData<T> d = new FStackData<>(id, codec, streamCodec);
        ALL.add(d);
        return d;
    }

    public FId id() {
        return this.id;
    }

    public Codec<T> codec() {
        return this.codec;
    }

    /** The value, or null when the stack has none. */
    public T get(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(this.key)) return null;
        return this.codec.parse(ops(), tag.get(this.key)).result().orElse(null);
    }

    public T getOr(ItemStack stack, T fallback) {
        T v = get(stack);
        return v != null ? v : fallback;
    }

    public boolean has(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(this.key);
    }

    public void set(ItemStack stack, T value) {
        Tag t = this.codec.encodeStart(ops(), value).getOrThrow(false, msg -> { });
        stack.getOrCreateTag().put(this.key, t);
    }

    public void remove(ItemStack stack) {
        stack.removeTagKey(this.key);
    }

    public T update(ItemStack stack, T fallback, UnaryOperator<T> change) {
        T v = change.apply(getOr(stack, fallback));
        set(stack, v);
        return v;
    }

    private static com.mojang.serialization.DynamicOps<Tag> ops() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? NbtOps.INSTANCE : RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
    }

    /** The NBT keys used by stack data, so custom data can leave them out. */
    static Set<String> keys() {
        Set<String> keys = new java.util.HashSet<>();
        for (FStackData<?> d : ALL) keys.add(d.key);
        return keys;
    }

    /** Called by the loader wiring once registration starts; later {@link #of} calls are refused. */
    static void freeze() {
        frozen = true;
    }
}
