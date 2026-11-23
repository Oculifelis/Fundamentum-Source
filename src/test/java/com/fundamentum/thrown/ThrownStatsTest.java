package com.fundamentum.thrown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** The thrown stats: data laid over code, the returns block, the thrower's stats, and the save round trip. */
class ThrownStatsTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void dataLaysOverOnlyItsFields() {
        ThrownStats s = ThrownStats.DEFAULT.withJson(json("{\"damage\": 7, \"pierce\": 2, \"consumable\": true}"));
        assertEquals(7, s.damage());
        assertEquals(2, s.pierce());
        assertTrue(s.consumable());
        assertEquals(ThrownStats.DEFAULT.throwSpeed(), s.throwSpeed());
        assertEquals(ThrownStats.DEFAULT.gravity(), s.gravity());
        assertTrue(s.sticksInTarget());
        assertNull(s.returns());
    }

    @Test
    void stepObjectsWorkOnTheValueBelow() {
        ThrownStats s = ThrownStats.BOOMERANG.withJson(json(
            "{\"throw_speed\": {\"multiply\": 2}, \"damage\": {\"add\": 3, \"max\": 6}, \"returns\": {\"range\": {\"add\": 4}}}"));
        assertEquals(3.0, s.throwSpeed(), 1e-9);
        assertEquals(6, s.damage(), 1e-9);
        assertEquals(20, s.returns().range(), 1e-9);
        assertEquals(ThrownStats.Returns.DEFAULT.returnSpeed(), s.returns().returnSpeed(), 1e-9);
    }

    @Test
    void returnsBlockAddsAndRemovesTheBoomerangPart() {
        ThrownStats knife = ThrownStats.DEFAULT;
        assertFalse(knife.returning());
        ThrownStats axe = knife.withJson(json("{\"returns\": {\"range\": 30, \"bounces\": 1}}"));
        assertTrue(axe.returning());
        assertEquals(30, axe.returns().range());
        assertEquals(1, axe.returns().bounces());
        assertEquals(ThrownStats.Returns.DEFAULT.returnSpeed(), axe.returns().returnSpeed());
        assertTrue(knife.withJson(json("{\"returns\": true}")).returning());
        assertFalse(axe.withJson(json("{\"returns\": false}")).returning());
    }

    @Test
    void clampsNonsense() {
        ThrownStats s = ThrownStats.DEFAULT.withJson(json("{\"throw_speed\": -4, \"pierce\": -9, \"max_ticks\": 1, \"gravity\": 5,"
            + " \"returns\": {\"range\": 0, \"bounces\": -3}}"));
        assertEquals(0.05, s.throwSpeed());
        assertEquals(ThrownStats.UNLIMITED, s.pierce());
        assertEquals(20, s.maxTicks());
        assertEquals(1, s.gravity());
        assertEquals(0.5, s.returns().range());
        assertEquals(0, s.returns().bounces());
    }

    @Test
    void thrownersStatsChangeTheThrow() {
        ThrownStats b = ThrownStats.BOOMERANG.withJson(json("{\"pierce\": 1}"));
        ThrownStats m = b.modified(2, 1, 1.5, 2, 1);
        assertEquals(3.0, m.throwSpeed(), 1e-9);
        assertEquals(2, m.pierce());
        assertEquals(24, m.returns().range(), 1e-9);
        assertEquals(3.2, m.returns().returnSpeed(), 1e-9);
        assertEquals(0.16, m.returns().returnAccel(), 1e-9);
        assertEquals(1, m.returns().bounces());
        // unlimited pierce stays unlimited
        assertEquals(ThrownStats.UNLIMITED, ThrownStats.BOOMERANG.modified(1, 3, 1, 1, 0).pierce());
        // a non-returning throw ignores the boomerang stats
        ThrownStats k = ThrownStats.DEFAULT.modified(1.5, 2, 9, 9, 9);
        assertNull(k.returns());
        assertEquals(2, k.pierce());
        assertEquals(ThrownStats.DEFAULT.maxTicks(), k.maxTicks());
    }

    @Test
    void aLongThrowGetsTimeToComeHome() {
        ThrownStats far = ThrownStats.BOOMERANG.modified(1, 0, 10, 0.5, 0);
        assertTrue(far.maxTicks() >= Math.ceil(3 * far.returns().range() / far.returns().returnSpeed()));
    }

    @Test
    void jsonRoundTrip() {
        ThrownStats s = new ThrownStats(2.1, 0.02, 3, 7, 1.5, false, true, true, 300, new ThrownStats.Returns(30, 2.4, 0.12, false, 2));
        assertEquals(s, ThrownStats.DEFAULT.withJson(s.toJson()));
        assertEquals(ThrownStats.DEFAULT, ThrownStats.BOOMERANG.withJson(ThrownStats.DEFAULT.toJson()));
        assertEquals(10.5, s.hitDamage(), 1e-9);
    }
}
