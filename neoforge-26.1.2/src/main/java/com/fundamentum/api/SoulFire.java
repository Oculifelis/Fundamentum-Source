package com.fundamentum.api;

import com.fundamentum.soulfire.SoulFlames;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Soul burning (1.25.6): an entity set on fire by a soul-fire source burns with blue soul flames instead of orange ones,
 * takes {@code soulFireBurnDamage} (2 HP by default, like the soul fire block) per second instead of 1, and its burn hits
 * are the damage type {@code fundamentum:on_soul_fire} (fire, and the element {@code fundamentum:soul_fire}).
 * The state is a synced flag on the entity; it clears when the fire goes out, the entity touches normal fire or lava,
 * or it is set alight again by a non-soul source.
 * <p>Sources: blocks in {@link #SOURCES} (soul fire, soul campfires), projectiles in {@link #PROJECTILES} and weapons in
 * {@link #WEAPONS} that set what they hit on fire, every hit of the soul fire element, and this API.
 * Call from the server; the client only reads.
 */
public final class SoulFire {
    /** Blocks that make an entity burning inside them soul-burn: {@code #fundamentum:soul_fire_sources}. */
    public static final TagKey<Block> SOURCES = TagKey.create(Registries.BLOCK, id("soul_fire_sources"));
    /** Projectiles whose fire is soul fire: {@code #fundamentum:soul_fire_projectiles}. */
    public static final TagKey<EntityType<?>> PROJECTILES = TagKey.create(Registries.ENTITY_TYPE, id("soul_fire_projectiles"));
    /** Melee weapons whose fire (Fire Aspect and the like) is soul fire: {@code #fundamentum:soul_fire_weapons}. */
    public static final TagKey<Item> WEAPONS = TagKey.create(Registries.ITEM, id("soul_fire_weapons"));

    /** Sets {@code entity} on fire for at least {@code ticks} ticks, burning with soul fire (does nothing to fire-immune ones). */
    public static void ignite(Entity entity, int ticks) {
        SoulFlames.ignite(entity, ticks);
    }

    /** Whether the entity is burning with soul fire right now (both sides). */
    public static boolean isSoulBurning(Entity entity) {
        return SoulFlames.isSoulBurning(entity);
    }

    /** Turns soul burning back into ordinary burning (the fire itself stays; put it out with {@link Entity#clearFire}). */
    public static void clear(Entity entity) {
        SoulFlames.clear(entity);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("fundamentum", path);
    }

    private SoulFire() {}
}
