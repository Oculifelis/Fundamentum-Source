package com.fundamentum.stamina;

import com.fundamentum.port.FId;
import net.minecraft.world.entity.LivingEntity;

/**
 * One mod's stamina pool, seen through {@link Stamina}. Fundamentum keeps no stamina of its own: the mod that owns the
 * pool (a combat mod, a survival mod) registers a source that reads and changes it.
 */
public interface StaminaSource {
    /** A stable id, usually {@code <modid>:stamina}. Registering another source with the same id replaces this one. */
    FId id();

    /** Higher wins when more than one source handles an entity. */
    default int priority() {
        return 0;
    }

    boolean handles(LivingEntity entity);

    double get(LivingEntity entity);

    double max(LivingEntity entity);

    /** Takes {@code amount}; false and no change when there is less. Never called with a negative amount. */
    boolean spend(LivingEntity entity, double amount);

    /** Adds up to {@code amount}, stopping at {@link #max}; returns how much was added. */
    double restore(LivingEntity entity, double amount);

    /** How much the pool refills per tick right now, for display. */
    default double regenPerTick(LivingEntity entity) {
        return 0.0;
    }

    /** Ticks before regeneration starts again after a spend, for display; 0 when the owner does not say. */
    default int regenDelay(LivingEntity entity) {
        return 0;
    }
}
