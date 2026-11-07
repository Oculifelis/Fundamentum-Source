package com.fundamentum.element;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ElementMathTest {
    @Test
    void auraDecaysPerSecondAndNeverBelowZero() {
        assertEquals(1f, ElementMath.decayed(1f, 0f, 400), 1e-6);
        assertEquals(0.5f, ElementMath.decayed(1f, 0.5f, 20), 1e-6);
        assertEquals(0f, ElementMath.decayed(1f, 0.5f, 200), 1e-6);
        assertEquals(1f, ElementMath.decayed(1f, 0.5f, -5), 1e-6);
    }

    @Test
    void stacksCapAtMax() {
        assertEquals(2, ElementMath.stacked(1, 3));
        assertEquals(3, ElementMath.stacked(3, 3));
        assertEquals(1, ElementMath.stacked(1, 0));
        assertEquals(2f, ElementMath.stackedStrength(1f, 1f, 3), 1e-6);
        assertEquals(3f, ElementMath.stackedStrength(2.5f, 1f, 3), 1e-6);
        assertEquals(1f, ElementMath.stackedStrength(1f, 1f, 1), 1e-6);
    }

    @Test
    void consumingOneStackKeepsTheRestProportional() {
        assertEquals(2f, ElementMath.afterConsumingOne(3f, 3), 1e-6);
        assertEquals(0f, ElementMath.afterConsumingOne(3f, 1), 1e-6);
    }

    @Test
    void affinitiesAddUpToAMultiplier() {
        assertEquals(1f, ElementMath.affinityMultiplier(0f), 1e-6);
        assertEquals(0.5f, ElementMath.affinityMultiplier(0.5f), 1e-6);
        assertEquals(2f, ElementMath.affinityMultiplier(-1f), 1e-6);
        assertEquals(0f, ElementMath.affinityMultiplier(1f), 1e-6);
        assertEquals(0f, ElementMath.affinityMultiplier(3f), 1e-6);
    }

    @Test
    void reactionDamage() {
        assertEquals(4f, ElementMath.reactionDamage(2f, 2f, 0f, 0f, 1), 1e-6);
        assertEquals(5f, ElementMath.reactionDamage(2f, 1f, 1f, 1f, 2), 1e-6);
        assertEquals(0f, ElementMath.reactionDamage(2f, 1f, -10f, 0f, 1), 1e-6);
    }

    @Test
    void guardBlocksAReactionInsideItsOwnChain() {
        ElementMath.LoopGuard<String, String> guard = new ElementMath.LoopGuard<>();
        assertTrue(guard.allows("melt", "a", 1));
        guard.enter("melt", "a", 1);
        assertFalse(guard.allows("melt", "b", 1), "melt is running up the chain");
        assertTrue(guard.allows("overload", "b", 1), "another reaction may chain");
        guard.exit();
        assertEquals(0, guard.depth());
    }

    @Test
    void guardAllowsOneFiringPerTargetPerTick() {
        ElementMath.LoopGuard<String, String> guard = new ElementMath.LoopGuard<>();
        guard.enter("echo", "a", 5);
        guard.exit();
        assertFalse(guard.allows("echo", "a", 5));
        assertTrue(guard.allows("echo", "b", 5));
        assertTrue(guard.allows("echo", "a", 6), "a new tick clears it");
    }

    @Test
    void guardStopsDeepChains() {
        ElementMath.LoopGuard<String, String> guard = new ElementMath.LoopGuard<>();
        for (int i = 0; i < ElementMath.LoopGuard.MAX_DEPTH; i++) {
            assertTrue(guard.allows("r" + i, "t" + i, 1));
            guard.enter("r" + i, "t" + i, 1);
        }
        assertFalse(guard.allows("another", "x", 1));
        for (int i = 0; i < ElementMath.LoopGuard.MAX_DEPTH; i++) {
            guard.exit();
        }
        assertTrue(guard.allows("another", "x", 1));
    }
}
