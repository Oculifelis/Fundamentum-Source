package com.fundamentum.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The cloth chain behind Cloth (1.25.3), stepped headless with vanilla-cape-like inputs. */
class ClothSimTest {
    static ClothSim.Input idle(int age) {
        return new ClothSim.Input(0, 0, 0, 0, 0, false, 0, age);
    }

    static ClothSim.Input sprint(int age) {
        return new ClothSim.Input(90, 0, 4, 0, 0, false, 0.28F, age);
    }

    static ClothSim run(ClothSim sim, int from, int ticks, java.util.function.IntFunction<ClothSim.Input> in) {
        for (int t = from; t < from + ticks; t++) {
            sim.advanceTo(t, in.apply(t));
        }
        return sim;
    }

    @Test
    void idleHangsNearTheBackAndDrifts() {
        ClothSim sim = run(new ClothSim(4), 0, 200, ClothSimTest::idle);
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int t = 200; t < 400; t++) {
            sim.advanceTo(t, idle(t));
            for (int i = 0; i < 4; i++) {
                float p = sim.pitch(i, 1);
                assertTrue(p >= ClothSim.MIN && p < 25, "idle segment " + i + " at " + p);
            }
            min = Math.min(min, sim.pitch(3, 1));
            max = Math.max(max, sim.pitch(3, 1));
        }
        assertTrue(max - min > 1.5F, "the hem drifts while idle (" + min + ".." + max + ")");
    }

    @Test
    void sprintingTrailsAndTheChainLags() {
        ClothSim sim = run(new ClothSim(4), 0, 40, ClothSimTest::idle);
        float idleTop = sim.pitch(0, 1);
        sim.advanceTo(40, sprint(40));
        sim.advanceTo(41, sprint(41));
        assertTrue(sim.pitch(0, 1) > sim.pitch(3, 1) + 5, "the top swings out before the hem: " + sim.pitch(0, 1) + " vs " + sim.pitch(3, 1));
        run(sim, 42, 60, ClothSimTest::sprint);
        assertTrue(sim.pitch(0, 1) > idleTop + 30, "sprinting lifts the cape (" + sim.pitch(0, 1) + ")");
        assertTrue(sim.pitch(3, 1) > 40, "and the hem trails (" + sim.pitch(3, 1) + ")");
    }

    @Test
    void fallingLiftsAndGlidingStreams() {
        ClothSim fall = run(new ClothSim(3), 0, 40, t -> new ClothSim.Input(0, 0, 20, 0.8F, 0, false, 0, t));
        assertTrue(fall.pitch(0, 1) > 50, "falling fast lifts it (" + fall.pitch(0, 1) + ")");
        ClothSim glide = run(new ClothSim(3), 0, 60, t -> new ClothSim.Input(0, 0, 0, 0.1F, 1, false, 1.5F, t));
        assertTrue(glide.pitch(0, 1) > 65, "an elytra glide streams it out (" + glide.pitch(0, 1) + ")");
    }

    @Test
    void crouchingNeverSinksIntoTheBody() {
        ClothSim sim = run(new ClothSim(4), 0, 300, t -> new ClothSim.Input(0, 0, -6, 0, 0, true, 0.05F, t));
        for (int i = 0; i < 4; i++) {
            assertTrue(sim.pitch(i, 0.5F) >= ClothSim.CROUCH - 0.001F, "crouching segment " + i + " at " + sim.pitch(i, 0.5F));
        }
    }

    @Test
    void steppedOncePerTickHoweverOftenDrawn() {
        ClothSim a = run(new ClothSim(4), 0, 50, ClothSimTest::sprint);
        ClothSim b = new ClothSim(4);
        for (int t = 0; t < 50; t++) {
            for (int frame = 0; frame < 3; frame++) {
                b.advanceTo(t, sprint(t));
            }
        }
        for (int i = 0; i < 4; i++) {
            assertEquals(a.pitch(i, 1), b.pitch(i, 1), 1e-5);
        }
    }
}
