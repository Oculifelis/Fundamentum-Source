package com.fundamentum.moon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** The moon listener guard, driven with a listener that throws on purpose between two that work. */
class ListenerGuardTest {
    record Line(String message, Throwable cause) {}

    /** A listener as Moons sees one: called with the moon id it shines under. */
    interface Hook {
        void call(String moon);
    }

    private final List<String> ran = new ArrayList<>();
    private final List<Line> log = new ArrayList<>();
    private final RuntimeException boom = new IllegalStateException("bad rise_message");
    private final Hook first = moon -> ran.add("first:" + moon);
    private final Hook broken = moon -> {
        ran.add("broken:" + moon);
        throw boom;
    };
    private final Hook last = moon -> ran.add("last:" + moon);
    private final List<Hook> hooks = new CopyOnWriteArrayList<>(List.of(first, broken, last));
    private final Map<Object, String> mods = Map.of(first, "alpha", broken, "badmod", last, "omega");
    private final ListenerGuard<Hook> guard = new ListenerGuard<>("moon", hooks, mods::get, (m, c) -> log.add(new Line(m, c)));

    private void rise(String moon) {
        guard.fire("rise", moon, h -> h.call(moon));
    }

    @Test
    void oneThrowingListenerDoesNotStopTheTickOrTheOthers() {
        rise("ostentum:harvest"); // throws out of here if the guard does not hold, as the 14:20 crash did

        assertEquals(List.of("first:ostentum:harvest", "broken:ostentum:harvest", "last:ostentum:harvest"), ran);
    }

    @Test
    void theErrorNamesTheModTheMoonAndCarriesTheTrace() {
        rise("ostentum:harvest");

        assertEquals(1, log.size());
        Line e = log.get(0);
        assertTrue(e.message().contains("mod 'badmod'"), e.message());
        assertTrue(e.message().contains("moon rise listener"), e.message());
        assertTrue(e.message().contains("ostentum:harvest"), e.message());
        assertFalse(e.message().contains("alpha") || e.message().contains("omega"), e.message());
        assertSame(boom, e.cause());
    }

    @Test
    void aListenerThatKeepsThrowingIsDroppedOnceAndNotCalledAgain() {
        for (int i = 0; i < ListenerGuard.LIMIT; i++) {
            rise("night" + i);
        }
        assertEquals(List.of(first, last), hooks);
        assertEquals(ListenerGuard.LIMIT + 1, log.size());
        Line drop = log.get(ListenerGuard.LIMIT);
        assertTrue(drop.message().contains("dropped") && drop.message().contains("'badmod'"), drop.message());
        assertNull(drop.cause());

        ran.clear();
        log.clear();
        rise("after");
        assertEquals(List.of("first:after", "last:after"), ran);
        assertTrue(log.isEmpty());
    }

    @Test
    void aListenerThatThrowsOnceIsKept() {
        boolean[] fail = {true};
        Hook flaky = moon -> {
            if (fail[0]) {
                fail[0] = false;
                throw boom;
            }
            ran.add("flaky:" + moon);
        };
        hooks.add(flaky);
        rise("a");
        rise("b");

        assertTrue(hooks.contains(flaky));
        assertTrue(ran.contains("flaky:b"));
    }
}
