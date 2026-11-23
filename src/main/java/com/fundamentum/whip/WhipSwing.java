/*
 * Adapted from Better Whips (https://github.com/my2167592261-cell/Better-Whips), whose code is under the MIT License:
 *
 * Copyright (c) 2026 my2167592261-cell
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.fundamentum.whip;

/**
 * One swing of a whip: where the handle points over time, and its strands' cords. The server runs it for hits and every
 * client runs the same one for drawing; both start from the same aim, side and tuning, so they agree up to where each
 * puts the hand (the client's is the drawn hand, the server's an estimate).
 *
 * <p>The handle turns in a plane through the aim, tilted {@code tilt} degrees off vertical toward this swing's side:
 * hanging forward and down, it is drawn back to {@code arc * 0.5} above the aim (windup, eased), then thrown forward through the
 * crosshair to {@code arc * 0.3} below it (stroke, accelerating like Better Whips' precision lash), then held while the
 * guide lets go and the cord flies out and falls (recover).
 */
public final class WhipSwing {
    /** Heard after each physics step of each strand. {@code tick} is the fractional tick into the swing. */
    public interface Listener {
        void step(int strand, WhipCord cord, double tick, double dt);
    }

    /** How far forward of the hand the handle sits, along its direction, in blocks. */
    static final double HANDLE_REACH = 0.3;
    private static final double TICK = 0.05;

    public final WhipTuning tuning;
    public final WhipCord[] cords;
    private final WhipVec[] forwards;
    private final WhipVec[] axes;
    /** How much of the tilt the handle keeps below the aim (the follow-through). */
    static final double DOWN_TILT = 0.5;

    /** Per strand: the axis the handle turns about below the aim, tilted {@link #DOWN_TILT} as much. */
    private final WhipVec[] downAxes;
    // Per strand: the tip's speed at the last step, whether it has turned forward yet, and whether it has cracked.
    private final double[] lastTipSpeed;
    private final boolean[] forward, cracked;
    private int age;

    /**
     * @param look the unit aim at the start of the swing
     * @param side +1 or -1: which way the swing plane leans (alternate it between swings)
     */
    public WhipSwing(WhipTuning tuning, WhipVec look, int side) {
        this(tuning, new WhipVec[] {look}, side);
    }

    /**
     * @param looks the unit aim of each strand (homing gives strands their own); strands past the end use the first
     * @param side +1 or -1: which way the swing plane leans (alternate it between swings)
     */
    public WhipSwing(WhipTuning tuning, WhipVec[] looks, int side) {
        this.tuning = tuning;
        cords = new WhipCord[tuning.strands()];
        axes = new WhipVec[tuning.strands()];
        downAxes = new WhipVec[tuning.strands()];
        forwards = new WhipVec[tuning.strands()];
        lastTipSpeed = new double[tuning.strands()];
        forward = new boolean[tuning.strands()];
        cracked = new boolean[tuning.strands()];
        for (int k = 0; k < cords.length; k++) {
            WhipVec forward = (k < looks.length ? looks[k] : looks[0]).normalize(new WhipVec(0, 0, 1));
            WhipVec right = forward.cross(WhipVec.UP).normalize(new WhipVec(1, 0, 0));
            double tilt = tuning.tilt() + (k - (cords.length - 1) / 2.0) * tuning.strandSpread();
            forwards[k] = forward;
            axes[k] = right.rotate(forward, Math.toRadians(tilt) * (side >= 0 ? 1 : -1));
            double downTilt = DOWN_TILT * tuning.tilt() + (k - (cords.length - 1) / 2.0) * tuning.strandSpread();
            downAxes[k] = right.rotate(forward, Math.toRadians(downTilt) * (side >= 0 ? 1 : -1));
            cords[k] = new WhipCord(tuning.segments(), tuning.length());
        }
    }

    /** Lays the cords out hanging from the hand. */
    public void start(WhipVec hand) {
        start(hand, null);
    }

    /** Lays the cords out hanging from the hand, folded back from the given blocks (null for none). */
    public void start(WhipVec hand, WhipCord.Solids solids) {
        for (int k = 0; k < cords.length; k++) {
            WhipVec dir = direction(k, 0);
            cords[k].layOut(hand.addScaled(dir, HANDLE_REACH), dir, solids);
        }
    }

    /**
     * Watches strand {@code k}'s tip; call it once after each of its steps (from the {@link Listener}). True at the step
     * its crack is heard: the first peak of tip speed at {@link WhipTuning#crackSpeed} or more once the tip flies
     * forward, out in front of the player at about hand height (or where it slams into a block, which is a peak too).
     *
     * <p>Until 1.22.3 the crack was the first step past crack speed, which was the backswing's whip-back: the tip
     * cracked overhead and behind the hand (3 blocks up), so crack effects went off in the air.
     */
    public boolean crack(int k, WhipCord cord, double tick, double dt) {
        double speed = cord.tipSpeed(dt), last = lastTipSpeed[k];
        lastTipSpeed[k] = speed;
        if (cracked[k] || !forward(k, cord, tick)) {
            return false;
        }
        int end = tuning.windupTicks() + tuning.strokeTicks() + tuning.hitTicksAfterStroke();
        if ((last >= tuning.crackSpeed() && speed < last) || (speed >= tuning.crackSpeed() && tick >= end)) {
            cracked[k] = true;
            return true;
        }
        return false;
    }

