package com.fundamentum.mobgoal.goal;

import com.fundamentum.mobgoal.GoalParams;
import com.fundamentum.mobgoal.MobGoals;
import java.util.EnumSet;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * {@code fundamentum:follow_leader}: packs up behind a leader while there is nothing to fight. The leader is the
 * allied mob nearby (the faction hook; the same entity type by default) that has been in the world longest, so a
 * pack settles on one. The leader itself just goes about its business. Params: min (3), max (8), range (16),
 * speed (1.0).
 */
public final class FollowLeaderGoal extends Goal {
    private final PathfinderMob mob;
    private final double min;
    private final double max;
    private final double range;
    private final double speed;
    private Mob leader;
    private int recheck;

    public FollowLeaderGoal(PathfinderMob mob, GoalParams p) {
        this.mob = mob;
        this.min = p.d("min", 3);
        this.max = Math.max(min, p.d("max", 8));
        this.range = Math.max(max, p.d("range", 16));
        this.speed = p.d("speed", 1.0);
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /** The leader this mob would follow now, or null when it leads (or is alone). */
    public Mob findLeader() {
        Mob best = mob;
        for (Mob m : mob.level().getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(range),
            m -> m != mob && m.isAlive() && MobGoals.factions().allied(mob, m))) {
            if (m.getId() < best.getId()) best = m;
        }
        return best == mob ? null : best;
    }

    @Override
    public boolean canUse() {
        if (mob.getTarget() != null) return false;
        if (--recheck > 0 && leader == null) return false;
        recheck = 10;
        leader = findLeader();
        return leader != null && mob.distanceToSqr(leader) > max * max;
    }

    @Override
    public boolean canContinueToUse() {
        return leader != null && leader.isAlive() && mob.getTarget() == null && mob.distanceToSqr(leader) > min * min
            && mob.distanceToSqr(leader) < range * range * 4;
    }

    @Override
    public void tick() {
        if (leader == null) return;
        mob.getLookControl().setLookAt(leader, 10, mob.getMaxHeadXRot());
        if (mob.tickCount % 10 == 0) mob.getNavigation().moveTo(leader, speed);
    }

    @Override
    public void start() {
        if (leader != null) mob.getNavigation().moveTo(leader, speed);
    }

    @Override
    public void stop() {
        leader = null;
        mob.getNavigation().stop();
    }
}
