package com.fundamentum.port;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * A typed value on an item stack, the same calls on every version: a data component on 26.x and 1.21.1, a tag
 * {@code "namespace:path"} inside the stack's NBT on 1.20.1. Make one with {@link #of} while the mod is being
 * constructed (a static field). Values must be immutable (records, strings, numbers): stacks compare and copy them.
 */
public final class FStackData<T> {
    private static final List<FStackData<?>> ALL = new ArrayList<>();
    private static boolean frozen;

    private final FId id;
    private final Codec<T> codec;
    private final FStreamCodec<T> streamCodec;
    private DataComponentType<T> type;

    private FStackData(FId id, Codec<T> codec, FStreamCodec<T> streamCodec) {
        this.id = id;
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
        return stack.get(type());
    }

    public T getOr(ItemStack stack, T fallback) {
        T v = get(stack);
        return v != null ? v : fallback;
    }

    public boolean has(ItemStack stack) {
        return stack.has(type());
    }

    public void set(ItemStack stack, T value) {
        stack.set(type(), value);
    }

    public void remove(ItemStack stack) {
        stack.remove(type());
    }

    public T update(ItemStack stack, T fallback, UnaryOperator<T> change) {
        T v = change.apply(getOr(stack, fallback));
        set(stack, v);
        return v;
    }

    /** The vanilla component (26.x and 1.21.1 only). */
    public DataComponentType<T> vanilla() {
        return type();
    }

    private DataComponentType<T> type() {
        if (this.type == null) throw new IllegalStateException("FStackData " + this.id + " is not registered yet");
        return this.type;
    }

    static void register(RegisterEvent event) {
        if (!event.getRegistryKey().equals(Registries.DATA_COMPONENT_TYPE)) return;
        frozen = true;
        for (FStackData<?> d : ALL) registerOne(event, d);
    }

    private static <T> void registerOne(RegisterEvent event, FStackData<T> d) {
        DataComponentType<T> t = DataComponentType.<T>builder().persistent(d.codec).networkSynchronized(d.streamCodec.vanilla()).build();
        d.type = t;
        event.register(Registries.DATA_COMPONENT_TYPE, d.id.vanilla(), () -> t);
    }
}
