package com.fundamentum.thrown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fundamentum.whip.WhipVec;
import org.junit.jupiter.api.Test;

/**
 * The flight state machine, run headless: a thrower at the origin throws along +z; positions are stepped by the
 * velocity each tick returns, as the game does.
 */
class ThrownFlightTest {
    static final WhipVec HOME = new WhipVec(0, 1.6, 0);

    /** Flies a returning throw with no creatures or blocks in the way; returns the ticks to the catch, or -1. */
    static int flyHome(ThrownFlight f, double[] furthest) {
        WhipVec pos = HOME;
        WhipVec v = new WhipVec(0, 0, f.stats().throwSpeed());
        for (int t = 0; t < 1000; t++) {
            if (f.caught(pos, HOME)) {
                return t;
            }
            v = f.tick(pos, v, HOME);
            pos = pos.add(v);
            furthest[0] = Math.max(furthest[0], pos.z());
        }
        return -1;
    }

    @Test
    void boomerangFliesItsRangeAndComesHome() {
        ThrownFlight f = new ThrownFlight(ThrownStats.BOOMERANG);
        double[] far = {0};
        int ticks = flyHome(f, far);
        assertTrue(ticks > 0, "caught");
        assertEquals(ThrownStats.Returns.DEFAULT.range(), far[0], ThrownStats.BOOMERANG.throwSpeed() + 0.01);
        assertTrue(f.returning());
        assertFalse(f.expired());
    }

    @Test
    void fasterAndFurtherIsStillCaught() {
        ThrownStats s = ThrownStats.BOOMERANG.withJson(com.google.gson.JsonParser.parseString(
            "{\"throw_speed\": 2.2, \"returns\": {\"range\": 30, \"return_speed\": 2.6, \"return_accel\": 0.15}}").getAsJsonObject());
        ThrownFlight f = new ThrownFlight(s);
        double[] far = {0};
        int ticks = flyHome(f, far);
        assertTrue(ticks > 0 && ticks < s.maxTicks(), "caught in time: " + ticks);
        assertTrue(far[0] >= 30 && far[0] < 33, "flew " + far[0]);
    }

    @Test
    void pierceTurnsABoomerangHome() {
        ThrownFlight f = new ThrownFlight(ThrownStats.BOOMERANG.withJson(com.google.gson.JsonParser.parseString("{\"pierce\": 1}").getAsJsonObject()));
        assertEquals(ThrownFlight.HitResult.PASS, f.onHit());
        assertEquals(ThrownFlight.HitResult.TURN_HOME, f.onHit());
        assertTrue(f.returning());
        // home, hits change nothing
        assertEquals(ThrownFlight.HitResult.PASS, f.onHit());
        assertTrue(f.canHit());
    }

    @Test
    void ricochetUsesABounceThenAPierce() {
        ThrownStats s = new ThrownStats(1.5, 0, 0, 4, 1, false, false, false, 200, new ThrownStats.Returns(16, 1.6, 0.08, false, 1));
        ThrownFlight f = new ThrownFlight(s);
        assertEquals(ThrownFlight.HitResult.RICOCHET, f.onHit());
        f.ricochet(5);
        assertEquals(0, f.bouncesLeft());
        assertEquals(ThrownFlight.HitResult.TURN_HOME, f.onHit());
        assertFalse(f.canHit(), "hits_on_return false");
    }

    @Test
    void ricochetWithNoEnemyFallsBackToPierce() {
        ThrownStats s = new ThrownStats(1.5, 0, 0, 4, 1, false, false, false, 200, new ThrownStats.Returns(16, 1.6, 0.08, true, 1));
        ThrownFlight f = new ThrownFlight(s);
        assertEquals(ThrownFlight.HitResult.RICOCHET, f.onHit());
        assertEquals(ThrownFlight.HitResult.TURN_HOME, f.noRicochetTarget());
    }

