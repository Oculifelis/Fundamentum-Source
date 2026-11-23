package com.fundamentum.thrown;

import com.fundamentum.whip.WhipVec;

/**
 * The flight of one throw, as a plain state machine with no game types (unit-tested, the same on every version).
 * <ul>
 * <li>A throw that does not return is {@link Phase#OUTBOUND} with gravity until its pierce is spent, then
 * {@link Phase#FALLING} (it hits nothing more), then on a block {@link Phase#STUCK} or dropped or broken.</li>
 * <li>A returning throw flies straight out at its throw speed until it has flown its range, used up its pierce or hit a
 * block it cannot glance off, then {@link Phase#RETURNING} home to the thrower, speeding up to its return speed. It
 * never turns out again.</li>
 * </ul>
 * {@link ThrownWeapon} drives one of these each server tick.
 */
public final class ThrownFlight {
    /** Saved by name. */
    public enum Phase { OUTBOUND, RETURNING, FALLING, STUCK }

    /** What a block in the way does to the flight. */
    public enum BlockResult { GLANCE, TURN_HOME, STICK, DROP, BREAK, NONE }

    /** What a creature hit does to the flight. */
    public enum HitResult { PASS, RICOCHET, TURN_HOME, FALL, BREAK }

    /** Distance from the thrower's eyes at which a returning throw is caught. */
    public static final double CATCH = 1.5;
    /** How much of its speed a spent throw keeps, bounced back off the creature it stopped in. */
    public static final double DEFLECT = -0.1;

    private final ThrownStats stats;
    private Phase phase = Phase.OUTBOUND;
    private double travelled;
    private int pierced;
    private int bouncesUsed;
    private int age;
    private int homeTicks;
    private boolean pierceSpent;

    public ThrownFlight(ThrownStats stats) {
        this.stats = stats;
    }

    public ThrownStats stats() {
        return stats;
    }

    public Phase phase() {
        return phase;
    }

    public boolean returning() {
        return phase == Phase.RETURNING;
    }

    public boolean stuck() {
        return phase == Phase.STUCK;
    }

    public double travelled() {
        return travelled;
    }

    public int age() {
        return age;
    }

    public int bouncesLeft() {
        return stats.returns() == null ? 0 : stats.returns().bounces() - bouncesUsed;
    }

    /** Turns a returning throw home now (the one change of phase it gets). */
    public void turnHome() {
        phase = Phase.RETURNING;
    }

    /** Past its time without landing or coming home: drop it. A throw stuck in a block stays. */
    public boolean expired() {
        return phase != Phase.STUCK && age > stats.maxTicks();
    }

    /**
     * One tick: the velocity to fly with this tick. A returning throw out keeps its heading at the throw speed and counts
     * the distance; home, it heads for {@code home} (the thrower's eyes), speeding up each tick. Any other throw keeps its
     * velocity with gravity added; a stuck one stays still.
     */
    public WhipVec tick(WhipVec pos, WhipVec velocity, WhipVec home) {
        age++;
        ThrownStats.Returns back = stats.returns();
        if (phase == Phase.STUCK) {
            return WhipVec.ZERO;
        }
        if (back == null || phase == Phase.FALLING) {
            travelled += velocity.length();
            return velocity.add(new WhipVec(0, -stats.gravity(), 0));
        }
        if (phase == Phase.OUTBOUND) {
            if (travelled >= back.range()) {
                turnHome();
            } else {
                travelled += stats.throwSpeed();
                return velocity.normalize(new WhipVec(0, 0, 1)).scale(stats.throwSpeed());
            }
        }
        homeTicks++;
        double speed = Math.min(back.returnSpeed(), stats.throwSpeed() * 0.5 + back.returnAccel() * homeTicks);
        WhipVec to = home.sub(pos);
        double d = to.length();
        // never overshoot the catch: the last step lands in the hand
        return to.normalize(WhipVec.ZERO).scale(Math.min(speed, d));
    }

    /** Close enough to be caught (only on the way home). */
    public boolean caught(WhipVec pos, WhipVec home) {
        return phase == Phase.RETURNING && pos.distanceTo(home) < CATCH;
    }

