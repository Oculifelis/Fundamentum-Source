package com.fundamentum.mobgoal.goal;

import com.fundamentum.mobgoal.GoalParams;
import com.fundamentum.mobgoal.MobGoals;
import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * {@code fundamentum:faction_target} (a target goal): picks the nearest living thing the faction hook calls hostile.
 * Fundamentum's default hook calls nothing hostile, so this does nothing until a faction mod sets one.
 * Params: range (16), interval (10 ticks between scans), sight (true: needs line of sight).
 */
public final class FactionTargetGoal extends Goal {
    private final Mob mob;
    private final double range;
    private final int interval;
    private final boolean sight;
    private LivingEntity found;
    private int wait;

    public FactionTargetGoal(Mob mob, GoalParams p) {
        this.mob = mob;
        this.range = p.d("range", 16);
        this.interval = Math.max(1, p.i("interval", 10));
        this.sight = p.b("sight", true);
        setFlags(EnumSet.of(Flag.TARGET));
    }

    /** The nearest hostile in range now, or null. */
    public LivingEntity scan() {
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(range),
            e -> e != mob && GoalUtil.valid(e) && MobGoals.factions().hostile(mob, e))) {
            if (sight && !mob.getSensing().hasLineOfSight(e)) continue;
            double d = mob.distanceToSqr(e);
            if (d < bestD && d <= range * range) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    @Override
    public boolean canUse() {
        if (--wait > 0) return false;
        wait = interval;
        found = scan();
        return found != null;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity t = mob.getTarget();
        return GoalUtil.valid(t) && mob.distanceToSqr(t) <= range * range * 2.25 && MobGoals.factions().hostile(mob, t);
    }

    @Override
    public void start() {
        mob.setTarget(found);
    }

    @Override
    public void stop() {
        if (mob.getTarget() == found) mob.setTarget(null);
        found = null;
    }
}
