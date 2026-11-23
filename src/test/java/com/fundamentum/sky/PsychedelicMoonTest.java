package com.fundamentum.sky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The Psychedelic Moon's config default (off when released) and its switch. The roll over many nights and the data
 * are checked in game by {@link SkySelfTest} ({@code gradlew runServer -Pskytest}).
 */
class PsychedelicMoonTest {
    @Test
    void offWhenReleasedOnInDev() {
        assertFalse(PsychedelicRules.defaultOn(true, null), "the released jar must default to off");
        assertTrue(PsychedelicRules.defaultOn(false, null), "dev and test runs default to on");
        assertTrue(PsychedelicRules.defaultOn(true, "true"));
        assertFalse(PsychedelicRules.defaultOn(false, "false"));
    }

    @Test
    void switchOnlyTouchesThisMoon() {
        assertEquals(0.0, PsychedelicRules.chance(PsychedelicRules.ID, 0.03, false));
        assertEquals(0.03, PsychedelicRules.chance(PsychedelicRules.ID, 0.03, true));
        assertEquals(0.2, PsychedelicRules.chance("ostentum:harvest", 0.2, false));
    }
}
