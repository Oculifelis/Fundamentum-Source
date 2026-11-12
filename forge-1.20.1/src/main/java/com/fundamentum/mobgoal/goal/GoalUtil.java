package com.fundamentum.mobgoal.goal;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Small shared pieces of the built-in mob goals. */
final class GoalUtil {
    private GoalUtil() { }

    /** The mob's target when it is alive and attackable, else null. */
    static LivingEntity target(Mob mob) {
        LivingEntity t = mob.getTarget();
        return valid(t) ? t : null;
    }

    static boolean valid(LivingEntity t) {
        if (t == null || !t.isAlive() || t.isRemoved()) return false;
        return !(t instanceof Player p) || !(p.isCreative() || p.isSpectator());
    }

    /** A spot away from {@code from} that is farther from it than the mob is now, or null. */
    static Vec3 awayFrom(PathfinderMob mob, Vec3 from, int range, int height) {
        for (int i = 0; i < 4; i++) {
            Vec3 p = DefaultRandomPos.getPosAway(mob, range, height, from);
            if (p != null && p.distanceToSqr(from) > mob.position().distanceToSqr(from)) return p;
        }
        return null;
    }
}
