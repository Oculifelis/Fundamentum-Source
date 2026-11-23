package com.fundamentum.port;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Registration with the same calls on every version. Call these while the mod is being constructed (static fields); the
 * returned {@link Ref} is filled when the loader registers the game's content. The version differences are handled
 * here: 26.x needs the id inside item and block properties and entity builders, 1.20.1 and 1.21.1 do not.
 */
public final class FReg {
    private FReg() { }

    /** A registered thing, available once registration has run. */
    public static final class Ref<T> implements Supplier<T> {
        private final FId id;
        private T value;

        private Ref(FId id) {
            this.id = id;
        }

        public FId id() {
            return this.id;
        }

        @Override
        public T get() {
            if (this.value == null) throw new IllegalStateException(this.id + " is not registered yet");
            return this.value;
        }

        public boolean bound() {
            return this.value != null;
        }
    }

    private record Pending<T>(ResourceKey<? extends Registry<T>> registry, Ref<T> ref, Function<ResourceKey<T>, T> make) { }

    private static final List<Pending<?>> PENDING = new ArrayList<>();

    private static <T> Ref<T> add(ResourceKey<? extends Registry<T>> registry, FId id, Function<ResourceKey<T>, T> make) {
        Ref<T> ref = new Ref<>(id);
        PENDING.add(new Pending<>(registry, ref, make));
        return ref;
    }

    public static <B extends Block> Ref<B> block(FId id, Function<BlockBehaviour.Properties, B> factory, BlockBehaviour.Properties props) {
        Ref<B> ref = new Ref<>(id);
        PENDING.add(new Pending<Block>(Registries.BLOCK, castRef(ref), key -> factory.apply(props)));
        return ref;
    }

    public static <I extends Item> Ref<I> item(FId id, Function<Item.Properties, I> factory, Item.Properties props) {
        Ref<I> ref = new Ref<>(id);
        PENDING.add(new Pending<Item>(Registries.ITEM, castRef(ref), key -> factory.apply(props)));
        return ref;
    }

    public static Ref<Item> item(FId id, Item.Properties props) {
        return item(id, Item::new, props);
    }

    /** The item for a block registered with {@link #block}, under the same id. */
    public static Ref<Item> blockItem(Ref<? extends Block> block, Item.Properties props) {
        return add(Registries.ITEM, block.id(), key -> new BlockItem(block.get(), props));
    }

    public static <E extends Entity> Ref<EntityType<E>> entity(FId id, EntityType.Builder<E> builder) {
        Ref<EntityType<E>> ref = new Ref<>(id);
        PENDING.add(new Pending<EntityType<?>>(Registries.ENTITY_TYPE, castRef(ref), key -> builder.build(key.location().toString())));
        return ref;
    }

    public static <M extends MobEffect> Ref<M> effect(FId id, Supplier<M> factory) {
        Ref<M> ref = new Ref<>(id);
        PENDING.add(new Pending<MobEffect>(Registries.MOB_EFFECT, castRef(ref), key -> factory.get()));
        return ref;
    }

    public static Ref<SoundEvent> sound(FId id) {
        return add(Registries.SOUND_EVENT, id, key -> SoundEvent.createVariableRangeEvent(id.vanilla()));
    }

    /** Anything else, by its vanilla registry key ({@code Registries.X}). */
    public static <T> Ref<T> other(ResourceKey<? extends Registry<T>> registry, FId id, Supplier<? extends T> factory) {
        return add(registry, id, key -> factory.get());
    }

    @SuppressWarnings("unchecked")
    private static <T> Ref<T> castRef(Ref<?> ref) {
        return (Ref<T>) ref;
    }

    static void register(RegisterEvent event) {
        for (Pending<?> p : PENDING) {
            if (p.registry.equals(event.getRegistryKey())) registerOne(event, p);
        }
    }

    private static <T> void registerOne(RegisterEvent event, Pending<T> p) {
        event.register(p.registry, p.ref.id.vanilla(), () -> {
            ResourceKey<T> key = ResourceKey.create(p.registry, p.ref.id.vanilla());
            T value = p.make.apply(key);
            p.ref.value = value;
            return value;
        });
    }
}
