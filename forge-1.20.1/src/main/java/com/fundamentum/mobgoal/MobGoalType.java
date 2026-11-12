package com.fundamentum.mobgoal;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Makes a goal for one mob from a data entry's params. Return null when the goal can't work on that mob (a goal
 * that paths needs a pathfinding mob, for example); the entry is then skipped for it. {@code target} goals go into
 * the mob's target selector instead of its goal selector.
 */
public record MobGoalType(boolean target, Factory factory) {
    @FunctionalInterface
    public interface Factory {
        Goal create(Mob mob, GoalParams params);
    }
}
