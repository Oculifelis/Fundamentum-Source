package com.fundamentum.mobgoal.goal;

import com.fundamentum.mobgoal.GoalParams;
import com.fundamentum.port.FMobs;
import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * {@code fundamentum:charge}: picks a line at the target and rushes along it, hitting the target once on contact.
 * Params: min (4), range (12), speed (0.6 blocks per tick), duration (30 ticks), cooldown (100 ticks).
 */
public final class ChargeGoal extends Goal {
    private final Mob mob;
    private final double min;
    private final double range;
    private final double speed;
    private final int duration;
    private final int cooldown;
    private Vec3 dir = Vec3.ZERO;
    private int time;
    private boolean hit;
    private long next;

    public ChargeGoal(Mob mob, GoalParams p) {
        this.mob = mob;
        this.min = p.d("min", 4);
        this.range = p.d("range", 12);
        this.speed = p.d("speed", 0.6);
        this.duration = Math.max(1, p.i("duration", 30));
        this.cooldown = p.i("cooldown", 100);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = GoalUtil.target(mob);
        if (t == null || mob.tickCount < next || !mob.onGround()) return false;
        double d = mob.distanceToSqr(t);
        return d >= min * min && d <= range * range && mob.getSensing().hasLineOfSight(t);
    }

    @Override
    public boolean canContinueToUse() {
        return !hit && time < duration && GoalUtil.target(mob) != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        LivingEntity t = mob.getTarget();
        time = 0;
        hit = false;
        mob.getNavigation().stop();
        Vec3 d = t == null ? Vec3.ZERO : new Vec3(t.getX() - mob.getX(), 0, t.getZ() - mob.getZ());
        dir = d.lengthSqr() > 1.0E-7 ? d.normalize() : Vec3.ZERO;
    }

    @Override
    public void tick() {
        time++;
        LivingEntity t = mob.getTarget();
        Vec3 v = mob.getDeltaMovement();
        mob.setDeltaMovement(dir.x * speed, v.y, dir.z * speed);
        FMobs.pushed(mob);
        if (t != null) {
            mob.getLookControl().setLookAt(t, 30, 30);
            if (mob.getBoundingBox().inflate(0.5).intersects(t.getBoundingBox())) {
                hit = true;
                FMobs.hurtTarget(mob, t);
            }
        }
    }

    /** True once this charge has landed its hit. */
    public boolean landed() {
        return hit;
    }

    @Override
    public void stop() {
        next = mob.tickCount + cooldown;
        Vec3 v = mob.getDeltaMovement();
        mob.setDeltaMovement(v.x * 0.2, v.y, v.z * 0.2);
    }
}
