package com.fundamentum.mobgoal;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Who a mob counts as friend or foe, for {@code fundamentum:faction_target}, {@code call_allies} and
 * {@code follow_leader}. Fundamentum has no factions of its own; a faction mod sets one with
 * {@link MobGoals#setFactions}. The default treats nobody as hostile and only the same entity type as allied.
 */
public interface FactionHook {
    FactionHook DEFAULT = new FactionHook() { };

    /** True when {@code self} should pick {@code other} as a target. */
    default boolean hostile(Mob self, LivingEntity other) {
        return false;
    }

    /** True when {@code other} is on {@code self}'s side (answers calls for help, can lead a pack). */
    default boolean allied(Mob self, LivingEntity other) {
        return other != self && other.getType() == self.getType();
    }
}
