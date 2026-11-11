package com.fundamentum.port;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

/** Tag keys from an {@link FId}, the same calls on every version. */
public final class FTags {
    private FTags() { }

    public static TagKey<Item> item(FId id) {
        return TagKey.create(Registries.ITEM, id.vanilla());
    }

    public static TagKey<Block> block(FId id) {
        return TagKey.create(Registries.BLOCK, id.vanilla());
    }

    public static TagKey<EntityType<?>> entity(FId id) {
        return TagKey.create(Registries.ENTITY_TYPE, id.vanilla());
    }

    public static TagKey<Biome> biome(FId id) {
        return TagKey.create(Registries.BIOME, id.vanilla());
    }

    public static TagKey<Fluid> fluid(FId id) {
        return TagKey.create(Registries.FLUID, id.vanilla());
    }

    public static <T> TagKey<T> of(ResourceKey<? extends Registry<T>> registry, FId id) {
        return TagKey.create(registry, id.vanilla());
    }

    /** True when the entity type is in the tag (EntityType.is was removed in 26.x). */
    public static boolean is(EntityType<?> type, TagKey<EntityType<?>> tag) {
        return type.builtInRegistryHolder().is(tag);
    }

    public static FId id(TagKey<?> tag) {
        return FId.from(tag.location());
    }
}
