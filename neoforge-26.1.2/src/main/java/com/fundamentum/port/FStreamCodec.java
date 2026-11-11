package com.fundamentum.port;

import com.mojang.datafixers.util.Function3;
import com.mojang.datafixers.util.Function4;
import com.mojang.datafixers.util.Function5;
import com.mojang.datafixers.util.Function6;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * A packet codec that is the same type on every version: it writes to and reads from a {@link FriendlyByteBuf}. On
 * 26.x and 1.21.1 the buffer is the vanilla {@code RegistryFriendlyByteBuf} and {@link #vanilla()} turns this into a
 * vanilla {@code StreamCodec}; on 1.20.1 it is a plain {@code FriendlyByteBuf}. Build packet codecs from the constants
 * and {@link #composite} instead of naming the vanilla type.
 */
public interface FStreamCodec<T> {
    void encode(FriendlyByteBuf buf, T value);

    T decode(FriendlyByteBuf buf);

    static <T> FStreamCodec<T> of(BiConsumer<FriendlyByteBuf, T> encoder, Function<FriendlyByteBuf, T> decoder) {
        return new FStreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, T value) {
                encoder.accept(buf, value);
            }

            @Override
            public T decode(FriendlyByteBuf buf) {
                return decoder.apply(buf);
            }
        };
    }

    FStreamCodec<Boolean> BOOL = of(FriendlyByteBuf::writeBoolean, FriendlyByteBuf::readBoolean);
    /** A variable-length int. */
    FStreamCodec<Integer> INT = of(FriendlyByteBuf::writeVarInt, FriendlyByteBuf::readVarInt);
    /** A variable-length long. */
    FStreamCodec<Long> LONG = of(FriendlyByteBuf::writeVarLong, FriendlyByteBuf::readVarLong);
    FStreamCodec<Float> FLOAT = of(FriendlyByteBuf::writeFloat, FriendlyByteBuf::readFloat);
    FStreamCodec<Double> DOUBLE = of(FriendlyByteBuf::writeDouble, FriendlyByteBuf::readDouble);
    FStreamCodec<String> STRING = of(FriendlyByteBuf::writeUtf, b -> b.readUtf());
    FStreamCodec<UUID> UUID = of((b, v) -> b.writeUUID(v), b -> b.readUUID());
    FStreamCodec<byte[]> BYTES = of((b, v) -> b.writeByteArray(v), b -> b.readByteArray());
    FStreamCodec<BlockPos> BLOCK_POS = of((b, v) -> b.writeBlockPos(v), b -> b.readBlockPos());
    FStreamCodec<FId> ID = STRING.map(FId::of, FId::toString);
    /** A compound tag; an empty tag stands in for none. */
    FStreamCodec<CompoundTag> NBT = of((b, v) -> b.writeNbt(v), b -> {
        CompoundTag t = b.readNbt();
        return t == null ? new CompoundTag() : t;
    });
    /** An item stack with its data (empty stacks allowed). Needs the network's buffer (or {@link #buffer}). */
    FStreamCodec<ItemStack> ITEM_STACK = of(
        (b, s) -> ItemStack.OPTIONAL_STREAM_CODEC.encode(registryBuf(b), s),
        b -> ItemStack.OPTIONAL_STREAM_CODEC.decode(registryBuf(b)));

    static <T> FStreamCodec<T> unit(T value) {
        return of((b, v) -> { }, b -> value);
    }

    static <E extends Enum<E>> FStreamCodec<E> enumOf(Class<E> type) {
        E[] values = type.getEnumConstants();
        return of((b, v) -> b.writeVarInt(v.ordinal()), b -> values[b.readVarInt()]);
    }

    default <R> FStreamCodec<R> map(Function<? super T, ? extends R> to, Function<? super R, ? extends T> from) {
        FStreamCodec<T> self = this;
        return of((b, v) -> self.encode(b, from.apply(v)), b -> to.apply(self.decode(b)));
    }

    default FStreamCodec<List<T>> list() {
        FStreamCodec<T> self = this;
        return of((b, list) -> {
            b.writeVarInt(list.size());
            for (T v : list) self.encode(b, v);
        }, b -> {
            int n = b.readVarInt();
            List<T> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(self.decode(b));
            return list;
        });
    }

    default FStreamCodec<Optional<T>> optional() {
        FStreamCodec<T> self = this;
        return of((b, o) -> {
            b.writeBoolean(o.isPresent());
            o.ifPresent(v -> self.encode(b, v));
        }, b -> b.readBoolean() ? Optional.of(self.decode(b)) : Optional.empty());
    }

    static <K, V> FStreamCodec<Map<K, V>> mapOf(FStreamCodec<K> keys, FStreamCodec<V> values) {
        return of((b, m) -> {
            b.writeVarInt(m.size());
            m.forEach((k, v) -> {
                keys.encode(b, k);
                values.encode(b, v);
            });
        }, b -> {
            int n = b.readVarInt();
            Map<K, V> m = new HashMap<>();
            for (int i = 0; i < n; i++) m.put(keys.decode(b), values.decode(b));
            return m;
        });
    }

    static <T, A> FStreamCodec<T> composite(FStreamCodec<A> a, Function<T, A> ga, Function<A, T> make) {
        return of((b, v) -> a.encode(b, ga.apply(v)), b -> make.apply(a.decode(b)));
    }

    static <T, A, B> FStreamCodec<T> composite(FStreamCodec<A> a, Function<T, A> ga, FStreamCodec<B> bc, Function<T, B> gb,
                                               BiFunction<A, B, T> make) {
        return of((b, v) -> {
            a.encode(b, ga.apply(v));
            bc.encode(b, gb.apply(v));
        }, b -> {
            A va = a.decode(b);
            return make.apply(va, bc.decode(b));
        });
    }

    static <T, A, B, C> FStreamCodec<T> composite(FStreamCodec<A> a, Function<T, A> ga, FStreamCodec<B> bc, Function<T, B> gb,
                                                  FStreamCodec<C> c, Function<T, C> gc, Function3<A, B, C, T> make) {
        return of((b, v) -> {
            a.encode(b, ga.apply(v));
            bc.encode(b, gb.apply(v));
            c.encode(b, gc.apply(v));
        }, b -> {
            A va = a.decode(b);
            B vb = bc.decode(b);
            return make.apply(va, vb, c.decode(b));
        });
    }

    static <T, A, B, C, D> FStreamCodec<T> composite(FStreamCodec<A> a, Function<T, A> ga, FStreamCodec<B> bc, Function<T, B> gb,
                                                     FStreamCodec<C> c, Function<T, C> gc, FStreamCodec<D> d, Function<T, D> gd,
                                                     Function4<A, B, C, D, T> make) {
        return of((b, v) -> {
            a.encode(b, ga.apply(v));
            bc.encode(b, gb.apply(v));
            c.encode(b, gc.apply(v));
            d.encode(b, gd.apply(v));
        }, b -> {
            A va = a.decode(b);
            B vb = bc.decode(b);
            C vc = c.decode(b);
            return make.apply(va, vb, vc, d.decode(b));
        });
    }

    static <T, A, B, C, D, E> FStreamCodec<T> composite(FStreamCodec<A> a, Function<T, A> ga, FStreamCodec<B> bc, Function<T, B> gb,
                                                        FStreamCodec<C> c, Function<T, C> gc, FStreamCodec<D> d, Function<T, D> gd,
                                                        FStreamCodec<E> e, Function<T, E> ge, Function5<A, B, C, D, E, T> make) {
        return of((b, v) -> {
            a.encode(b, ga.apply(v));
            bc.encode(b, gb.apply(v));
            c.encode(b, gc.apply(v));
            d.encode(b, gd.apply(v));
            e.encode(b, ge.apply(v));
        }, b -> {
            A va = a.decode(b);
            B vb = bc.decode(b);
            C vc = c.decode(b);
            D vd = d.decode(b);
            return make.apply(va, vb, vc, vd, e.decode(b));
        });
    }

    static <T, A, B, C, D, E, F> FStreamCodec<T> composite(FStreamCodec<A> a, Function<T, A> ga, FStreamCodec<B> bc, Function<T, B> gb,
                                                           FStreamCodec<C> c, Function<T, C> gc, FStreamCodec<D> d, Function<T, D> gd,
                                                           FStreamCodec<E> e, Function<T, E> ge, FStreamCodec<F> f, Function<T, F> gf,
                                                           Function6<A, B, C, D, E, F, T> make) {
        return of((b, v) -> {
            a.encode(b, ga.apply(v));
            bc.encode(b, gb.apply(v));
            c.encode(b, gc.apply(v));
            d.encode(b, gd.apply(v));
            e.encode(b, ge.apply(v));
            f.encode(b, gf.apply(v));
        }, b -> {
            A va = a.decode(b);
            B vb = bc.decode(b);
            C vc = c.decode(b);
            D vd = d.decode(b);
            E ve = e.decode(b);
            return make.apply(va, vb, vc, vd, ve, f.decode(b));
        });
    }

    /** A fresh buffer like the network's, for tests and for packing values by hand. */
    static FriendlyByteBuf buffer(RegistryAccess access) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
    }

    /** Encodes and decodes the value once, as the network would. */
    default T roundTrip(T value, RegistryAccess access) {
        FriendlyByteBuf buf = buffer(access);
        try {
            encode(buf, value);
            T out = decode(buf);
            if (buf.readableBytes() != 0) throw new IllegalStateException(buf.readableBytes() + " bytes left after decoding");
            return out;
        } finally {
            buf.release();
        }
    }

    /** This codec as a vanilla stream codec (26.x and 1.21.1 only). */
    default StreamCodec<RegistryFriendlyByteBuf, T> vanilla() {
        FStreamCodec<T> self = this;
        return StreamCodec.of((buf, v) -> self.encode(buf, v), self::decode);
    }

    private static RegistryFriendlyByteBuf registryBuf(FriendlyByteBuf buf) {
        if (buf instanceof RegistryFriendlyByteBuf r) return r;
        throw new IllegalStateException("Item stacks need the network's buffer (or FStreamCodec.buffer)");
    }
}
