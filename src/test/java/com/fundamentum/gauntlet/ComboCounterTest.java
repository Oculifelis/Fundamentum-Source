package com.fundamentum.gauntlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ComboCounterTest {
    @Test
    void countsUpToTheFinisher() {
        ComboCounter c = new ComboCounter();
        assertEquals(1, c.punch(0, 20, 3));
        assertFalse(c.finisher(3));
        assertEquals(2, c.punch(10, 20, 3));
        assertEquals(3, c.punch(20, 20, 3));
        assertTrue(c.finisher(3));
    }

    @Test
    void startsOverAfterTheFinisher() {
        ComboCounter c = new ComboCounter();
        c.punch(0, 20, 2);
        c.punch(5, 20, 2);
        assertEquals(1, c.punch(10, 20, 2));
    }

    @Test
    void aSlowPunchStartsOver() {
        ComboCounter c = new ComboCounter();
        c.punch(0, 20, 4);
        c.punch(10, 20, 4);
        assertEquals(1, c.punch(31, 20, 4));
        assertEquals(2, c.punch(51, 20, 4));
    }

    @Test
    void resetStartsOver() {
        ComboCounter c = new ComboCounter();
        c.punch(0, 20, 4);
        c.punch(1, 20, 4);
        c.reset();
        assertEquals(0, c.step());
        assertEquals(1, c.punch(2, 20, 4));
    }

    @Test
    void lengthOneIsAlwaysAFinisher() {
        ComboCounter c = new ComboCounter();
        assertEquals(1, c.punch(0, 20, 1));
        assertTrue(c.finisher(1));
        assertEquals(1, c.punch(1, 20, 1));
        assertEquals(1, c.punch(2, 20, 0));
    }
}
