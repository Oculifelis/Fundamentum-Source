package com.fundamentum.flail;

import com.fundamentum.whip.WhipCord;
import com.fundamentum.whip.WhipVec;

/**
 * A flail's heads on their chains, stepped once a tick (positions in blocks, speeds in blocks a tick). Plain maths on
 * {@link WhipVec} (no game types), so the server, the client's drawing and the unit tests run the same code.
 *
 * <p>Each head hangs on its own chain from the hand: {@code chain_segments} links (one rigid arm for
 * {@link FlailPhysics#PENDULUM}) solved with Verlet steps and distance constraints weighted by mass (the head weighs
 * {@code head_mass}, a link 0.15), bent back toward straight by {@code stiffness}, slowed by {@code damping}, and
 * bounced off blocks by {@code bounce}. The hand drives the heads toward a moving target (the arc of a swing, the
 * circle of a spin); how closely they follow is {@code inertia} (a heavy, sluggish head lags and carries through).
 */
public final class FlailSim {
    /** What the hand is doing with the flail. */
    public enum Mode { IDLE, SWING, SPIN, SMASH, LAUNCH, REEL }

    /** Gravity, blocks a tick squared (between a dropped item's and a falling mob's). */
    public static final double GRAVITY = 0.06;
    /** No point moves further than this in a tick (keeps a bad frame from flinging the chain). */
    public static final double MAX_STEP = 3.0;
    /** One link's mass against the head's {@code head_mass}. */
    public static final double LINK_MASS = 0.15;
    static final int ITERATIONS = 8;
    /** Ticks a smash stays down after its arc, so a slow heavy head still lands. */
    static final int SMASH_HOLD = 6;
    /** How hard (blocks a tick into the surface) a thrown head must hit a block to stop there; resting on it is less. */
    static final double STRIKE = 0.2;

    public final FlailSpec spec;
    /** Each head's chain, hand first, head last. */
    public final WhipVec[][] points;
    private final WhipVec[][] prev;
    /** Each head's chain length now: {@code chain_length}, or longer while a thrown head is out. */
    public final double[] reach;
    /** Each head's block impact this step (its speed into the block), or 0. */
    public final double[] impact;
    /** The block each head struck this step (x, y, z), when {@link #impact} is above 0. */
    public final int[][] impactBlock;
    public Mode mode = Mode.IDLE;
    /** Ticks in the current mode. */
    public int tick;
    /** The spin now, radians a tick. */
    public double spin;
    /** How full the spin was when it was let go (0..1): the throw's and the smash's strength. */
    public double charge;
    private double phase;
    private int side = 1;
    private WhipVec forward = new WhipVec(0, 0, 1);
    private WhipVec root;

    public FlailSim(FlailSpec spec, WhipVec root) {
        this.spec = spec;
        int heads = spec.headCount;
        int n = spec.chainSegments + 1;
        this.points = new WhipVec[heads][n];
        this.prev = new WhipVec[heads][n];
        this.reach = new double[heads];
        this.impact = new double[heads];
        this.impactBlock = new int[heads][3];
        reset(root);
    }

    /** Puts every chain hanging straight down from {@code root}, at rest. */
    public void reset(WhipVec root) {
        this.root = root;
        for (int h = 0; h < points.length; h++) {
            reach[h] = sideLength();
            double off = (h - (points.length - 1) * 0.5) * 0.08;
            for (int i = 0; i < points[h].length; i++) {
                WhipVec p = root.add(new WhipVec(off, -reach[h] * i / spec.chainSegments, 0));
                points[h][i] = p;
                prev[h][i] = p;
            }
        }
        mode = Mode.IDLE;
        tick = 0;
        spin = 0;
    }

    /** One chain's length: a double-ended flail is held in the middle, so each end gets half. */
    double sideLength() {
        return spec.doubleEnded ? spec.chainLength * 0.5 : spec.chainLength;
    }

    // ------------------------------------------------------------------------------------------------ what the hand does
    /** A left-click swing across the front, from {@code side} (1 right to left, -1 left to right). */
    public void swing(int side) {
        this.side = side >= 0 ? 1 : -1;
        mode = Mode.SWING;
        tick = 0;
    }

    /** Starts (or keeps) spinning overhead; the spin builds to {@code max_spin} over {@code spin_up} ticks. */
    public void startSpin() {
        if (mode != Mode.SPIN) {
            mode = Mode.SPIN;
            tick = 0;
        }
    }

    /**
     * Lets a spin go: a thrown head for {@link FlailPhysics#launches}, else an overhead smash down ahead. Returns the new
     * mode. The spin's fullness becomes {@link #charge}.
     */
    public Mode release() {
        charge = chargeNow();
        spin = 0;
        if (spec.physics.launches()) {
            launch(charge);
        } else {
            mode = Mode.SMASH;
            tick = 0;
        }
        return mode;
    }

