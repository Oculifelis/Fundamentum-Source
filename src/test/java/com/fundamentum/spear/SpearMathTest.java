package com.fundamentum.spear;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SpearMathTest {
    @Test
    void reachAddsTheBonus() {
        assertEquals(6.5F, SpearMath.reach(4.5F, 2.0F, 2.0), 1e-6);
        assertEquals(4.5F, SpearMath.reach(4.5F, 2.0F, 0.0), 1e-6);
    }

    @Test
    void reachNeverFallsBelowTheMinimum() {
        assertEquals(2.0F, SpearMath.reach(4.5F, 2.0F, -5.0), 1e-6);
    }

    @Test
    void reachIsCapped() {
        assertEquals(64.0F, SpearMath.reach(4.5F, 2.0F, 100.0), 1e-6);
    }

    @Test
    void chargeScales() {
        assertEquals(15.0F, SpearMath.charge(10.0F, 1.5), 1e-6);
        assertEquals(10.0F, SpearMath.charge(10.0F, 1.0), 1e-6);
        assertEquals(0.0F, SpearMath.charge(10.0F, -1.0), 1e-6);
    }

    @Test
    void throwSpeedGrowsWithTheDraw() {
        assertEquals(1.25F, SpearMath.throwSpeed(5, 10, 2.5), 1e-6);
        assertEquals(2.5F, SpearMath.throwSpeed(30, 10, 2.5), 1e-6);
        assertEquals(2.5F, SpearMath.throwSpeed(0, 0, 2.5), 1e-6);
    }
}