    @Test
    void boomerangBlocks() {
        ThrownStats s = new ThrownStats(1.5, 0, -1, 4, 1, false, false, false, 200, new ThrownStats.Returns(16, 1.6, 0.08, true, 1));
        ThrownFlight f = new ThrownFlight(s);
        assertEquals(ThrownFlight.BlockResult.GLANCE, f.onBlock());
        assertEquals(ThrownFlight.BlockResult.TURN_HOME, f.onBlock());
        assertEquals(ThrownFlight.BlockResult.NONE, f.onBlock());
    }

    @Test
    void knifeFallsWithGravityAndStopsAtItsPierce() {
        ThrownFlight f = new ThrownFlight(ThrownStats.DEFAULT);
        WhipVec v = new WhipVec(0, 0, 1.5);
        WhipVec next = f.tick(HOME, v, HOME);
        assertEquals(-ThrownStats.DEFAULT.gravity(), next.y(), 1e-9);
        assertEquals(1.5, next.z(), 1e-9);
        assertTrue(f.canHit());
        assertEquals(ThrownFlight.HitResult.FALL, f.onHit());
        assertEquals(ThrownFlight.Phase.FALLING, f.phase());
        assertFalse(f.canHit());
        assertEquals(ThrownFlight.BlockResult.STICK, f.onBlock());
        assertTrue(f.stuck());
        assertEquals(WhipVec.ZERO, f.tick(HOME, v, HOME));
        assertFalse(new ThrownFlight(ThrownStats.DEFAULT.withJson(new com.google.gson.JsonObject())).expired());
        f.unstick();
        assertEquals(ThrownFlight.Phase.FALLING, f.phase());
    }

    @Test
    void stuckThrowsNeverExpire() {
        ThrownFlight f = new ThrownFlight(ThrownStats.DEFAULT);
        f.onBlock();
        for (int i = 0; i < 1000; i++) {
            f.tick(HOME, WhipVec.ZERO, HOME);
        }
        assertFalse(f.expired());
    }

    @Test
    void nonStickingThrowDropsAndBreakingThrowBreaks() {
        com.google.gson.JsonObject drop = new com.google.gson.JsonObject();
        drop.addProperty("sticks_in_target", false);
        assertEquals(ThrownFlight.BlockResult.DROP, new ThrownFlight(ThrownStats.DEFAULT.withJson(drop)).onBlock());
        com.google.gson.JsonObject br = new com.google.gson.JsonObject();
        br.addProperty("breaks_on_hit", true);
        ThrownStats molotov = ThrownStats.DEFAULT.withJson(br);
        assertEquals(ThrownFlight.BlockResult.BREAK, new ThrownFlight(molotov).onBlock());
        assertEquals(ThrownFlight.HitResult.BREAK, new ThrownFlight(molotov).onHit());
    }

    @Test
    void unlimitedPierceNeverStops() {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("pierce", -1);
        ThrownFlight f = new ThrownFlight(ThrownStats.DEFAULT.withJson(o));
        for (int i = 0; i < 50; i++) {
            assertEquals(ThrownFlight.HitResult.PASS, f.onHit());
        }
    }

    @Test
    void restoreAndParse() {
        ThrownFlight f = new ThrownFlight(ThrownStats.BOOMERANG);
        f.restore("RETURNING", 7, 1, 0, 33, 4);
        assertTrue(f.returning());
        assertEquals(33, f.age());
        assertEquals(ThrownFlight.Phase.OUTBOUND, ThrownFlight.parse("nonsense"));
        assertEquals(ThrownFlight.Phase.STUCK, ThrownFlight.parse("STUCK"));
    }

    @Test
    void reflectMirrorsOffAFace() {
        WhipVec r = ThrownFlight.reflect(new WhipVec(1, -1, 0), new WhipVec(0, 1, 0));
        assertEquals(new WhipVec(1, 1, 0), r);
    }
}