    /** Throws the head(s) out along the look at once (a tap, or the end of a spin) with this much of a full spin (0..1). */
    public void launch(double charge) {
        if (spec.launch == null) {
            return;
        }
        this.charge = charge;
        mode = Mode.LAUNCH;
        tick = 0;
        double speed = spec.launch.speed() * (0.75 + 0.25 * clamp(charge, 0, 1));
        for (int h = 0; h < points.length; h++) {
            if (!thrown(h)) {
                continue;
            }
            // A little upward so a head hanging at the floor leaves it instead of sliding along it.
            WhipVec dir = fan(forward, h).add(new WhipVec(0, 0.2, 0)).normalize();
            int n = points[h].length;
            for (int i = 1; i < n; i++) {
                // The head flies; the links behind it are drawn after it, less the nearer the hand they are.
                double share = (double) i / (n - 1);
                WhipVec v = dir.scale(speed * share);
                prev[h][i] = points[h][i].sub(v);
            }
        }
    }

    /** True for heads a throw sends out: every head, except the near end of a double-ended chain. */
    boolean thrown(int h) {
        return !spec.doubleEnded || h == 0;
    }

    /** The spin's fullness now (0..1). */
    public double chargeNow() {
        return clamp(spin / spec.maxSpinRadians(), 0, 1);
    }

    /** True while the heads are swung, spun or thrown, and can hit. */
    public boolean active() {
        return mode != Mode.IDLE;
    }

    // ------------------------------------------------------------------------------------------------ stepping
    /** One tick: the hand at {@code root} looking along {@code look}; {@code solids} is the world (null for open air). */
    public void step(WhipVec root, WhipVec look, WhipCord.Solids solids) {
        this.root = root;
        WhipVec flat = new WhipVec(look.x(), 0, look.z());
        forward = flat.lengthSqr() < 1.0E-6 ? forward : flat.normalize();
        tick++;
        if (mode == Mode.SPIN) {
            double full = spec.maxSpinRadians();
            spin = Math.min(full, spin + full / Math.max(1, spec.spinUp));
            phase += spin;
        }
        if (mode == Mode.LAUNCH || mode == Mode.REEL) {
            stepThrow();
        }
        double gain = clamp(0.45 / spec.inertia, 0.08, 0.9);
        double keep = 1.0 - spec.damping;
        for (int h = 0; h < points.length; h++) {
            impact[h] = 0;
            WhipVec[] p = points[h], q = prev[h];
            p[0] = root;
            q[0] = root;
            for (int i = 1; i < p.length; i++) {
                WhipVec v = p[i].sub(q[i]).scale(keep);
                double s = v.length();
                if (s > MAX_STEP) {
                    v = v.scale(MAX_STEP / s);
                }
                q[i] = p[i];
                p[i] = p[i].add(v).add(new WhipVec(0, -GRAVITY, 0));
            }
            WhipVec target = target(h, root);
            if (target != null) {
                int last = p.length - 1;
                p[last] = p[last].lerp(target, gain);
            }
            for (int it = 0; it < ITERATIONS; it++) {
                constrain(h);
            }
            if (solids != null) {
                collide(h, solids);
            }
            limit(h);
            if (!finite(p)) {
                reset(root);
                return;
            }
        }
        advanceMode();
    }

    private void stepThrow() {
        FlailSpec.Launch l = spec.launch;
        double base = sideLength();
        for (int h = 0; h < points.length; h++) {
            if (!thrown(h)) {
                continue;
            }
            if (mode == Mode.LAUNCH) {
                // The chain pays out behind the head up to the range, a tick ahead so it never holds the head back.
                double out = head(h).distanceTo(root) + speed(h, points[h].length - 1);
                reach[h] = clamp(Math.max(reach[h], out), base, l.range());
            } else {
                reach[h] = Math.max(base, reach[h] - (l.range() - base) / l.reelTicks());
            }
        }
    }

    private void advanceMode() {
        switch (mode) {
            case SWING, SMASH -> {
                // A smash holds its low point a few ticks so a heavy head still reaches the ground.
                if (tick >= spec.swingTicks() + (mode == Mode.SMASH ? SMASH_HOLD : 0)) {
                    mode = Mode.IDLE;
                    tick = 0;
                }
            }
            case LAUNCH -> {
                FlailSpec.Launch l = spec.launch;
                boolean out = false;
                for (int h = 0; h < points.length; h++) {
                    out |= thrown(h) && (reach[h] >= l.range() - 1.0E-6 || impact[h] > STRIKE);
                }
                if (out || tick > l.range() / l.speed() * 2 + 4) {
                    mode = Mode.REEL;
                    tick = 0;
                }
            }
            case REEL -> {
                boolean in = true;
                for (int h = 0; h < points.length; h++) {
                    in &= !thrown(h) || reach[h] <= sideLength() + 1.0E-6 && head(h).distanceTo(points[h][0]) <= sideLength() * 1.2 + 0.1;
                }
                if (in || tick > spec.launch.reelTicks() * 3 + 10) {
                    mode = Mode.IDLE;
                    tick = 0;
                }
            }
            default -> {}
        }
    }

