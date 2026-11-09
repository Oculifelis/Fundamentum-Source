package com.fundamentum.flail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fundamentum.whip.WhipCord;
import com.fundamentum.whip.WhipVec;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * The flail physics, run headless for every shipped material and kind under every physics model: nothing goes NaN, the
 * energy stays bounded, the chain keeps its length, and the hand's moves (swing, spin, smash, throw) end where they should.
 */
class FlailSimTest {
    static final WhipVec HAND = new WhipVec(0.35, 1.4, 0.3);
    static final WhipVec LOOK = new WhipVec(0, 0, 1);
    /** Stone floor at y below 0. */
    static final WhipCord.Solids FLOOR = (x, y, z) -> y < 0 ? new double[] {x, y, z, x + 1, y + 1, z + 1} : null;

    static FlailSpec spec(String physics, String material, String kind) {
        JsonObject flail = new JsonObject();
        flail.addProperty("physics", physics);
        flail.addProperty("material", material);
        flail.addProperty("kind", kind);
        return FlailSpec.resolve(flail, ShippedPresets.materials(), ShippedPresets.kinds());
    }

    static void checkFinite(FlailSim sim) {
        for (WhipVec[] chain : sim.points) {
            for (WhipVec p : chain) {
                assertTrue(p.finite(), "point went NaN or infinite");
            }
        }
    }

    /** The whole chain stays within its length of the hand (a little stretch while it is driven hard). */
    static void checkLength(FlailSim sim) {
        for (int h = 0; h < sim.points.length; h++) {
            double max = sim.reach[h] * 1.35 + 0.1;
            assertTrue(sim.head(h).distanceTo(sim.points[h][0]) <= max,
                sim.spec.physics + " head " + h + " is " + sim.head(h).distanceTo(sim.points[h][0]) + " out on a reach of " + sim.reach[h]);
        }
    }

    @Test
    void everyPresetUnderEveryPhysicsStaysStable() {
        for (FlailPhysics physics : FlailPhysics.values()) {
            for (String material : ShippedPresets.materials().keySet()) {
                for (String kind : ShippedPresets.kinds().keySet()) {
                    FlailSpec spec;
                    try {
                        spec = spec(physics.id(), material, kind);
                    } catch (IllegalArgumentException e) {
                        // A pendulum's rigid arm can't wrap, so kinds that wrap are refused for it: that is the rule, not a bug.
                        if (physics.rigid() && e.getMessage().contains("can't wrap")) {
                            continue;
                        }
                        throw e;
                    }
                    FlailSim sim = new FlailSim(spec, HAND);
                    double maxEnergy = 0;
                    for (int round = 0; round < 3; round++) {
                        sim.swing(round % 2 == 0 ? 1 : -1);
                        for (int t = 0; t < 40; t++) {
                            sim.step(HAND, LOOK, FLOOR);
                            checkFinite(sim);
                            checkLength(sim);
                            maxEnergy = Math.max(maxEnergy, sim.energy());
                        }
                        sim.startSpin();
                        for (int t = 0; t < 60; t++) {
                            sim.step(HAND, LOOK, FLOOR);
                            checkFinite(sim);
                            checkLength(sim);
                            maxEnergy = Math.max(maxEnergy, sim.energy());
                        }
                        sim.release();
                        for (int t = 0; t < 120 && sim.active(); t++) {
                            sim.step(HAND, LOOK, FLOOR);
                            checkFinite(sim);
                            maxEnergy = Math.max(maxEnergy, sim.energy());
                        }
                        assertEquals(FlailSim.Mode.IDLE, sim.mode, physics + "/" + material + "/" + kind + " never came back to rest");
                    }
                    // Bounded: every point's mass at the max step speed, plus lifted the whole range overhead, with a quarter
                    // over for the solver's own push on a link already at the speed cap.
                    double mass = spec.headCount * (spec.headMass + spec.chainSegments * FlailSim.LINK_MASS);
                    double reach = spec.launch == null ? spec.chainLength : spec.launch.range();
                    double bound = 1.25 * mass * (0.5 * FlailSim.MAX_STEP * FlailSim.MAX_STEP + FlailSim.GRAVITY * (reach + 2));
                    assertTrue(maxEnergy < bound, physics + "/" + material + "/" + kind + " energy " + maxEnergy + " over " + bound);
                }
            }
        }
    }

