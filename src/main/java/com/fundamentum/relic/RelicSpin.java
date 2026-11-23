package com.fundamentum.relic;

/**
 * How a relic's figure turns (no game classes, so it is unit tested). Unpowered, it turns slowly, one turn every
 * {@code turnTicks}; given a redstone signal it eases round to face the block's facing and stops there (the bob goes
 * on). Neither change snaps: it carries its angle and its speed, speeding up and slowing down by at most {@link #ACCEL}
 * a tick, and when the signal ends it turns on from wherever it stands. Ticked on both sides; nothing is saved (a
 * reloaded relic picks its angle up from the world clock, as Ultima Ratio's always did).
 */
public final class RelicSpin {
    /** Ultima Ratio's turn: 900 ticks (45 seconds). */
    public static final float TURN_TICKS = 900F;
    /** The most the speed changes in one tick, degrees per tick per tick. */
    public static final float ACCEL = 0.05F;
    /** The fastest it turns when seeking its facing, degrees per tick. */
    public static final float SEEK_MAX = 3.0F;
    /** Closer than this (degrees) and no faster than {@link #ACCEL}, it settles on its facing. */
    public static final float SETTLE = 0.05F;

    private float angle;
    private float prev;
    private float speed;
    private boolean started;

    /** The angle at rest for a facing: {@code -facing.toYRot()} (Ultima Ratio's turn at time 0). */
    public static float rest(float facingYRot) {
        return -facingYRot;
    }

    /** {@code a} folded into (-180, 180]. */
    public static float wrap(float a) {
        float w = a % 360F;
        if (w > 180F) {
            w -= 360F;
        } else if (w <= -180F) {
            w += 360F;
        }
        return w;
    }

    private static float approach(float from, float to, float step) {
        return from < to ? Math.min(to, from + step) : Math.max(to, from - step);
    }

    /**
     * One tick. {@code facingYRot} is the block's facing ({@code Direction.toYRot()}), {@code gameTime} the world clock
     * (only read on the first tick), {@code turnTicks} one turn's length.
     */
    public void tick(boolean powered, float facingYRot, long gameTime, float turnTicks) {
        float target = rest(facingYRot);
        float cruise = 360F / turnTicks;
        if (!this.started) {
            this.started = true;
            this.angle = powered ? target : target + 360F * (gameTime % (long) turnTicks) / turnTicks;
            this.angle = wrap(this.angle);
            this.prev = this.angle;
            this.speed = powered ? 0F : cruise;
            return;
        }
        this.prev = this.angle;
        if (!powered) {
            this.speed = approach(this.speed, cruise, ACCEL);
        } else {
            float diff = wrap(target - this.angle);
            if (Math.abs(diff) < SETTLE && Math.abs(this.speed) <= ACCEL) {
                this.angle = this.angle + diff;
                this.speed = 0F;
                this.fold();
                return;
            }
            // the fastest speed from which it can still stop on the target: v^2 = 2 a d
            float want = Math.signum(diff) * Math.min(SEEK_MAX, (float) Math.sqrt(2 * ACCEL * Math.abs(diff)));
            this.speed = approach(this.speed, want, ACCEL);
        }
        this.angle += this.speed;
        this.fold();
    }

    /** Keeps the angle in (-180, 180], moving the last tick's angle with it so the drawn turn does not jump. */
    private void fold() {
        float w = wrap(this.angle);
        this.prev += w - this.angle;
        this.angle = w;
    }

    /** The drawn angle (degrees about y, as Ultima Ratio's {@code turn}) between the last tick and this one. */
    public float angle(float partial) {
        return this.prev + (this.angle - this.prev) * partial;
    }

    public float angle() {
        return this.angle;
    }

    public float speed() {
        return this.speed;
    }

    public boolean started() {
        return this.started;
    }

    /** Whether it stands still on its facing. */
    public boolean settled(float facingYRot) {
        return this.speed == 0F && Math.abs(wrap(rest(facingYRot) - this.angle)) < 1e-3F;
    }
}
