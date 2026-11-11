package com.fundamentum.scythe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fundamentum.whip.WhipVec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Scythe files: which moves combine, what each block reads, and the moves' numbers. */
class ScytheSpecTest {
    static ScytheSpec parse(String s) {
        return ScytheSpec.parse(JsonParser.parseString(s).getAsJsonObject());
    }

    static String error(String s) {
        return assertThrows(IllegalArgumentException.class, () -> parse(s)).getMessage();
    }

    @Test
    void whirlAndBackswingCanShareAScythe() {
        ScytheSpec s = parse("{\"moves\":{\"whirl\":{},\"backswing\":{}}}");
        assertNotNull(s.whirl);
        assertNotNull(s.backswing);
        assertEquals("whirl", s.moveOn("use"));
    }

    @Test
    void twoMovesOnOneInputAreRejected() {
        String m = error("{\"moves\":{\"whirl\":{},\"reap_pull\":{}}}");
        assertTrue(m.contains("are both on input \"use\""), m);
        assertTrue(m.contains("\"input\": \"sneak_use\""), m);
        String n = error("{\"moves\":{\"reap_pull\":{\"input\":\"sneak_use\"},\"crescent_wave\":{}}}");
        assertTrue(n.contains("on input \"sneak_use\""), n);
        // Moving one fixes it.
        ScytheSpec s = parse("{\"moves\":{\"whirl\":{},\"reap_pull\":{\"input\":\"sneak_use\"}}}");
        assertEquals("whirl", s.moveOn("use"));
        assertEquals("reap_pull", s.moveOn("sneak_use"));
    }

    @Test
    void otherMistakesAreNamed() {
        assertTrue(error("{\"moves\":{}}").contains("needs at least one move"));
        assertTrue(error("{}").contains("needs at least one move"));
        assertTrue(error("{\"moves\":{\"spin\":{}}}").contains("unknown move \"spin\""));
        assertTrue(error("{\"moves\":{\"whirl\":{\"radious\":3}}}").contains("\"whirl\": unknown field \"radious\""));
        assertTrue(error("{\"moves\":{\"whirl\":{\"input\":\"attack\"}}}").contains("must be use or sneak_use"));
        assertTrue(error("{\"moves\":{\"whirl\":{\"min_damage\":2,\"max_damage\":1}}}").contains("above \"max_damage\""));
        assertTrue(error("{\"moves\":{\"sweet_spot\":{\"min\":3,\"max\":2}}}").contains("must be below \"max\""));
        assertTrue(error("{\"moves\":{\"reap_pull\":{\"range\":40}}}").contains("\"reap_pull.range\" is 40.0, must be 1.0 to 16.0"));
        assertTrue(error("{\"moves\":{\"harvest_souls\":5}}").contains("must be an object"));
        assertTrue(error("{\"moves\":{\"sweet_spot\":{}},\"speed\":2}").contains("unknown field \"speed\""));
    }

    @Test
    void defaultsFillEveryMove() {
        ScytheSpec s = parse("{\"reach\":3.5,\"moves\":{\"sweet_spot\":{},\"backswing\":{},\"reap_pull\":{},\"crescent_wave\":{},\"harvest_souls\":{}}}");
        assertEquals(List.of("backswing", "crescent_wave", "harvest_souls", "reap_pull", "sweet_spot"), s.moves());
        assertEquals(3.5, s.sweetSpot.max());
        assertEquals(3.5 * 0.7, s.sweetSpot.min(), 1.0E-9);
        assertEquals(12, s.backswing.window());
        assertEquals("reap_pull", s.moveOn("use"));
        assertEquals("crescent_wave", s.moveOn("sneak_use"));
        assertEquals(5, s.harvestSouls.maxSouls());
        assertNull(s.whirl);
        assertFalse(s.has("whirl"));
    }

