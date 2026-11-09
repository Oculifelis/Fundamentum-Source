package com.fundamentum.scythe;

import com.fundamentum.whip.WhipVec;

/** The scythe moves' numbers, with no game types (unit tested; the same on every version). */
public final class ScytheMath {
    private ScytheMath() {}

    /** The sweet spot's damage multiplier for a hit {@code distance} blocks from the eye. */
    public static double sweetSpot(ScytheSpec.SweetSpot s, double distance) {
        if (distance >= s.min() && distance <= s.max() + 0.25) {
            return s.multiplier();
        }
        return distance < s.min() ? s.inside() : 1.0;
    }

    /** The whirl's damage multiplier after {@code ticks} held: from min to max over its build time. */
    public static double whirl(ScytheSpec.Whirl w, int ticks) {
        double u = Math.max(0, Math.min(1, (double) ticks / w.buildTicks()));
        return w.minDamage() + (w.maxDamage() - w.minDamage()) * u;
    }

    /** True when the whirl hits on this tick held. */
    public static boolean whirlHits(ScytheSpec.Whirl w, int ticks) {
        return ticks > 0 && ticks % w.hitEvery() == 0;
    }

    /**
     * True when {@code target} is within {@code range} of {@code eye} and inside the {@code arcDeg} wide arc around the flat
     * look direction (an arc of 360 takes everything in range).
     */
    public static boolean inArc(WhipVec eye, WhipVec look, WhipVec target, double range, double arcDeg) {
        WhipVec d = target.sub(eye);
        if (d.lengthSqr() > range * range) {
            return false;
        }
        if (arcDeg >= 360) {
            return true;
        }
        WhipVec flatD = new WhipVec(d.x(), 0, d.z());
        WhipVec flatL = new WhipVec(look.x(), 0, look.z());
        if (flatD.lengthSqr() < 1.0E-6) {
            return true;
        }
        if (flatL.lengthSqr() < 1.0E-6) {
            return false;
        }
        double cos = flatD.normalize(WhipVec.ZERO).dot(flatL.normalize(WhipVec.ZERO));
        return cos >= Math.cos(Math.toRadians(arcDeg * 0.5));
    }

    /**
     * The speed a reap pull gives a target at {@code target}, toward the puller at {@code puller}: {@code pull} blocks a
     * tick from far out, less closer in so nothing is flung past, with a small lift so it clears the ground.
     */
    public static WhipVec pull(WhipVec puller, WhipVec target, double pull, double range) {
        WhipVec d = puller.sub(target);
        double flatLen = Math.sqrt(d.x() * d.x() + d.z() * d.z());
        if (flatLen < 1.0E-6) {
            return WhipVec.ZERO;
        }
        double k = pull * Math.min(1.0, Math.max(0.0, (flatLen - 1.0) / Math.max(1.0, range * 0.5)));
        WhipVec flat = new WhipVec(d.x() / flatLen, 0, d.z() / flatLen);
        return flat.scale(k).add(new WhipVec(0, 0.1 + 0.15 * k, 0));
    }

    /** Where a crescent wave's centre is after {@code tick} ticks, or null once it has run its range. */
    public static WhipVec wave(ScytheSpec.CrescentWave w, WhipVec start, WhipVec dir, int tick) {
        double travelled = w.speed() * tick;
        if (travelled > w.range()) {
            return null;
        }
        return start.addScaled(dir.normalize(new WhipVec(0, 0, 1)), travelled);
    }

    /** The damage multiplier held souls give (1 with none). */
    public static double souls(ScytheSpec.HarvestSouls h, int souls) {
        return 1.0 + h.empowerPerSoul() * Math.max(0, Math.min(h.maxSouls(), souls));
    }
}
