package com.fundamentum.client;

/**
 * The cloth chain behind {@link Cloth} (1.25.3): a cape split into a few segments hanging one under the other, each
 * springing toward the angle the one above it had a tick ago, so the lower ones lag, trail and flutter. Pure (no game
 * classes), so it is unit tested ({@code ClothSimTest}).
 * <p>
 * Angles are degrees of pitch away from the body's back (0 hangs straight down along it, 90 streams straight out
 * behind), absolute per segment; {@link #relativePitch} gives what a child model part needs. The drive is vanilla's own
 * cape numbers ({@link Input}), so a veil sways exactly when a cape would, plus a fall lift, a glide stream, a crouch
 * floor and a slow idle drift.
 */
public final class ClothSim {
    /** One tick of drive, in vanilla's cape units (degrees) plus a few extras. */
    public record Input(
        /** vanilla capeLean: forward motion of the cloak behind the body, 0..150 */
        float lean,
        /** vanilla capeLean2: sideways, -20..20 */
        float lean2,
        /** vanilla capeFlap: vertical cloak lag plus the walking bob, about -6..64 */
        float flap,
        /** falling speed in blocks per tick (positive down, 0 standing or rising) */
        float fall,
        /** 0..1, how far into an elytra glide */
        float glide,
        boolean crouching,
        /** horizontal speed in blocks per tick */
        float speed,
        /** the entity's age in ticks, for the idle drift */
        float age) {
    }

    /**
     * Never closer to the back than this (no clipping into the body), and in a crouch never closer than {@link #CROUCH}.
     * Angles are relative to the body part, which already leans forward in a crouch (vanilla 26.1.2's cape is a child
     * of the body and adds nothing; 1.21.1's unattached cape adds 25 degrees to make up for the lean), so a chain
     * hung from the body clears the legs with a small floor.
     */
    public static final float MIN = 2.0F;
    public static final float CROUCH = 8.0F;
    public static final float MAX = 150.0F;

    public final int segments;
    private final float[] pitch;
    private final float[] pitchO;
    private final float[] speed;
    private final float[] roll;
    private final float[] rollO;
    private int lastTick = Integer.MIN_VALUE;
    private boolean fresh = true;

    public ClothSim(int segments) {
        this.segments = segments;
        this.pitch = new float[segments];
        this.pitchO = new float[segments];
        this.speed = new float[segments];
        this.roll = new float[segments];
        this.rollO = new float[segments];
    }

    /** Steps the chain up to the given game tick (once per tick however often it is drawn; at most 20 steps to catch up). */
    public void advanceTo(int tick, Input in) {
        if (fresh || tick < lastTick || tick - lastTick > 20) {
            settle(in);
            lastTick = tick;
            return;
        }
        while (lastTick < tick) {
            step(in);
            lastTick++;
        }
    }

    /** Starts at rest on this input (a new wearer, or one seen again after a while). */
    public void settle(Input in) {
        fresh = false;
        float base = base(in);
        for (int i = 0; i < segments; i++) {
            pitch[i] = pitchO[i] = base;
            speed[i] = 0.0F;
            roll[i] = rollO[i] = in.lean2() / 2.0F;
        }
    }

    /** The angle the top segment is pulled to: vanilla's cape pitch, lifted by a fall, streamed by a glide. */
    static float base(Input in) {
        float base = 6.0F + in.lean() / 2.0F + in.flap();
        if (in.fall() > 0.1F) {
            base += Math.min(50.0F, (in.fall() - 0.1F) * 70.0F);
        }
        base += (80.0F - base) * clamp(in.glide(), 0.0F, 1.0F);
        return clamp(base, floor(in), MAX);
    }

    static float floor(Input in) {
        return in.crouching() ? CROUCH : MIN;
    }

    /** One tick. */
    public void step(Input in) {
        System.arraycopy(pitch, 0, pitchO, 0, segments);
        System.arraycopy(roll, 0, rollO, 0, segments);
        float moving = clamp(in.speed() * 4.0F + in.fall() * 2.0F + in.glide(), 0.0F, 1.0F);
        float floor = floor(in);
        for (int i = 0; i < segments; i++) {
            float target;
            if (i == 0) {
                target = base(in);
            } else {
                // follow the segment above as it was a tick ago (the lag); standing still, hang a little nearer the back
                float above = pitchO[i - 1];
                float hang = 0.22F * (1.0F - moving);
                target = above + (floor - above) * hang;
            }
            // the idle drift: slow, a little stronger and later down the chain; wind (speed) adds a quicker ripple
            float phase = in.age() * 0.09F - i * 0.85F;
            target += (1.2F + 1.4F * i) * (float) Math.sin(phase) * (0.7F + 0.3F * (float) Math.sin(in.age() * 0.023F + i));
            target += moving * (0.8F + 1.2F * i) * (float) Math.sin(in.age() * 0.55F - i * 1.3F);
            float stiffness = Math.max(0.16F, 0.42F - 0.07F * i);
            speed[i] = speed[i] * 0.62F + (target - pitch[i]) * stiffness;
            pitch[i] += speed[i];
            if (i > 0) {
                // a bend between neighbours stays a gentle curve
                pitch[i] = clamp(pitch[i], pitch[i - 1] - 22.0F, pitch[i - 1] + 38.0F);
            }
            if (pitch[i] < floor) {
                pitch[i] = floor;
                speed[i] = Math.max(0.0F, speed[i]);
            }
            pitch[i] = Math.min(pitch[i], MAX);
            float rollTarget = (i == 0 ? in.lean2() / 2.0F : rollO[i - 1] * 1.08F)
                + (0.5F + 0.9F * i) * (float) Math.sin(in.age() * 0.13F + i * 1.7F) * (0.4F + moving);
            roll[i] += (rollTarget - roll[i]) * Math.max(0.2F, 0.5F - 0.08F * i);
            roll[i] = clamp(roll[i], -25.0F, 25.0F);
        }
    }

    /** Absolute pitch of a segment, degrees, between the last two ticks. */
    public float pitch(int i, float partialTick) {
        return pitchO[i] + (pitch[i] - pitchO[i]) * partialTick;
    }

    /** Pitch of a segment relative to the one above it (the top one: relative to the body), degrees. */
    public float relativePitch(int i, float partialTick) {
        return i == 0 ? pitch(0, partialTick) : pitch(i, partialTick) - pitch(i - 1, partialTick);
    }

    /** Sideways roll of a segment, degrees, absolute. */
    public float roll(int i, float partialTick) {
        return rollO[i] + (roll[i] - rollO[i]) * partialTick;
    }

    public float relativeRoll(int i, float partialTick) {
        return i == 0 ? roll(0, partialTick) : roll(i, partialTick) - roll(i - 1, partialTick);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
