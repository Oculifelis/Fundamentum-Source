package com.fundamentum.element;

import com.fundamentum.damage.Elements;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import org.jetbrains.annotations.Nullable;

/**
 * An element, written the way JSON writes it: a damage type ({@code "minecraft:magic"}) or a damage type tag
 * ({@code "#fundamentum:element/fire"}). There is no element registry: an element is a damage type, or a set of them.
 * A tag that is one of Fundamentum's {@link Elements} ({@code #<ns>:element/<e>}) also matches hits that are of that
 * element through their weapon's item tag or a classifier (a Fire Aspect sword's plain hit counts as fire).
 */
public record ElementRef(ResourceLocation id, boolean tag) {
    public static final Codec<ElementRef> CODEC = Codec.STRING.comapFlatMap(ElementRef::read, ElementRef::toString);
    /** One element or a list of them: {@code "#fundamentum:element/water"} or {@code ["#fundamentum:element/water", "minecraft:drown"]}. */
    public static final Codec<List<ElementRef>> LIST_CODEC = com.mojang.serialization.Codec.either(CODEC.listOf(), CODEC).xmap(
        e -> e.map(l -> l, List::of), l -> l.size() == 1 ? com.mojang.datafixers.util.Either.right(l.get(0)) : com.mojang.datafixers.util.Either.left(l));

    public static ElementRef parse(String text) {
        return read(text).getOrThrow();
    }

    public static DataResult<ElementRef> read(String text) {
        boolean tag = text.startsWith("#");
        ResourceLocation id = ResourceLocation.tryParse(tag ? text.substring(1) : text);
        return id == null ? DataResult.error(() -> "not a damage type or #tag: " + text) : DataResult.success(new ElementRef(id, tag));
    }

    public static ElementRef of(ResourceKey<DamageType> type) {
        return new ElementRef(type.location(), false);
    }

    public TagKey<DamageType> tagKey() {
        return TagKey.create(Registries.DAMAGE_TYPE, this.id);
    }

    public ResourceKey<DamageType> typeKey() {
        return ResourceKey.create(Registries.DAMAGE_TYPE, this.id);
    }

    /** The Fundamentum element this tag is, if any ({@code #fundamentum:element/fire} is {@link Elements#FIRE}). */
    public Elements.@Nullable Element element() {
        if (!this.tag) {
            return null;
        }
        for (Elements.Element element : Elements.all()) {
            if (element.damageTypes().location().equals(this.id)) {
                return element;
            }
        }
        return null;
    }

    /** Whether a hit is of this element. */
    public boolean matches(DamageSource source) {
        if (!this.tag) {
            return source.is(typeKey());
        }
        if (source.is(tagKey())) {
            return true;
        }
        Elements.Element element = element();
        return element != null && element.matches(source);
    }

    /** Whether a damage type is of this element. */
    public boolean matches(Holder<DamageType> type) {
        return this.tag ? type.is(tagKey()) : type.is(typeKey());
    }

    public static boolean any(List<ElementRef> refs, DamageSource source) {
        for (ElementRef ref : refs) {
            if (ref.matches(source)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an aura stored under {@code key} counts as this element: the same ref, or this is a tag and the aura's key
     * is a damage type in it.
     */
    public boolean covers(ElementRef key, RegistryAccess registries) {
        if (this.equals(key)) {
            return true;
        }
        if (!this.tag || key.tag) {
            return false;
        }
        Optional<Holder.Reference<DamageType>> type = registries.lookupOrThrow(Registries.DAMAGE_TYPE).get(key.typeKey());
        return type.isPresent() && type.get().is(tagKey());
    }

    /**
     * The element's name: the lang key {@code element.<ns>.<path>} (slashes become dots, so
     * {@code #fundamentum:element/fire} is {@code element.fundamentum.element.fire}), else the raw text.
     */
    public MutableComponent name() {
        return Component.translatableWithFallback(langKey(), toString());
    }

    public String langKey() {
        return "element." + this.id.getNamespace() + "." + this.id.getPath().replace('/', '.');
    }

    @Override
    public String toString() {
        return (this.tag ? "#" : "") + this.id;
    }
}
