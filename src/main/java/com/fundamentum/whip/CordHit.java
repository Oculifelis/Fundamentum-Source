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

import org.jetbrains.annotations.Nullable;

/**
 * Hit tests along a moving cord (after Better Whips' swept segment colliders): each segment is swept from where it was
 * at the start of a step to where it is now, as a thick band, and tested against a box. A fast tip cannot skip a mob
 * between two steps, and the whole length of the cord hits, not just a ray from the eyes.
 */
public final class CordHit {
    /**
     * Where a cord touched a box.
     *
     * @param point    the touching point on the cord
     * @param along    0 (handle) to 1 (tip) along the whole cord
     * @param segment  the segment that touched
     * @param distance from the cord's centre line to the box (0 inside it)
     */
    public record Contact(WhipVec point, double along, int segment, double distance) {}

    /** The first (closest) touch between the cord's last step and the box, or null. */
    public static @Nullable Contact test(WhipCord cord, double radius, double[] box) {
        Contact best = null;
        for (int k = 0; k < cord.segments; k++) {
            Contact c = sweep(cord.before[k], cord.before[k + 1], cord.points[k], cord.points[k + 1], radius, box);
            if (c != null && (best == null || c.distance < best.distance)) {
                best = new Contact(c.point, (k + c.along) / cord.segments, k, c.distance);
                if (c.distance <= 0) {
                    // Inside: prefer the outermost segment that is inside, which is the one that did the hitting.
                    for (int j = cord.segments - 1; j > k; j--) {
                        Contact o = sweep(cord.before[j], cord.before[j + 1], cord.points[j], cord.points[j + 1], radius, box);
                        if (o != null && o.distance <= 0) {
                            return new Contact(o.point, (j + o.along) / cord.segments, j, 0);
                        }
                    }
                    return best;
                }
            }
        }
        return best;
    }

    /**
     * Sweeps the segment a0-b0 to a1-b1 against the box (min x, y, z, max x, y, z). Returns the closest sample within
     * {@code radius}, with {@code along} as the fraction along this segment, or null.
     */
    public static @Nullable Contact sweep(WhipVec a0, WhipVec b0, WhipVec a1, WhipVec b1, double radius, double[] box) {
        // Samples no further apart than the radius plus half the box's thinnest side, so a sample always lands within
        // the radius of a box the band passes through, however far the segment moved.
        double thin = Math.min(box[3] - box[0], Math.min(box[4] - box[1], box[5] - box[2]));
        double step = Math.max(radius, 0.02) + 0.5 * Math.max(thin, 0);
        double move = Math.max(a0.distanceTo(a1), b0.distanceTo(b1));
        int times = (int) Math.min(64, Math.ceil(move / step));
        int spots = (int) Math.min(64, Math.ceil(Math.max(a1.distanceTo(b1), a0.distanceTo(b0)) / step));
        Contact best = null;
        for (int ti = 0; ti <= times; ti++) {
            double t = (double) ti / times;
            WhipVec a = a0.lerp(a1, t), b = b0.lerp(b1, t);
            for (int si = 0; si <= spots; si++) {
                double s = (double) si / spots;
                WhipVec p = a.lerp(b, s);
                double d = distance(p, box);
                if (d <= radius && (best == null || d < best.distance)) {
                    best = new Contact(p, s, 0, d);
                    if (d <= 0) {
                        return best;
                    }
                }
            }
        }
        return best;
    }

    /** Distance from a point to a box (0 inside). */
    public static double distance(WhipVec p, double[] box) {
        double dx = Math.max(Math.max(box[0] - p.x(), 0), p.x() - box[3]);
        double dy = Math.max(Math.max(box[1] - p.y(), 0), p.y() - box[4]);
        double dz = Math.max(Math.max(box[2] - p.z(), 0), p.z() - box[5]);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Better Whips' aim assist test: true when {@code contact} is within {@code degrees} of where the eyes look. The
     * swinging cord hits anything near the crosshair; elsewhere a whip's {@link WhipTargets.Rule} decides (by default
     * hostiles only).
     */
    public static boolean nearCrosshair(WhipVec eye, WhipVec look, WhipVec contact, double degrees) {
        WhipVec to = contact.sub(eye);
        double l = to.length() * look.length();
        if (l < 1.0E-8) {
            return true;
        }
        return to.dot(look) / l >= Math.cos(Math.toRadians(degrees));
    }

    private CordHit() {}
}