    /**
     * Left alone, the flail only loses energy. The measure leaves out the bend the solver is still taking out of the
     * chain, so a tick may show a small rise as that turns into motion; it never climbs past where it started.
     */
    @Test
    void undrivenEnergyNeverGrows() {
        for (FlailPhysics physics : FlailPhysics.values()) {
            FlailSpec spec = spec(physics.id(), "fundamentum:iron", "fundamentum:single");
            FlailSim sim = new FlailSim(spec, HAND);
            sim.swing(1);
            for (int t = 0; t < 8; t++) {
                sim.step(HAND, LOOK, null);
            }
            sim.mode = FlailSim.Mode.IDLE;
            double start = sim.energy();
            double e = start;
            for (int t = 0; t < 200; t++) {
                sim.step(HAND, LOOK, null);
                e = sim.energy();
                assertTrue(e <= start + 0.05, physics + " energy grew from " + start + " to " + e + " at tick " + t);
            }
            assertTrue(e <= start, physics + " ended with more energy (" + e + ") than it started with (" + start + ")");
        }
    }

    @Test
    void aSwingIsFastAndEnds() {
        FlailSpec spec = spec("links", "fundamentum:iron", "fundamentum:single");
        FlailSim sim = new FlailSim(spec, HAND);
        sim.swing(1);
        double top = 0;
        int ticks = 0;
        while (sim.active() && ticks < 100) {
            sim.step(HAND, LOOK, null);
            top = Math.max(top, sim.headSpeed(0));
            ticks++;
        }
        assertEquals(spec.swingTicks(), ticks);
        assertTrue(spec.speedFactor(top) >= 0.6, "a swing's head reaches only " + top + " (" + spec.speedFactor(top) + " of full)");
    }

    @Test
    void aThrownHeadReachesOutAndComesBack() {
        FlailSpec spec = spec("launch", "fundamentum:iron", "fundamentum:single");
        FlailSim sim = new FlailSim(spec, HAND);
        sim.startSpin();
        for (int t = 0; t < spec.spinUp + 5; t++) {
            sim.step(HAND, LOOK, null);
        }
        assertEquals(1.0, sim.chargeNow(), 1.0E-9);
        assertEquals(FlailSim.Mode.LAUNCH, sim.release());
        double furthest = 0;
        for (int t = 0; t < 200 && sim.active(); t++) {
            sim.step(HAND, LOOK, null);
            furthest = Math.max(furthest, sim.head(0).sub(HAND).dot(LOOK));
        }
        assertEquals(FlailSim.Mode.IDLE, sim.mode);
        assertTrue(furthest > spec.launch.range() * 0.6, "the head only got " + furthest + " out of " + spec.launch.range());
        assertTrue(sim.head(0).distanceTo(HAND) < spec.chainLength * 1.35, "the head was not reeled back in");
    }

    @Test
    void aSmashHitsTheGroundAhead() {
        FlailSpec spec = spec("links", "fundamentum:stone", "fundamentum:ball_and_chain");
        FlailSim sim = new FlailSim(spec, HAND);
        sim.startSpin();
        for (int t = 0; t < spec.spinUp; t++) {
            sim.step(HAND, LOOK, FLOOR);
        }
        assertEquals(FlailSim.Mode.SMASH, sim.release());
        double hardest = 0;
        for (int t = 0; t < 60 && sim.active(); t++) {
            sim.step(HAND, LOOK, FLOOR);
            hardest = Math.max(hardest, sim.impact[0]);
        }
        assertTrue(hardest > 0.1, "the smash never struck the floor (" + hardest + ")");
    }

    @Test
    void aPendulumArmIsRigid() {
        FlailSpec spec = spec("pendulum", "fundamentum:chain_mace", "fundamentum:single");
        assertEquals(1, spec.chainSegments);
        FlailSim sim = new FlailSim(spec, HAND);
        sim.swing(-1);
        for (int t = 0; t < 30; t++) {
            sim.step(HAND, LOOK, null);
            assertEquals(spec.chainLength, sim.head(0).distanceTo(HAND), 0.05);
        }
    }

    @Test
    void aMeteorHammerSpinsBothEnds() {
        FlailSpec spec = spec("hybrid", "fundamentum:iron", "fundamentum:meteor_hammer");
        FlailSim sim = new FlailSim(spec, HAND);
        sim.startSpin();
        for (int t = 0; t < 40; t++) {
            sim.step(HAND, LOOK, null);
        }
        // The two ends are on opposite sides of the circle.
        WhipVec a = sim.head(0).sub(HAND), b = sim.head(1).sub(HAND);
        assertTrue(a.x() * b.x() + a.z() * b.z() < 0, "both ends of the meteor hammer are on the same side");
        sim.release();
        assertEquals(FlailSim.Mode.LAUNCH, sim.mode);
    }

    @Test
    void touchesTestsSphereAgainstBox() {
        double[] box = {0, 0, 0, 1, 2, 1};
        assertTrue(FlailSim.touches(new WhipVec(1.2, 1, 0.5), 0.3, box));
        assertTrue(!FlailSim.touches(new WhipVec(1.5, 1, 0.5), 0.3, box));
    }
}
