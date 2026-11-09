package com.fundamentum.gauntlet;

/**
 * Where a puncher stands in a gauntlet combo. Each landed punch moves one step along; a punch later than the combo
 * window after the last one starts over at 1, and the punch after the finisher starts over too. Plain code with no game
 * classes, so it is unit tested on its own.
 */
public final class ComboCounter {
    private int step;
    private long last = Long.MIN_VALUE;

    /**
     * Counts a landed punch at game time {@code now} and returns its place in the combo, 1 to {@code length}; the punch
     * that returns {@code length} is the finisher.
     */
    public int punch(long now, int window, int length) {
        int len = Math.max(1, length);
        if (step >= len || last == Long.MIN_VALUE || now - last > window) {
            step = 0;
        }
        step++;
        last = now;
        return step;
    }

    /** True when the last punch counted was the finisher of a combo this long. */
    public boolean finisher(int length) {
        return step >= Math.max(1, length);
    }

    /** The step the last punch reached (0 before any). */
    public int step() {
        return step;
    }

    /** Starts over (a weak punch, a weapon change). */
    public void reset() {
        step = 0;
        last = Long.MIN_VALUE;
    }
}