    /** Where the hand pulls a head this tick, or null when it hangs free. */
    WhipVec target(int h, WhipVec root) {
        WhipVec up = WhipVec.UP;
        WhipVec right = forward.cross(up).normalize(new WhipVec(1, 0, 0));
        double r = reach[h] * 0.92;
        double fanRad = Math.toRadians(spec.spread) * (h - (points.length - 1) * 0.5);
        switch (mode) {
            case SPIN -> {
                double a = phase + (spec.doubleEnded ? Math.PI * h : Math.toRadians(spec.spread) * h);
                WhipVec centre = root.add(new WhipVec(0, 0.7, 0));
                return centre.add(forward.scale(Math.cos(a) * r)).add(right.scale(Math.sin(a) * r));
            }
            case SWING -> {
                double u = Math.min(1.0, (double) tick / spec.swingTicks());
                double s = u * u * (3 - 2 * u);
                double a = side * Math.toRadians(-110 + 190 * s) - fanRad * side;
                return root.add(new WhipVec(0, 0.2 - 0.6 * s, 0)).add(forward.scale(Math.cos(a) * r)).add(right.scale(Math.sin(a) * r));
            }
            case SMASH -> {
                double u = Math.min(1.0, (double) tick / spec.swingTicks());
                double a = -0.4 + 3.0 * u * u;
                WhipVec dir = fan(forward, h);
                return root.add(up.scale(Math.cos(a) * r)).add(dir.scale(Math.sin(a) * r));
            }
            case REEL -> {
                if (!thrown(h)) {
                    return null;
                }
                // Hauled straight back in along the line it is on.
                WhipVec out = points[h][points[h].length - 1].sub(root);
                return root.add(out.normalize(forward).scale(reach[h] * 0.9));
            }
            default -> {
                return null;
            }
        }
    }

    /** {@code dir} turned about the vertical by head {@code h}'s place in the fan. */
    WhipVec fan(WhipVec dir, int h) {
        double fanRad = Math.toRadians(spec.spread) * (h - (points.length - 1) * 0.5);
        return fanRad == 0 ? dir : dir.rotate(WhipVec.UP, fanRad);
    }

    private void constrain(int h) {
        WhipVec[] p = points[h];
        int n = p.length;
        double rest = reach[h] / (n - 1);
        boolean slack = mode == Mode.LAUNCH && thrown(h);
        double headW = 1.0 / spec.headMass, linkW = 1.0 / LINK_MASS;
        for (int i = 1; i < n; i++) {
            WhipVec d = p[i].sub(p[i - 1]);
            double len = d.length();
            if (len < 1.0E-9 || (slack && len <= rest)) {
                continue;
            }
            double wa = i - 1 == 0 ? 0 : linkW;
            double wb = i == n - 1 ? headW : linkW;
            double diff = (len - rest) / len;
            double total = wa + wb;
            p[i - 1] = p[i - 1].addScaled(d, diff * wa / total);
            p[i] = p[i].addScaled(d, -diff * wb / total);
        }
        if (!spec.physics.rigid() && spec.stiffness > 0) {
            double k = spec.stiffness * 0.25;
            for (int i = 2; i < n; i++) {
                WhipVec dir = p[i - 1].sub(p[i - 2]).normalize(WhipVec.ZERO);
                if (dir == WhipVec.ZERO) {
                    continue;
                }
                WhipVec straight = p[i - 1].addScaled(dir, rest);
                p[i] = p[i].lerp(straight, k);
            }
        }
    }

    /**
     * The last word on length: no link ends up more than a tenth over its rest length, whatever a heavy head's speed or
     * a wall did to the solver this tick. A point drawn back loses the part of its velocity that pointed outward, so the
     * limit only ever takes energy out. The speed cap holds at the end of the tick too: the drive's pull on the head can
     * otherwise carry it past {@link #MAX_STEP} after the cap was applied.
     */
    private void limit(int h) {
        WhipVec[] p = points[h], q = prev[h];
        double max = reach[h] / (p.length - 1) * 1.1;
        for (int i = 1; i < p.length; i++) {
            WhipVec step = p[i].sub(q[i]);
            double s = step.length();
            if (s > MAX_STEP) {
                q[i] = p[i].addScaled(step, -MAX_STEP / s);
            }
            WhipVec d = p[i].sub(p[i - 1]);
            double len = d.length();
            if (len > max) {
                WhipVec np = p[i - 1].addScaled(d, max / len);
                WhipVec u = d.scale(1.0 / len), v = p[i].sub(q[i]);
                double out = v.dot(u);
                if (out > 0) {
                    v = v.addScaled(u, -out);
                }
                q[i] = np.sub(v);
                p[i] = np;
            }
        }
    }

