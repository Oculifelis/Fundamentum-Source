package com.fundamentum.damage;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Damage types every mod can deal instead of registering its own: one per element ({@code fundamentum:fire} ...
 * {@code fundamentum:arcane}), each already in {@code #fundamentum:element/<e>} and {@code #fundamentum:class/mage}
 * (they are spells' damage; a fire sword or a fire bullet stays melee or marksman and gets its element from its item
 * tag), and {@code fundamentum:bullet}, in {@code #fundamentum:class/marksman} and {@code #minecraft:is_projectile}.
 * Fire is also {@code #minecraft:is_fire} (fire-immune creatures shrug it off), ice {@code #minecraft:is_freezing}
 * (blazes and magma cubes take it five times over) and electric {@code #minecraft:is_lightning}. They are data, in
 * {@code data/fundamentum/damage_type/}, with death messages {@code death.attack.fundamentum.<name>} (and
 * {@code .player}, {@code .item}). Deal one with {@code target.hurt(SharedDamage.source(level, SharedDamage.HOLY, projectile, shooter), amount)}.
 */
public final class SharedDamage {
    public static final ResourceKey<DamageType> FIRE = key("fire");
    /** Soul fire (1.25.6): fire too (the element is a variant of fire), and it sets what it hits soul-burning. */
    public static final ResourceKey<DamageType> SOUL_FIRE = key("soul_fire");
    public static final ResourceKey<DamageType> ICE = key("ice");
    public static final ResourceKey<DamageType> WATER = key("water");
    public static final ResourceKey<DamageType> ELECTRIC = key("electric");
    public static final ResourceKey<DamageType> EARTH = key("earth");
    public static final ResourceKey<DamageType> AIR = key("air");
    public static final ResourceKey<DamageType> POISON = key("poison");
    public static final ResourceKey<DamageType> HOLY = key("holy");
    public static final ResourceKey<DamageType> SHADOW = key("shadow");
    public static final ResourceKey<DamageType> ARCANE = key("arcane");
    public static final ResourceKey<DamageType> BULLET = key("bullet");

    private static ResourceKey<DamageType> key(String name) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath("fundamentum", name));
    }

    /** The shared damage type of a built-in element ({@code fundamentum:<e>}), for any other element its own id. */
    public static ResourceKey<DamageType> of(Elements.Element element) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, element.id());
    }

    /** Damage with no one behind it. */
    public static DamageSource source(Level level, ResourceKey<DamageType> type) {
        return source(level, type, null, null);
    }

    /** Damage dealt directly by {@code attacker} (a spell from the hand, a touch). */
    public static DamageSource source(Level level, ResourceKey<DamageType> type, @Nullable Entity attacker) {
        return source(level, type, attacker, attacker);
    }

    /** Damage from {@code direct} (a projectile, a cloud) on behalf of {@code attacker} (who fired or cast it). */
    public static DamageSource source(Level level, ResourceKey<DamageType> type, @Nullable Entity direct, @Nullable Entity attacker) {
        return new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(type), direct, attacker);
    }

    private SharedDamage() {}
}
