package com.fundamentum.spear;

/**
 * The numbers behind the spear attributes and throws, kept apart from the game so they are unit tested on their own.
 */
public final class SpearMath {
    /** Reach after {@code fundamentum:spear_reach}: the spear's own reach plus the bonus, kept between its minimum and 64. */
    public static float reach(float max, float min, double bonus) {
        return (float) Math.max(min, Math.min(64.0, max + bonus));
    }

    /** A charge hit after {@code fundamentum:spear_charge_damage}: the hit times the multiplier, never negative. */
    public static float charge(float damage, double multiplier) {
        return (float) Math.max(0.0, damage * multiplier);
    }

    /** Throw speed in blocks per tick for how long the spear was drawn back: full speed at {@code chargeTicks}. */
    public static float throwSpeed(int heldTicks, int chargeTicks, double velocity) {
        if (chargeTicks <= 0) {
            return (float) velocity;
        }
        return (float) (velocity * Math.min(1.0, heldTicks / (double) chargeTicks));
    }

    private SpearMath() {}
}
