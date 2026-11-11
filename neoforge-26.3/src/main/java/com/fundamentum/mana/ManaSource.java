package com.fundamentum.mana;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;

/**
 * One mod's mana (or any spendable resource pool), seen through {@link Mana}. Fundamentum keeps no mana of its own:
 * the mod that owns the pool (Iron's Spells, Ars Nouveau, a mod's own bar) registers a source that reads and changes it.
 * Every method is called on the logical side the caller is on; a source that only knows the server side returns 0 /
 * false on the client.
 */
public interface ManaSource {
    /** A stable id, usually {@code <modid>:mana}. Registering another source with the same id replaces this one. */
    Identifier id();

    /** Higher wins when more than one source handles an entity. Mods' own pools default to 0; a pack can raise one. */
    default int priority() {
        return 0;
    }

    /** True when this source has a pool for the entity (for example only players, or only casters with a spell book). */
    boolean handles(LivingEntity entity);

    /** The mana the entity has now. */
    double get(LivingEntity entity);

    /** The size of the entity's pool. */
    double max(LivingEntity entity);

    /**
     * Takes {@code amount} from the pool. Returns false and changes nothing when the entity has less than that. Never
     * called with a negative amount.
     */
    boolean spend(LivingEntity entity, double amount);

    /** Adds up to {@code amount}, stopping at {@link #max}; returns how much was added. Never called with a negative amount. */
    double restore(LivingEntity entity, double amount);

    /** How much the pool refills per tick right now, for display; 0 when the owner does not say. */
    default double regenPerTick(LivingEntity entity) {
        return 0.0;
    }
}