    private void collide(int h, WhipCord.Solids solids) {
        WhipVec[] p = points[h], q = prev[h];
        int last = p.length - 1;
        for (int i = 1; i < p.length; i++) {
            WhipVec at = p[i];
            int bx = (int) Math.floor(at.x()), by = (int) Math.floor(at.y()), bz = (int) Math.floor(at.z());
            double[] box = solids.box(bx, by, bz);
            if (box == null || at.x() <= box[0] || at.x() >= box[3] || at.y() <= box[1] || at.y() >= box[4] || at.z() <= box[2]
                || at.z() >= box[5]) {
                continue;
            }
            // Out through the nearest face that opens on air: a face against another solid block would only push the
            // point into that one. Buried with no open face near, it climbs out upward, toward the hand.
            double[] pen = {at.x() - box[0], box[3] - at.x(), at.y() - box[1], box[4] - at.y(), at.z() - box[2], box[5] - at.z()};
            int face = -1;
            for (int f = 0; f < 6; f++) {
                int nx = bx + (f == 0 ? -1 : f == 1 ? 1 : 0), ny = by + (f == 2 ? -1 : f == 3 ? 1 : 0), nz = bz + (f == 4 ? -1 : f == 5 ? 1 : 0);
                if (solids.box(nx, ny, nz) == null && (face < 0 || pen[f] < pen[face])) {
                    face = f;
                }
            }
            if (face < 0) {
                face = 3;
            }
            WhipVec normal = switch (face) {
                case 0 -> new WhipVec(-1, 0, 0);
                case 1 -> new WhipVec(1, 0, 0);
                case 2 -> new WhipVec(0, -1, 0);
                case 3 -> new WhipVec(0, 1, 0);
                case 4 -> new WhipVec(0, 0, -1);
                default -> new WhipVec(0, 0, 1);
            };
            WhipVec out = at.addScaled(normal, pen[face] + 1.0E-3);
            WhipVec v = at.sub(q[i]);
            double into = -v.dot(normal);
            WhipVec tangent = v.addScaled(normal, into);
            WhipVec bounced = tangent.scale(0.7).addScaled(normal, Math.max(0, into) * spec.bounce);
            p[i] = out;
            q[i] = out.sub(bounced);
            if (i == last && into > impact[h]) {
                impact[h] = into;
                impactBlock[h][0] = bx;
                impactBlock[h][1] = by;
                impactBlock[h][2] = bz;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ reading it
    public WhipVec head(int h) {
        WhipVec[] p = points[h];
        return p[p.length - 1];
    }

    /** A head's speed, blocks a tick. */
    public double headSpeed(int h) {
        int last = points[h].length - 1;
        return points[h][last].sub(prev[h][last]).length();
    }

    /** A head's velocity, blocks a tick. */
    public WhipVec headVelocity(int h) {
        int last = points[h].length - 1;
        return points[h][last].sub(prev[h][last]);
    }

    /** A point's speed, blocks a tick. */
    public double speed(int h, int i) {
        return points[h][i].sub(prev[h][i]).length();
    }

    /**
     * Kinetic plus potential energy of every head and link (mass times blocks squared a tick squared), the hand's height as
     * zero. For tests: with no drive it must not grow, and it stays bounded however the flail is driven.
     */
    public double energy() {
        double e = 0;
        for (int h = 0; h < points.length; h++) {
            WhipVec[] p = points[h], q = prev[h];
            for (int i = 1; i < p.length; i++) {
                double m = i == p.length - 1 ? spec.headMass : LINK_MASS;
                double v2 = p[i].sub(q[i]).lengthSqr();
                e += m * (0.5 * v2 + GRAVITY * (p[i].y() - root.y()));
            }
        }
        return e;
    }

    /** True when the sphere at {@code c} with radius {@code r} touches the box (minX, minY, minZ, maxX, maxY, maxZ). */
    public static boolean touches(WhipVec c, double r, double[] box) {
        double dx = Math.max(box[0] - c.x(), Math.max(0, c.x() - box[3]));
        double dy = Math.max(box[1] - c.y(), Math.max(0, c.y() - box[4]));
        double dz = Math.max(box[2] - c.z(), Math.max(0, c.z() - box[5]));
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    private static boolean finite(WhipVec[] p) {
        for (WhipVec v : p) {
            if (!v.finite()) {
                return false;
            }
        }
        return true;
    }

    static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(max, v);
    }
}
