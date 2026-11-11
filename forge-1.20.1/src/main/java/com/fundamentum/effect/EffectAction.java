package com.fundamentum.effect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * Something a status effect does while it lasts. Types are registered with {@link EffectActions#register} and named in an
 * effect's {@code actions} list by {@code "type"}. Every hook runs on the server thread.
 */
public interface EffectAction {
    /** When the effect starts on an entity (not when it stacks). */
    default void onApply(Context context) {}

    /** Every tick while the effect lasts; {@link Context#age} counts ticks since it started. */
    default void onTick(Context context) {}

    /** When the effect ends for any reason (expired, cured, removed, the entity died). */
    default void onRemove(Context context) {}

    /** What an action is working on. {@code level()} is the effect's current level (1 and up). */
    record Context(LivingEntity entity, ServerLevel serverLevel, EffectDefinition definition, ActiveEffect effect, long age) {
        public int level() {
            return this.effect.level(this.definition);
        }
    }
}
