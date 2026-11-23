package com.fundamentum.relic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** RelicSpin: the slow turn, easing round to the facing on a signal and back to turning when it ends, never jumping. */
class RelicSpinTest {
    private static final float NORTH = 180F;
    private static final float EAST = 270F;

    /** Ticks {@code n} times and returns the biggest change in speed and in angle seen in one tick. */
    private static float[] run(RelicSpin s, boolean powered, float facing, int n) {
        float dv = 0F;
        float da = 0F;
        for (int i = 0; i < n; i++) {
            float a = s.angle();
            float v = s.speed();
            s.tick(powered, facing, 1000L + i, RelicSpin.TURN_TICKS);
            dv = Math.max(dv, Math.abs(s.speed() - v));
            da = Math.max(da, Math.abs(RelicSpin.wrap(s.angle() - a)));
        }
        return new float[] {dv, da};
    }

    @Test
    void itStartsInStepWithUltimaRatio() {
        RelicSpin s = new RelicSpin();
        s.tick(false, NORTH, 450L, 900F);
        // UR: turn = -facing.toYRot() + 360 * (t % 900) / 900 -> -180 + 180 = 0
        assertEquals(0F, s.angle(), 1e-4F);
        assertEquals(0.4F, s.speed(), 1e-6F);
    }

    @Test
    void unpoweredItTurnsAtCruise() {
        RelicSpin s = new RelicSpin();
        s.tick(false, NORTH, 0L, 900F);
        float a = s.angle();
        run(s, false, NORTH, 100);
        assertEquals(RelicSpin.wrap(a + 40F), s.angle(), 1e-2F);
    }

    @Test
    void poweredFromTheStartItFacesAtOnce() {
        RelicSpin s = new RelicSpin();
        s.tick(true, EAST, 123L, 900F);
        assertTrue(s.settled(EAST));
    }

    @Test
    void aSignalEasesItRoundAndStopsItOnTheFacing() {
        for (long start = 0; start < 900; start += 37) {
            RelicSpin s = new RelicSpin();
            s.tick(false, EAST, start, 900F);
            run(s, false, EAST, 50);
            float[] d = run(s, true, EAST, 400);
            assertTrue(s.settled(EAST), "settled from start " + start + ": angle " + s.angle() + " speed " + s.speed());
            assertTrue(d[0] <= RelicSpin.ACCEL + 1e-5F, "speed changed by " + d[0]);
            assertTrue(d[1] <= RelicSpin.SEEK_MAX + 1e-4F, "angle jumped by " + d[1]);
            run(s, true, EAST, 50);
            assertTrue(s.settled(EAST), "and it stays there");
        }
    }

    @Test
    void whenTheSignalEndsItTurnsOnFromWhereItStands() {
        RelicSpin s = new RelicSpin();
        s.tick(true, NORTH, 0L, 900F);
        float rest = s.angle();
        s.tick(false, NORTH, 1L, 900F);
        assertEquals(RelicSpin.wrap(rest + RelicSpin.ACCEL), s.angle(), 1e-4F, "it starts slowly from its facing, not from the clock");
        float[] d = run(s, false, NORTH, 100);
        assertEquals(0.4F, s.speed(), 1e-6F, "and comes up to its cruise");
        assertTrue(d[0] <= RelicSpin.ACCEL + 1e-5F);
        assertFalse(s.settled(NORTH));
    }

    @Test
    void aSignalJustPastTheFacingTurnsItBackTheShortWay() {
        RelicSpin s = new RelicSpin();
        s.tick(true, NORTH, 0L, 900F);
        for (int i = 0; i < 40; i++) {
            s.tick(false, NORTH, i, 900F);
        }
        float past = RelicSpin.wrap(s.angle() - RelicSpin.rest(NORTH));
        assertTrue(past > 0F && past < 20F, "a little past its facing: " + past);
        float furthest = past;
        for (int i = 0; i < 400; i++) {
            s.tick(true, NORTH, i, 900F);
            furthest = Math.max(furthest, RelicSpin.wrap(s.angle() - RelicSpin.rest(NORTH)));
        }
        assertTrue(s.settled(NORTH));
        assertTrue(furthest < past + 3F, "it slowed and came back rather than going the long way round: " + furthest);
    }

    @Test
    void theDrawnAngleLerpsWithoutAJumpAcrossTheFold() {
        RelicSpin s = new RelicSpin();
        s.tick(false, 0F, 449L, 900F);
        for (int i = 0; i < 2000; i++) {
            s.tick(false, 0F, i, 900F);
            float a0 = s.angle(0F);
            float a1 = s.angle(1F);
            assertTrue(Math.abs(a1 - a0) < 1F, "lerp span " + (a1 - a0));
        }
    }

    @Test
    void wrapFoldsIntoAHalfTurnEachWay() {
        assertEquals(180F, RelicSpin.wrap(-180F), 1e-5F);
        assertEquals(-90F, RelicSpin.wrap(270F), 1e-5F);
        assertEquals(10F, RelicSpin.wrap(730F), 1e-5F);
    }
}
