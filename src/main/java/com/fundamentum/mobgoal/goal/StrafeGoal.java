package com.fundamentum.mobgoal.goal;

import com.fundamentum.mobgoal.GoalParams;
import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * {@code fundamentum:strafe}: circles the target at about {@code radius}, switching direction now and then.
 * Params: radius (6), speed (0.6, strafe strength), switch_ticks (60).
 */
public final class StrafeGoal extends Goal {
    private final Mob mob;
    private final double radius;
    private final float speed;
    private final int switchTicks;
    private int dir = 1;
    private int time;

    public StrafeGoal(Mob mob, GoalParams p) {
        this.mob = mob;
        this.radius = p.d("radius", 6);
        this.speed = p.f("speed", 0.6);
        this.switchTicks = Math.max(1, p.i("switch_ticks", 60));
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = GoalUtil.target(mob);
        double r = radius * 1.5;
        return t != null && mob.distanceToSqr(t) <= r * r && mob.getSensing().hasLineOfSight(t);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        mob.getNavigation().stop();
        dir = mob.getRandom().nextBoolean() ? 1 : -1;
        time = 0;
    }

    @Override
    public void tick() {
        LivingEntity t = GoalUtil.target(mob);
        if (t == null) return;
        if (++time % switchTicks == 0 && mob.getRandom().nextFloat() < 0.5f) dir = -dir;
        double d = Math.sqrt(mob.distanceToSqr(t));
        float forward = d > radius + 1 ? speed : d < radius - 1 ? -speed : 0;
        mob.getMoveControl().strafe(forward, speed * dir);
        mob.lookAt(t, 30, 30);
        mob.getLookControl().setLookAt(t, 30, 30);
    }

    /** The current sideways direction, 1 or -1. */
    public int direction() {
        return dir;
    }

    @Override
    public void stop() {
        mob.getMoveControl().strafe(0, 0);
    }
}
