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
 * One simulated cord: a chain of {@code segments + 1} points, Verlet integrated with distance and bending constraints
 * (after Better Whips' leather whip, cut down from its per-whip 2,000-line solvers to one shared one). The first link is
 * the handle, held rigid along the swing direction. During a stroke a guide pulls each point toward a lagged copy of
 * the swing (each link follows the one before it a little late, Better Whips' "trainer" guide), which is what throws
 * the tip into its crack; as the guide lets go the cord flies on its own momentum and falls.
 *
 * <p>{@link #before} holds the points at the start of the last step, so hits are tested along the swept segments
 * (before to after), not with a ray. Given {@link Solids}, the cord lies on and slides along blocks instead of passing
 * through them (Better Whips' block collision, cut down to points against block boxes).
 */
public final class WhipCord {
    /** Fastest a point may move, in blocks per second, so a bad frame cannot fling the cord across the world. */
    static final double MAX_SPEED = 160.0;
    private static final int ITERATIONS = 4;
    /** Links may stretch this much past their length before they are pulled back hard. */
    private static final double MAX_STRETCH = 1.02;
    /** How far a point keeps off a block face, in blocks. */
    static final double SKIN = 0.04;
    /** Speed along a block face kept per step of contact. */
    private static final double FRICTION = 0.7;

    /**
     * What blocks the cord meets: the collision box (min x, y, z, max x, y, z, in world space) of the block at x, y, z,
     * or null for none. A block with an odd shape gives its shape's bounds.
     */
    public interface Solids {
        double[] box(int x, int y, int z);
    }

    public final int segments;
    public final double rest;
    /** Points now, handle first. */
    public final WhipVec[] points;
    /** Points at the start of the last step. */
    public final WhipVec[] before;
    private final WhipVec[] previous;
    /** The guide's lagged link directions. */
    private final WhipVec[] dirs;
    /** How far apart the samples are when looking for a block in a point's move (see {@link #stopAtBlocks}). */
    static final double SWEEP_STEP = 0.2;
    /**
     * The guide for a loose cord (one not being swung), per step at four steps a tick: just enough memory of its
     * shape that it hangs in a curve from the handle and lies along the ground instead of piling up under the hand.
     */
    public static final double IDLE_GUIDE = 0.04;
    /**
     * How fast the tip must go into a block, as a fraction of the whip's crack speed, to strike it (a sound, sparks and
     * {@link WhipHooks#struckBlock}) rather than just touch it.
     */
    public static final double STRIKE_SPEED = 0.5;
    /**
     * The block the tip was pushed out of in the last {@link #step} as {x, y, z}, or null if it touched none, and how
     * far it had gone into it, in blocks: over the step's dt, how hard the tip struck it.
     */
    public int[] tipBlock;
    public double tipImpact;

    public WhipCord(int segments, double length) {
        this.segments = segments;
        this.rest = length / segments;
        points = new WhipVec[segments + 1];
        before = new WhipVec[segments + 1];
        previous = new WhipVec[segments + 1];
        dirs = new WhipVec[segments];
    }

    /** Lays the cord out straight from {@code root} along the unit {@code dir}, at rest. */
    public void layOut(WhipVec root, WhipVec dir) {
        layOut(root, dir, null);
    }

    /**
     * As {@link #layOut(WhipVec, WhipVec)}, but where the line would enter a block the rest of the cord hangs straight
     * down from the last free point instead (piled there if that is blocked too), so it never starts inside a wall.
     */
    public void layOut(WhipVec root, WhipVec dir, Solids solids) {
        WhipVec step = dir.scale(rest);
        for (int i = 0; i <= segments; i++) {
            WhipVec p = i == 0 ? root : points[i - 1].add(step);
            if (i >= 2 && solids != null && inside(solids, p)) {
                step = new WhipVec(0, -rest, 0);
                p = points[i - 1].add(step);
                if (inside(solids, p)) {
                    p = points[i - 1];
                }
            }
            points[i] = p;
            before[i] = p;
            previous[i] = p;
        }
        for (int i = 0; i < segments; i++) {
            dirs[i] = dir;
        }
    }

    /** Moves the whole cord (a teleport, or a root that jumped too far), keeping its shape and speed. */
    public void shift(WhipVec by) {
        for (int i = 0; i <= segments; i++) {
            points[i] = points[i].add(by);
            before[i] = before[i].add(by);
            previous[i] = previous[i].add(by);
        }
    }

    /**
     * One step of {@code dt} seconds with the handle at {@code root} pointing along the unit {@code dir}.
     *
     * @param guide     0 to 1 for this step (already scaled for the step length): how far each point is pulled
     *                  toward the lagged swing shape
     * @param retention velocity kept this step
     * @param solids    the blocks, or null to pass through everything
     */
    public void step(WhipVec root, WhipVec dir, double dt, double guide, double lag, double stiffness, double retention,
                     double gravity, Solids solids) {
        System.arraycopy(points, 0, before, 0, points.length);
        tipBlock = null;
        tipImpact = 0;
        double maxStep = MAX_SPEED * dt;
        WhipVec fall = new WhipVec(0, -gravity * dt * dt, 0);
        // Verlet: carry on at the last step's speed, plus gravity.
        for (int i = 1; i <= segments; i++) {
            WhipVec v = points[i].sub(previous[i]).scale(retention);
            double speed = v.length();
            if (speed > maxStep) {
                v = v.scale(maxStep / speed);
            }
            previous[i] = points[i];
            points[i] = points[i].add(v).add(fall);
        }
        points[0] = root;
        previous[0] = root;
        // The guide: each link's direction trails the one before it; pull the points toward that trailing shape.
        double blend = 1.0 - Math.exp(-dt / (lag / segments));
        for (int i = segments - 1; i >= 1; i--) {
            dirs[i] = dirs[i].lerp(dirs[i - 1], blend).normalize(dirs[i - 1]);
        }
        dirs[0] = dir;
        if (guide > 0) {
            WhipVec q = root;
            for (int i = 1; i <= segments; i++) {
                q = q.addScaled(dirs[i - 1], rest);
                points[i] = points[i].lerp(q, guide);
            }
        }
        if (solids != null) {
            stopAtBlocks(solids);
        }
        // Constraints: the handle is rigid, links keep their length, and the cord resists folding.
        double minSkip = 2.0 * rest * (0.1 + 0.9 * stiffness);
        for (int it = 0; it < ITERATIONS; it++) {
            points[0] = root;
            points[1] = root.addScaled(dir, rest);
            for (int i = 1; i < segments; i++) {
                link(i, i + 1, rest, i == 1 ? 0.0 : 0.5);
            }
            if (stiffness > 0) {
                for (int i = 0; i + 2 <= segments; i++) {
                    WhipVec d = points[i + 2].sub(points[i]);
                    double len = d.length();
                    if (len < minSkip && len > 1.0E-9) {
                        double push = (minSkip - len) * stiffness * 0.5;
                        WhipVec n = d.scale(1.0 / len);
                        if (i >= 2) {
                            points[i] = points[i].addScaled(n, -push);
                            points[i + 2] = points[i + 2].addScaled(n, push);
                        } else {
                            points[i + 2] = points[i + 2].addScaled(n, push * 2);
                        }
                    }
                }
            }
            if (solids != null) {
                collide(solids);
            }
        }
        // Never longer than the cord, whatever the iterations left.
        for (int i = 1; i <= segments; i++) {
            WhipVec d = points[i].sub(points[i - 1]);
            double len = d.length();
            if (len > rest * MAX_STRETCH) {
                points[i] = points[i - 1].addScaled(d, rest * MAX_STRETCH / len);
            }
            if (!points[i].finite()) {
                points[i] = points[i - 1].addScaled(dir, rest);
                previous[i] = points[i];
            }
        }
        if (solids != null) {
            stopAtBlocks(solids);
            collide(solids);
        }
    }

    /**
     * Nothing goes through a block in one step: each point whose move since the step began enters a block stops just
     * before it, with no speed left. The push-out only sees where a point is, so a fast tip (or the guide's pull) could
     * otherwise jump a whole block.
     */
    private void stopAtBlocks(Solids solids) {
        for (int i = 2; i <= segments; i++) {
            WhipVec a = before[i], b = points[i];
            if (inside(solids, a)) {
                continue;
            }
            int n = (int) Math.ceil(a.distanceTo(b) / SWEEP_STEP);
            for (int k = 1; k <= n; k++) {
                if (inside(solids, a.lerp(b, (double) k / n))) {
                    double lo = (k - 1.0) / n, hi = (double) k / n;
                    for (int j = 0; j < 5; j++) {
                        double mid = (lo + hi) * 0.5;
                        if (inside(solids, a.lerp(b, mid))) {
                            hi = mid;
                        } else {
                            lo = mid;
                        }
                    }
                    points[i] = a.lerp(b, lo);
                    previous[i] = points[i];
                    if (i == segments) {
                        double into = a.distanceTo(b);
                        if (tipBlock == null || into > tipImpact) {
                            WhipVec q = a.lerp(b, hi);
                            tipBlock = new int[] {floor(q.x()), floor(q.y()), floor(q.z())};
                            tipImpact = into;
                        }
                    }
                    break;
                }
            }
        }
    }

    /**
     * Pushes every point past the handle out of the blocks it is in, by the shortest way, and takes away its speed into
     * the face and some of its speed along it.
     */
    private void collide(Solids solids) {
        for (int i = 2; i <= segments; i++) {
            WhipVec p = points[i];
            for (int x = floor(p.x() - SKIN); x <= floor(p.x() + SKIN); x++) {
                for (int y = floor(p.y() - SKIN); y <= floor(p.y() + SKIN); y++) {
                    for (int z = floor(p.z() - SKIN); z <= floor(p.z() + SKIN); z++) {
                        double[] b = solids.box(x, y, z);
                        if (b != null) {
                            p = pushOut(i, p, b, x, y, z);
                        }
                    }
                }
            }
            points[i] = p;
        }
    }

    private WhipVec pushOut(int i, WhipVec p, double[] b, int x, int y, int z) {
        double[] lo = {b[0] - SKIN, b[1] - SKIN, b[2] - SKIN}, hi = {b[3] + SKIN, b[4] + SKIN, b[5] + SKIN};
        double[] c = {p.x(), p.y(), p.z()};
        for (int a = 0; a < 3; a++) {
            if (c[a] <= lo[a] || c[a] >= hi[a]) {
                return p;
            }
        }
        // Out along the axis with the least way to go; prefer the side the point came from.
        double[] from = {previous[i].x(), previous[i].y(), previous[i].z()};
        int axis = -1;
        double best = Double.MAX_VALUE, to = 0;
        for (int a = 0; a < 3; a++) {
            double down = c[a] - lo[a], up = hi[a] - c[a];
            if (from[a] <= lo[a]) {
                up = Double.MAX_VALUE;
            } else if (from[a] >= hi[a]) {
                down = Double.MAX_VALUE;
            }
            if (down < best) {
                best = down;
                axis = a;
                to = lo[a];
            }
            if (up < best) {
                best = up;
                axis = a;
                to = hi[a];
            }
        }
        if (axis < 0) {
            return p;
        }
        if (i == segments) {
            double into = Math.abs(c[axis] - from[axis]);
            if (tipBlock == null || into > tipImpact) {
                tipBlock = new int[] {x, y, z};
                tipImpact = into;
            }
        }
        c[axis] = to;
        WhipVec out = new WhipVec(c[0], c[1], c[2]);
        // Velocity is points - previous: none into the face, and friction along it.
        WhipVec v = out.sub(previous[i]);
        double[] vv = {v.x() * FRICTION, v.y() * FRICTION, v.z() * FRICTION};
        vv[axis] = 0;
        previous[i] = out.sub(new WhipVec(vv[0], vv[1], vv[2]));
        return out;
    }

    private static boolean inside(Solids solids, WhipVec p) {
        double[] b = solids.box(floor(p.x()), floor(p.y()), floor(p.z()));
        return b != null && p.x() > b[0] && p.x() < b[3] && p.y() > b[1] && p.y() < b[4] && p.z() > b[2] && p.z() < b[5];
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    /** Pulls points a and b to {@code length} apart; {@code wa} is a's share of the move (0 pins a). */
    private void link(int a, int b, double length, double wa) {
        WhipVec d = points[b].sub(points[a]);
        double len = d.length();
        if (len < 1.0E-9) {
            return;
        }
        WhipVec corr = d.scale((len - length) / len);
        points[a] = points[a].addScaled(corr, wa);
        points[b] = points[b].addScaled(corr, -(1.0 - wa));
    }

    public WhipVec tip() {
        return points[segments];
    }

    /** Speed of point {@code i} over the last step of {@code dt} seconds, in blocks per second. */
    public double speed(int i, double dt) {
        return points[i].distanceTo(before[i]) / dt;
    }

    /** Speed of the tip over the last step of {@code dt} seconds, in blocks per second. */
    public double tipSpeed(double dt) {
        return speed(segments, dt);
    }

    /** The box around every segment's sweep in the last step, grown by {@code grow}: min x, y, z, max x, y, z. */
    public double[] sweptBounds(double grow) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i <= segments; i++) {
            for (WhipVec p : new WhipVec[] {points[i], before[i]}) {
                b[0] = Math.min(b[0], p.x()); b[1] = Math.min(b[1], p.y()); b[2] = Math.min(b[2], p.z());
                b[3] = Math.max(b[3], p.x()); b[4] = Math.max(b[4], p.y()); b[5] = Math.max(b[5], p.z());
            }
        }
        for (int i = 0; i < 3; i++) {
            b[i] -= grow;
            b[i + 3] += grow;
        }
        return b;
    }
}
