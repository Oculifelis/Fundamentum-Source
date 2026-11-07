package com.fundamentum.curve;

import java.util.ArrayList;
import java.util.List;

/**
 * A smooth cubic Bezier between two ends, each with the direction the curve leaves it by: tubes, pipes, cables, rails,
 * ropes. Plain doubles and no Minecraft types, so it works on both sides and in unit tests.
 * <pre>
 * Curve c = Curve.between(fromFace, fromOut, toFace, toOut);   // toOut points out of the end the curve plugs into
 * if (c.length() <= 32 && c.minRadius() >= 1.5) { ... }        // what a placer checks
 * for (Curve.Frame f : c.frames(0.5)) { ... }                    // what a renderer or a traveller walks along
 * </pre>
 * The control points sit {@code tension} (default {@value #TENSION}) of the straight distance out along each end's
 * direction, so the curve leaves and enters straight and bends in between. Two ends at the same point make a curve of
 * length 0.
 */
public final class Curve {
    public static final double TENSION = 0.45;

    /** A point or direction. */
    public record Vec(double x, double y, double z) {
        public static final Vec ZERO = new Vec(0, 0, 0);

        public Vec add(Vec o) {
            return new Vec(x + o.x, y + o.y, z + o.z);
        }

        public Vec sub(Vec o) {
            return new Vec(x - o.x, y - o.y, z - o.z);
        }

        public Vec scale(double s) {
            return new Vec(x * s, y * s, z * s);
        }

        public double dot(Vec o) {
            return x * o.x + y * o.y + z * o.z;
        }

        public Vec cross(Vec o) {
            return new Vec(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
        }

        public double length() {
            return Math.sqrt(dot(this));
        }

        /** This direction at length 1 (zero stays zero). */
        public Vec normalize() {
            double l = length();
            return l < 1e-12 ? ZERO : scale(1 / l);
        }

        public Vec lerp(Vec o, double t) {
            return new Vec(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
        }
    }

    /**
     * A point on the curve with a twist-free frame (parallel transport): {@code tangent} along the curve, {@code normal}
     * and {@code binormal} across it, and the distance along the curve from its start.
     */
    public record Frame(Vec pos, Vec tangent, Vec normal, Vec binormal, double distance) {
    }

    private static final int DENSITY = 8;

    private final Vec p0;
    private final Vec p1;
    private final Vec p2;
    private final Vec p3;
    /** Dense samples by parameter, with the arc length at each (for even spacing and the length). */
    private final double[] ts;
    private final double[] arc;

    private Curve(Vec p0, Vec p1, Vec p2, Vec p3) {
        this.p0 = p0;
        this.p1 = p1;
        this.p2 = p2;
        this.p3 = p3;
        double chord = p0.sub(p3).length() + p0.sub(p1).length() + p2.sub(p3).length();
        int n = Math.max(16, (int) Math.ceil(chord * DENSITY));
        ts = new double[n + 1];
        arc = new double[n + 1];
        Vec prev = p0;
        for (int i = 0; i <= n; i++) {
            ts[i] = (double) i / n;
            Vec p = point(ts[i]);
            arc[i] = i == 0 ? 0 : arc[i - 1] + p.sub(prev).length();
            prev = p;
        }
    }

    /** The curve from {@code from}, leaving along {@code fromOut}, to {@code to}, arriving against {@code toOut}. */
    public static Curve between(Vec from, Vec fromOut, Vec to, Vec toOut) {
        return between(from, fromOut, to, toOut, TENSION);
    }

    public static Curve between(Vec from, Vec fromOut, Vec to, Vec toOut, double tension) {
        double k = from.sub(to).length() * tension;
        return new Curve(from, from.add(fromOut.normalize().scale(k)), to.add(toOut.normalize().scale(k)), to);
    }

    public Vec start() {
        return p0;
    }

    public Vec end() {
        return p3;
    }

    /** The point at parameter {@code t} (0 to 1; not evenly spaced along the curve). */
    public Vec point(double t) {
        double u = 1 - t;
        return p0.scale(u * u * u).add(p1.scale(3 * u * u * t)).add(p2.scale(3 * u * t * t)).add(p3.scale(t * t * t));
    }

    private Vec d1(double t) {
        double u = 1 - t;
        return p1.sub(p0).scale(3 * u * u).add(p2.sub(p1).scale(6 * u * t)).add(p3.sub(p2).scale(3 * t * t));
    }

    private Vec d2(double t) {
        double u = 1 - t;
        return p2.sub(p1.scale(2)).add(p0).scale(6 * u).add(p3.sub(p2.scale(2)).add(p1).scale(6 * t));
    }

    /** Direction of travel at parameter {@code t} (length 1; the straight line's for a curve of length 0). */
    public Vec tangent(double t) {
        Vec d = d1(t);
        if (d.length() < 1e-9) {
            d = p3.sub(p0).length() > 1e-9 ? p3.sub(p0) : p1.sub(p0);
        }
        return d.normalize();
    }

    /** Length along the curve. */
    public double length() {
        return arc[arc.length - 1];
    }

    /** The tightest bend's radius (infinite for a straight curve): placers refuse curves bent tighter than they allow. */
    public double minRadius() {
        double maxK = 0;
        for (double t : ts) {
            Vec a = d1(t);
            double s = a.length();
            if (s < 1e-9) {
                continue;
            }
            maxK = Math.max(maxK, a.cross(d2(t)).length() / (s * s * s));
        }
        return maxK < 1e-12 ? Double.POSITIVE_INFINITY : 1 / maxK;
    }

    /** The parameter at {@code distance} along the curve. */
    public double paramAt(double distance) {
        if (distance <= 0) {
            return 0;
        }
        if (distance >= length()) {
            return 1;
        }
        int lo = 0;
        int hi = arc.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (arc[mid] < distance) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double f = (distance - arc[lo]) / Math.max(1e-12, arc[hi] - arc[lo]);
        return ts[lo] + (ts[hi] - ts[lo]) * f;
    }

    /** Points about {@code step} apart along the curve, both ends included. */
    public List<Vec> points(double step) {
        List<Vec> out = new ArrayList<>();
        for (Frame f : frames(step)) {
            out.add(f.pos());
        }
        return out;
    }

    /** Frames about {@code step} apart along the curve, both ends included; normals do not twist along the curve. */
    public List<Frame> frames(double step) {
        double len = length();
        int n = Math.max(1, (int) Math.ceil(len / Math.max(1e-3, step)));
        List<Frame> out = new ArrayList<>(n + 1);
        Vec normal = null;
        for (int i = 0; i <= n; i++) {
            double d = len * i / n;
            double t = paramAt(d);
            Vec tan = tangent(t);
            if (normal == null) {
                Vec up = Math.abs(tan.y()) > 0.95 ? new Vec(1, 0, 0) : new Vec(0, 1, 0);
                normal = up.sub(tan.scale(up.dot(tan))).normalize();
            } else {
                // Parallel transport: carry the last normal over, square to the new tangent.
                Vec m = normal.sub(tan.scale(normal.dot(tan)));
                normal = m.length() < 1e-9 ? normal : m.normalize();
            }
            out.add(new Frame(point(t), tan, normal, tan.cross(normal).normalize(), d));
        }
        return out;
    }
}