    @Test
    void theShippedExampleParses() throws Exception {
        java.nio.file.Path f = java.nio.file.Path.of("src/flailtest/resources/data/fundamentum/fundamentum/scythes/example_scythe.json");
        JsonObject json = JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonObject();
        ScytheSpec s = ScytheSpec.parse(json);
        assertNotNull(s.whirl);
        java.nio.file.Path bad = java.nio.file.Path.of("src/flailtest/resources/data/fundamentum/fundamentum/scythes/bad_scythe.json");
        JsonObject b = JsonParser.parseString(java.nio.file.Files.readString(bad)).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> ScytheSpec.parse(b));
    }

    @Test
    void sweetSpotMath() {
        ScytheSpec.SweetSpot s = new ScytheSpec.SweetSpot(2.0, 3.0, 1.5, 0.75, "");
        assertEquals(0.75, ScytheMath.sweetSpot(s, 1.0));
        assertEquals(1.5, ScytheMath.sweetSpot(s, 2.5));
        assertEquals(1.5, ScytheMath.sweetSpot(s, 3.2));
        assertEquals(1.0, ScytheMath.sweetSpot(s, 4.0));
    }

    @Test
    void whirlBuilds() {
        ScytheSpec.Whirl w = parse("{\"moves\":{\"whirl\":{\"build_ticks\":20,\"min_damage\":0.2,\"max_damage\":1.2,\"hit_every\":4}}}").whirl;
        assertEquals(0.2, ScytheMath.whirl(w, 0), 1.0E-9);
        assertEquals(0.7, ScytheMath.whirl(w, 10), 1.0E-9);
        assertEquals(1.2, ScytheMath.whirl(w, 100), 1.0E-9);
        assertFalse(ScytheMath.whirlHits(w, 0));
        assertTrue(ScytheMath.whirlHits(w, 8));
        assertFalse(ScytheMath.whirlHits(w, 9));
    }

    @Test
    void arcsAndPulls() {
        WhipVec eye = new WhipVec(0, 1.6, 0), look = new WhipVec(0, 0, 1);
        assertTrue(ScytheMath.inArc(eye, look, new WhipVec(1, 1, 3), 5, 100));
        assertFalse(ScytheMath.inArc(eye, look, new WhipVec(4, 1, 1), 5, 100));
        assertFalse(ScytheMath.inArc(eye, look, new WhipVec(0, 1, 6), 5, 100));
        assertTrue(ScytheMath.inArc(eye, look, new WhipVec(0, 1, -3), 5, 360));
        WhipVec v = ScytheMath.pull(new WhipVec(0, 0, 0), new WhipVec(0, 0, 5), 0.9, 5);
        assertTrue(v.z() < 0 && v.y() > 0, "a pull drags in and lifts: " + v);
        assertEquals(0.0, ScytheMath.pull(new WhipVec(0, 0, 0), new WhipVec(0, 0, 0.5), 0.9, 5).z(), 1.0E-9);
        for (int d = 1; d < 16; d++) {
            WhipVec p = ScytheMath.pull(new WhipVec(0, 0, 0), new WhipVec(0, 0, d), 4, 16);
            assertTrue(-p.z() <= d, "a pull never flings past the puller");
        }
    }

    @Test
    void wavesRunTheirRange() {
        ScytheSpec.CrescentWave w = parse("{\"moves\":{\"crescent_wave\":{\"range\":6,\"speed\":2}}}").crescentWave;
        WhipVec start = new WhipVec(0, 1, 0), dir = new WhipVec(0, 0, 2);
        assertEquals(4.0, ScytheMath.wave(w, start, dir, 2).z(), 1.0E-9);
        assertNotNull(ScytheMath.wave(w, start, dir, 3));
        assertNull(ScytheMath.wave(w, start, dir, 4));
    }

    @Test
    void soulsEmpower() {
        ScytheSpec.HarvestSouls h = new ScytheSpec.HarvestSouls(5, 200, 0.1, 1);
        assertEquals(1.0, ScytheMath.souls(h, 0));
        assertEquals(1.3, ScytheMath.souls(h, 3), 1.0E-9);
        assertEquals(1.5, ScytheMath.souls(h, 50), 1.0E-9);
    }
}
