package com.fundamentum.mobgoal.goal;

import com.fundamentum.mobgoal.GoalParams;
import com.fundamentum.mobgoal.MobGoals;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * {@code fundamentum:call_allies}: when the mob is hurt, allies nearby (by the faction hook; the same entity type by
 * default) that have no target turn on the attacker. Takes no movement, so it runs beside other goals.
 * Params: radius (16), max (8 allies).
 */
public final class CallAlliesGoal extends Goal {
    private final Mob mob;
    private final double radius;
    private final int max;
    private int seen;
    private LivingEntity seenAttacker;
    private int called;

    public CallAlliesGoal(Mob mob, GoalParams p) {
        this.mob = mob;
        this.radius = p.d("radius", 16);
        this.max = Math.max(1, p.i("max", 8));
        this.seen = mob.getLastHurtByMobTimestamp();
    }

    @Override
    public boolean canUse() {
        LivingEntity attacker = mob.getLastHurtByMob();
        return (mob.getLastHurtByMobTimestamp() != seen || attacker != seenAttacker) && GoalUtil.valid(attacker);
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        seen = mob.getLastHurtByMobTimestamp();
        LivingEntity attacker = mob.getLastHurtByMob();
        seenAttacker = attacker;
        called = 0;
        if (!GoalUtil.valid(attacker)) return;
        for (Mob ally : mob.level().getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(radius),
            m -> m != mob && m.isAlive() && m.getTarget() == null && m != attacker && MobGoals.factions().allied(mob, m))) {
            if (called >= max) break;
            if (MobGoals.factions().allied(ally, attacker)) continue;
            ally.setTarget(attacker);
            called++;
        }
    }

    /** How many allies the last call reached. */
    public int lastCalled() {
        return called;
    }
}
