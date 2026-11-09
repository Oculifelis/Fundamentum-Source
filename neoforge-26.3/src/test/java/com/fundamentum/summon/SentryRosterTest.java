package com.fundamentum.summon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SentryRosterTest {
    @Test
    void placesWithinTheLimit() {
        SentryRoster<String> r = new SentryRoster<>();
        assertTrue(r.add("a", 1, 2).isEmpty());
        assertTrue(r.add("b", 1, 2).isEmpty());
        assertEquals(List.of("a", "b"), r.sentries());
        assertEquals(2.0, r.used());
    }

    @Test
    void oldestGoesFirstPastTheLimit() {
        SentryRoster<String> r = new SentryRoster<>();
        r.add("a", 1, 2);
        r.add("b", 1, 2);
        assertEquals(List.of("a"), r.add("c", 1, 2));
        assertEquals(List.of("b", "c"), r.sentries());
    }

    @Test
    void aBigSentryPushesOutSeveral() {
        SentryRoster<String> r = new SentryRoster<>();
        r.add("a", 1, 3);
        r.add("b", 1, 3);
        r.add("c", 1, 3);
        assertEquals(List.of("a", "b"), r.add("big", 2, 3));
        assertEquals(List.of("c", "big"), r.sentries());
    }

    @Test
    void tooBigIsNotPlaced() {
        SentryRoster<String> r = new SentryRoster<>();
        r.add("a", 1, 1);
        assertEquals(List.of("huge"), r.add("huge", 2, 1));
        assertEquals(List.of("a"), r.sentries());
    }

    @Test
    void aFallingLimitTrimsOldest() {
        SentryRoster<String> r = new SentryRoster<>();
        r.add("a", 1, 3);
        r.add("b", 1, 3);
        r.add("c", 1, 3);
        assertEquals(List.of("a", "b"), r.trim(1));
        assertEquals(List.of("c"), r.sentries());
        assertEquals(List.of("c"), r.trim(0));
        assertTrue(r.isEmpty());
    }

    @Test
    void zeroLimitPlacesNothing() {
        SentryRoster<String> r = new SentryRoster<>();
        assertEquals(List.of("a"), r.add("a", 1, 0));
        assertTrue(r.isEmpty());
    }
}
