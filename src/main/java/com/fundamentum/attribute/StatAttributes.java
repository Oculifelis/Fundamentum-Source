package com.fundamentum.attribute;

import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Combat stats every mod can share, so gear, armour and effects from any of them stack. Every living entity has them.
 * Fundamentum only holds the values: the mod that fires, reloads or explodes reads the final value and applies it.
 * Multipliers default to 1, chances and fractions to 0.
 */
public final class StatAttributes {
    private static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(ForgeRegistries.ATTRIBUTES, "fundamentum");

    /** Chance (0 to 1) that a hit is critical. */
    public static final RegistryObject<Attribute> CRIT_CHANCE = add("crit_chance", 0, 0, 1);
    /** Damage multiplier of a critical hit. */
    public static final RegistryObject<Attribute> CRIT_DAMAGE = add("crit_damage", 1.5, 1, 100);
    /** Multiplier on ranged and projectile damage. */
    public static final RegistryObject<Attribute> RANGED_DAMAGE = add("ranged_damage", 1, 0, 1024);
    /** Multiplier on shots per second. */
    public static final RegistryObject<Attribute> FIRE_RATE = add("fire_rate", 1, 0.05, 20);
    /** Multiplier on reload speed (2 reloads in half the time). */
    public static final RegistryObject<Attribute> RELOAD_SPEED = add("reload_speed", 1, 0.05, 20);
    /** Chance (0 to 1) that a shot uses no ammo. */
    public static final RegistryObject<Attribute> AMMO_CONSERVATION = add("ammo_conservation", 0, 0, 1);
    /** Fraction (0 to 1) of the target's armour ignored. */
    public static final RegistryObject<Attribute> ARMOR_PIERCE = add("armor_pierce", 0, 0, 1);
    /** Multiplier on explosion radius. */
    public static final RegistryObject<Attribute> BLAST_RADIUS = add("blast_radius", 1, 0, 16);
    /** Multiplier on damage taken from one's own explosions (0 = none). */
    public static final RegistryObject<Attribute> BLAST_SELF_DAMAGE = add("blast_self_damage", 1, 0, 16);
    /** Multiplier on projectile speed. */
    public static final RegistryObject<Attribute> PROJECTILE_SPEED = add("projectile_speed", 1, 0.05, 20);
    /** Multiplier on spread (0 = perfectly accurate). */
    public static final RegistryObject<Attribute> SPREAD = add("spread", 1, 0, 16);
    /** Multiplier on how long use sickness lasts (see com.fundamentum.sickness.Sickness). */
    public static final RegistryObject<Attribute> SICKNESS_DURATION = add("sickness_duration", 1, 0, 16);
    /** Multiplier on the damage a player's minions deal (see com.fundamentum.summon.Summons). */
    public static final RegistryObject<Attribute> SUMMON_DAMAGE = add("summon_damage", 1, 0, 1024);
    /** Multiplier on wing flight time (see com.fundamentum.wings.Wings). */
    public static final RegistryObject<Attribute> WING_FLIGHT_TIME = add("wing_flight_time", 1, 0, 16);

    /** Registers one more Fundamentum attribute (the element and class ones); call it while mods construct. */
    public static RegistryObject<Attribute> add(String name, double base, double min, double max) {
        return ATTRIBUTES.register(name, () -> new RangedAttribute("attribute.name.fundamentum." + name, base, min, max).setSyncable(true));
    }

    public static void init(IEventBus modBus) {
        ATTRIBUTES.register(modBus);
        modBus.addListener(StatAttributes::attach);
    }

    private static void attach(EntityAttributeModificationEvent event) {
        for (var type : event.getTypes()) {
            for (var attribute : ATTRIBUTES.getEntries()) {
                if (!event.has(type, attribute.get())) {
                    event.add(type, attribute.get());
                }
            }
        }
    }

    private StatAttributes() {}
}
