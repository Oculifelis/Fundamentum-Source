package com.fundamentum.mobgoal.goal;

import com.fundamentum.mobgoal.GoalParams;
import com.fundamentum.port.FMobs;
import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * {@code fundamentum:leap}: jumps at the target from between {@code min} and {@code max} blocks away.
 * Params: min (2), max (6), cooldown (40 ticks), horizontal (0.5), vertical (0.4), chance (0.2 per check).
 */
public final class LeapGoal extends Goal {
    private final Mob mob;
    private final double min;
    private final double max;
    private final int cooldown;
    private final double horizontal;
    private final double vertical;
    private final float chance;
    private long next;

    public LeapGoal(Mob mob, GoalParams p) {
        this.mob = mob;
        this.min = p.d("min", 2);
        this.max = p.d("max", 6);
        this.cooldown = p.i("cooldown", 40);
        this.horizontal = p.d("horizontal", 0.5);
        this.vertical = p.d("vertical", 0.4);
        this.chance = p.f("chance", 0.2);
        setFlags(EnumSet.of(Flag.JUMP, Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = GoalUtil.target(mob);
        if (t == null || !mob.onGround() || mob.tickCount < next) return false;
        double d = mob.distanceToSqr(t);
        if (d < min * min || d > max * max) return false;
        return mob.getRandom().nextFloat() < chance;
    }

    @Override
    public boolean canContinueToUse() {
        return !mob.onGround();
    }

    @Override
    public void start() {
        LivingEntity t = mob.getTarget();
        next = mob.tickCount + cooldown;
        if (t == null) return;
        Vec3 dir = new Vec3(t.getX() - mob.getX(), 0, t.getZ() - mob.getZ());
        if (dir.lengthSqr() > 1.0E-7) dir = dir.normalize().scale(horizontal);
        mob.setDeltaMovement(mob.getDeltaMovement().scale(0.2).add(dir.x, vertical, dir.z));
        FMobs.pushed(mob);
    }
}
