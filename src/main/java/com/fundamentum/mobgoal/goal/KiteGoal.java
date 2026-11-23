package com.fundamentum.mobgoal.goal;

import com.fundamentum.mobgoal.GoalParams;
import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * {@code fundamentum:kite}: backs off when the target comes closer than {@code min} and stops once it is past
 * {@code max}. Params: min (6), max (10), speed (1.2).
 */
public final class KiteGoal extends Goal {
    private final PathfinderMob mob;
    private final double min;
    private final double max;
    private final double speed;

    public KiteGoal(PathfinderMob mob, GoalParams p) {
        this.mob = mob;
        this.min = p.d("min", 6);
        this.max = Math.max(min, p.d("max", 10));
        this.speed = p.d("speed", 1.2);
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = GoalUtil.target(mob);
        if (t == null || mob.distanceToSqr(t) >= min * min) return false;
        Vec3 away = GoalUtil.awayFrom(mob, t.position(), (int) Math.ceil(max) + 4, 7);
        if (away == null) return false;
        return mob.getNavigation().moveTo(away.x, away.y, away.z, speed);
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity t = GoalUtil.target(mob);
        return t != null && !mob.getNavigation().isDone() && mob.distanceToSqr(t) < max * max;
    }

    @Override
    public void tick() {
        LivingEntity t = GoalUtil.target(mob);
        if (t != null) mob.getLookControl().setLookAt(t, 30, 30);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }
}
