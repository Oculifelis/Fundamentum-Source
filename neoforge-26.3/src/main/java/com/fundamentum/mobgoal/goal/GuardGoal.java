package com.fundamentum.mobgoal.goal;

import com.fundamentum.mobgoal.GoalParams;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * {@code fundamentum:guard}: keeps the mob near a point. It walks back when it strays past {@code radius} with no
 * target, and drops a target that leads it more than {@code leash} from the point. The point is {@code pos} when
 * given, else where the mob stood when the goal was added. Params: pos ([x, y, z]), radius (8), leash (radius x 2),
 * speed (1.0).
 */
public final class GuardGoal extends Goal {
    private final PathfinderMob mob;
    private final BlockPos point;
    private final double radius;
    private final double leash;
    private final double speed;

    public GuardGoal(PathfinderMob mob, GoalParams p) {
        this.mob = mob;
        BlockPos pos = p.pos("pos");
        this.point = pos != null ? pos : mob.blockPosition();
        this.radius = p.d("radius", 8);
        this.leash = p.d("leash", radius * 2);
        this.speed = p.d("speed", 1.0);
        setFlags(EnumSet.of(Flag.MOVE));
    }

    public BlockPos point() {
        return point;
    }

    private double distSqr() {
        return mob.distanceToSqr(point.getX() + 0.5, point.getY(), point.getZ() + 0.5);
    }

    @Override
    public boolean canUse() {
        LivingEntity t = mob.getTarget();
        if (t != null && t.distanceToSqr(point.getX() + 0.5, point.getY(), point.getZ() + 0.5) > leash * leash) {
            mob.setTarget(null);
            t = null;
        }
        if (t != null || distSqr() <= radius * radius) return false;
        return mob.getNavigation().moveTo(point.getX() + 0.5, point.getY(), point.getZ() + 0.5, speed);
    }

    @Override
    public boolean canContinueToUse() {
        return mob.getTarget() == null && !mob.getNavigation().isDone() && distSqr() > 4;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }
}
