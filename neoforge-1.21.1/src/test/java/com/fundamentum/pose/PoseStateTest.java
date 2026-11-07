package com.fundamentum.pose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** PosePlayer's per-entity playback state: only legal modes, and nothing carried over to the next item. */
class PoseStateTest {
    private static PoseClip clip(String name) {
        return new PoseClip(name, new JsonObject());
    }

    @Test
    void aShotEndsACharge() {
        PosePlayer.State st = new PosePlayer.State();
        st.hold("ostentum:bow", Float.NaN, 0L);
        st.play(PosePlayer.Mode.CHARGE, clip("charge"), new PosePlayer.Sample(), 10.0F, 1.0F);
        assertEquals(PosePlayer.Mode.CHARGE, st.mode);
        st.play(PosePlayer.Mode.FIRE, clip("fire"), new PosePlayer.Sample(), 20.0F, 1.0F);
        assertEquals(PosePlayer.Mode.FIRE, st.mode, "charging and firing never both hold");
        st.release(new PosePlayer.Sample(), 30.0F, 3.0F);
        assertEquals(PosePlayer.Mode.REST, st.mode);
        assertNull(st.clip);
    }

    @Test
    void aNewItemStartsOver() {
        PosePlayer.State st = new PosePlayer.State();
        st.hold("ostentum:saber", Float.NaN, 0L);
        st.combo = 2;
        st.lastCombo = 500.0F;
        st.play(PosePlayer.Mode.CHARGE, clip("charge"), new PosePlayer.Sample(), 500.0F, 0.5F);
        st.hold("ostentum:glaive", 480.0F, 77L);
        assertEquals("ostentum:glaive", st.item);
        assertEquals(PosePlayer.Mode.REST, st.mode, "a charge does not carry to the next weapon");
        assertEquals(0, st.combo, "nor does a combo");
        assertEquals(-1000.0F, st.lastCombo);
        assertEquals(1.0F, st.speed);
        assertNull(st.clip);
        assertNull(st.from);
        assertEquals(480.0F, st.lastFire, "its last shot is remembered, so it is not replayed");
        assertEquals(77L, st.lastReload);
    }

    @Test
    void noShotYetIsOlderThanAnyShot() {
        PosePlayer.State st = new PosePlayer.State();
        st.hold("ostentum:bow", Float.NaN, 0L);
        assertEquals(PosePlayer.NO_FIRE, st.lastFire);
        assertTrue(0.0F - st.lastFire > 0.5F, "the first shot counts as new");
    }
}