    /** Whether it may hit a creature now (one it has not hit this leg). */
    public boolean canHit() {
        return phase == Phase.OUTBOUND || phase == Phase.RETURNING && stats.returns() != null && stats.returns().hitsOnReturn();
    }

    /**
     * A creature was hit. A breaking throw breaks. Out, a returning throw ricochets on to the next enemy while bounces are
     * left ({@link HitResult#RICOCHET}: the caller aims it, calling {@link #ricochet} or, with no enemy in reach,
     * {@link #noRicochetTarget}); else it uses one pierce, and with none left a returning throw turns home and any other
     * falls ({@link HitResult#FALL}: the caller turns it back off the creature, see {@link #DEFLECT}). Home, hits change
     * nothing.
     */
    public HitResult onHit() {
        if (phase != Phase.OUTBOUND) {
            return HitResult.PASS;
        }
        if (stats.breaksOnHit()) {
            return HitResult.BREAK;
        }
        pierceSpent = stats.pierce() != ThrownStats.UNLIMITED && pierced++ >= stats.pierce();
        if (bouncesLeft() > 0) {
            return HitResult.RICOCHET;
        }
        return pierceSpent ? spent() : HitResult.PASS;
    }

    private HitResult spent() {
        if (stats.returning()) {
            turnHome();
            return HitResult.TURN_HOME;
        }
        phase = Phase.FALLING;
        return HitResult.FALL;
    }

    /** A ricochet found no enemy: it flies on, or turns home if that hit used its last pierce. */
    public HitResult noRicochetTarget() {
        return pierceSpent ? spent() : HitResult.PASS;
    }

    /** A ricochet was taken: one bounce used, and it gets a fresh stretch of range to reach the new target. */
    public void ricochet(double distance) {
        bouncesUsed++;
        double range = stats.returns() == null ? 0 : stats.returns().range();
        travelled = Math.max(0, Math.min(travelled, range - distance - 1));
    }

    /**
     * A block was hit. A breaking throw breaks. A returning throw out glances off while bounces are left, else turns home;
     * home, blocks do nothing. Any other throw sticks in the block or drops.
     */
    public BlockResult onBlock() {
        if (phase == Phase.RETURNING || phase == Phase.STUCK) {
            return BlockResult.NONE;
        }
        if (stats.breaksOnHit()) {
            return BlockResult.BREAK;
        }
        if (stats.returning()) {
            if (bouncesLeft() > 0) {
                bouncesUsed++;
                return BlockResult.GLANCE;
            }
            turnHome();
            return BlockResult.TURN_HOME;
        }
        if (stats.sticksInTarget()) {
            phase = Phase.STUCK;
            return BlockResult.STICK;
        }
        return BlockResult.DROP;
    }

    /** The block it was stuck in is gone: it falls again (and lands, sticking or dropping, as before). */
    public void unstick() {
        if (phase == Phase.STUCK) {
            phase = Phase.FALLING;
        }
    }

    /** {@code v} mirrored off a surface with unit normal {@code n}. */
    public static WhipVec reflect(WhipVec v, WhipVec n) {
        return v.sub(n.scale(2 * v.dot(n)));
    }

    // saving: the game side writes these and calls restore

    public int pierced() {
        return pierced;
    }

    public int bouncesUsed() {
        return bouncesUsed;
    }

    public int homeTicks() {
        return homeTicks;
    }

    /** Puts back a saved flight. An unknown phase name flies out. */
    public void restore(String phaseName, double travelled, int pierced, int bouncesUsed, int age, int homeTicks) {
        this.phase = parse(phaseName);
        this.travelled = travelled;
        this.pierced = pierced;
        this.bouncesUsed = bouncesUsed;
        this.age = age;
        this.homeTicks = homeTicks;
    }

    /** The phase of this name; unknown names (and older Ostentum hatchet saves' OUTBOUND / RETURNING) parse as they are. */
    public static Phase parse(String name) {
        for (Phase p : Phase.values()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return Phase.OUTBOUND;
    }
}
