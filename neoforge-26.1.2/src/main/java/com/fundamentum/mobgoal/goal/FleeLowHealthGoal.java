package com.fundamentum.mobgoal.goal;

import com.fundamentum.mobgoal.GoalParams;
import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * {@code fundamentum:flee_low_health}: runs from whoever it is fighting (its target, or whoever last hurt it) once
 * its health falls below {@code health} (a share of max health). Params: health (0.25), speed (1.4), distance (16).
 */
public final class FleeLowHealthGoal extends Goal {
    private final PathfinderMob mob;
    private final float health;
    private final double speed;
    private final int distance;

    public FleeLowHealthGoal(PathfinderMob mob, GoalParams p) {
        this.mob = mob;
        this.health = p.f("health", 0.25);
        this.speed = p.d("speed", 1.4);
        this.distance = Math.max(4, p.i("distance", 16));
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /** True while the mob is below the health line. */
    public boolean low() {
        return mob.getHealth() < mob.getMaxHealth() * health;
    }

    private LivingEntity threat() {
        LivingEntity t = GoalUtil.target(mob);
        if (t != null) return t;
        LivingEntity h = mob.getLastHurtByMob();
        return GoalUtil.valid(h) ? h : null;
    }

    @Override
    public boolean canUse() {
        if (!low()) return false;
        LivingEntity t = threat();
        if (t == null) return false;
        Vec3 away = GoalUtil.awayFrom(mob, t.position(), distance, 7);
        return away != null && mob.getNavigation().moveTo(away.x, away.y, away.z, speed);
    }

    @Override
    public boolean canContinueToUse() {
        return low() && !mob.getNavigation().isDone();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }
}
