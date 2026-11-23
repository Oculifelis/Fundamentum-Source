package com.fundamentum.whip;

/**
 * A plain 3D vector for the cord maths. The physics ({@link WhipCord}, {@link WhipSwing}, {@link CordHit}) uses no
 * game types, so it runs in unit tests and ports to every version unchanged; {@link Whips} converts at the edges.
 */
public record WhipVec(double x, double y, double z) {
    public static final WhipVec ZERO = new WhipVec(0, 0, 0);
    public static final WhipVec UP = new WhipVec(0, 1, 0);

    public WhipVec add(WhipVec o) {
        return new WhipVec(x + o.x, y + o.y, z + o.z);
    }

    public WhipVec sub(WhipVec o) {
        return new WhipVec(x - o.x, y - o.y, z - o.z);
    }

    public WhipVec scale(double s) {
        return new WhipVec(x * s, y * s, z * s);
    }

    /** this + o * s */
    public WhipVec addScaled(WhipVec o, double s) {
        return new WhipVec(x + o.x * s, y + o.y * s, z + o.z * s);
    }

    public double dot(WhipVec o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public WhipVec cross(WhipVec o) {
        return new WhipVec(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double lengthSqr() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSqr());
    }

    public double distanceTo(WhipVec o) {
        return sub(o).length();
    }

    /** The unit vector, or {@code fallback} when this is (about) zero. */
    public WhipVec normalize(WhipVec fallback) {
        double l = length();
        return l < 1.0E-9 ? fallback : scale(1.0 / l);
    }

    public WhipVec normalize() {
        return normalize(ZERO);
    }

    public WhipVec lerp(WhipVec to, double t) {
        return new WhipVec(x + (to.x - x) * t, y + (to.y - y) * t, z + (to.z - z) * t);
    }

    /** Rodrigues: this vector turned by {@code radians} about the unit {@code axis}. */
    public WhipVec rotate(WhipVec axis, double radians) {
        double c = Math.cos(radians), s = Math.sin(radians);
        return scale(c).add(axis.cross(this).scale(s)).add(axis.scale(axis.dot(this) * (1 - c)));
    }

    public boolean finite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    /** Java 21's Math.clamp, for Java 17. */
    public static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    public static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