    /**
     * True once strand {@code k}'s tip, after the windup, has turned and is flying forward along the aim (it lags the
     * handle, so it is still whipping back for a tick or two after the stroke begins). Hits and crits count from then.
     */
    public boolean forward(int k, WhipCord cord, double tick) {
        if (!forward[k] && tick > tuning.windupTicks()) {
            int tip = cord.segments;
            forward[k] = cord.points[tip].sub(cord.before[tip]).dot(forwards[k]) > 0;
        }
        return forward[k];
    }

    /** Ticks done. */
    public int age() {
        return age;
    }

    public boolean done() {
        return age >= tuning.totalTicks();
    }

    /** The handle angle in the swing plane at {@code tick} ticks (radians, 0 at the crosshair, up positive). */
    public double angle(double tick) {
        double arc = Math.toRadians(tuning.arc());
        // A lower backswing and a follow-through below the rest angle put the crack out in front at about hand height;
        // from 0.88 back to the rest angle, the lagging cord cracked overhead (2.5 blocks up, 2 out).
        double rest = -0.12 * arc, back = 0.5 * arc, end = -0.3 * arc;
        int w = tuning.windupTicks(), s = tuning.strokeTicks();
        if (tick <= w) {
            double t = w == 0 ? 1 : tick / w;
            return rest + (back - rest) * smooth(t);
        }
        if (tick <= w + s) {
            double t = (tick - w) / s;
            return back + (end - back) * Math.pow(t, 1.6);
        }
        return end;
    }

    /** The guide strength at {@code tick}: full until the stroke ends, then easing to nothing over the recovery. */
    public double guide(double tick) {
        int end = tuning.windupTicks() + tuning.strokeTicks();
        if (tick <= end) {
            return tuning.guide();
        }
        double t = tuning.recoverTicks() == 0 ? 1 : (tick - end) / Math.min(tuning.recoverTicks(), 4.0);
        return tuning.guide() * Math.max(0, 1 - t);
    }

    /**
     * The handle direction of strand {@code k} at {@code tick}. Above the aim the handle turns in the tilted plane (the
     * swing comes over the shoulder); below it, in a plane tilted half as much, so the follow-through lays the cord
     * near the line to the crosshair and still sweeps a little to the side. Before 1.22.3 it stayed fully tilted below
     * the aim, which carried the low cord half a block or more aside: a short mob dead ahead was missed on every other
     * swing. Straight down (no tilt) missed mobs a little off to the side instead.
     */
    public WhipVec direction(int k, double tick) {
        double a = angle(tick);
        return forwards[k].rotate(a >= 0 ? axes[k] : downAxes[k], a);
    }

    /**
     * Runs one tick of {@code tuning.substeps()} steps, the hand moving from {@code handFrom} to {@code handTo}, through
     * every block. Returns false once the swing is over.
     */
    public boolean tick(WhipVec handFrom, WhipVec handTo, Listener listener) {
        return tick(handFrom, handTo, null, listener);
    }

    /** As {@link #tick(WhipVec, WhipVec, Listener)}, with the cords lying on the given blocks (null for none). */
    public boolean tick(WhipVec handFrom, WhipVec handTo, WhipCord.Solids solids, Listener listener) {
        if (done()) {
            return false;
        }
        int n = tuning.substeps();
        double dt = TICK / n;
        double retention = Math.pow(tuning.retention(), 1.0 / n);
        for (int k = 0; k < cords.length; k++) {
            // A hand that jumped (a teleport) takes the cord along instead of stretching it.
            WhipVec root0 = handFrom.addScaled(direction(k, age), HANDLE_REACH);
            if (cords[k].points[0].distanceTo(root0) > 3.0) {
                cords[k].shift(root0.sub(cords[k].points[0]));
            }
        }
        for (int i = 1; i <= n; i++) {
            double tick = age + (double) i / n;
            WhipVec hand = handFrom.lerp(handTo, (double) i / n);
            double guide = 1.0 - Math.pow(1.0 - guide(tick), 1.0 / n);
            for (int k = 0; k < cords.length; k++) {
                WhipVec dir = direction(k, tick);
                cords[k].step(hand.addScaled(dir, HANDLE_REACH), dir, dt, guide, tuning.lag(), tuning.stiffness(), retention,
                    tuning.gravity(), solids);
                listener.step(k, cords[k], tick, dt);
            }
        }
        age++;
        return !done();
    }

    private static double smooth(double t) {
        t = WhipVec.clamp(t, 0.0, 1.0);
        return t * t * (3 - 2 * t);
    }
}
