package com.fundamentum.client;

/**
 * The client's safety net for change-driven caches. HUD text, meter rows and the multiblock ghost are rebuilt when the
 * value behind them changes; {@link #epoch()} also moves every {@code refresh.safetyRecheck} seconds (0 never), so a
 * cache keyed on it rebuilds now and then even if a change was missed. Ticked from the client tick.
 */
public final class ClientRefresh {
    private static int epoch;
    private static int ticks;

    private ClientRefresh() {
    }

    /** Ticks between safety rechecks, 0 for none. */
    public static int period() {
        try {
            return ItemHudConfig.SAFETY_RECHECK.get() * 20;
        } catch (RuntimeException notLoaded) {
            return 100;
        }
    }

    /** Goes up once per safety period. */
    public static int epoch() {
        return epoch;
    }

    /** Called once per client tick. */
    public static void tick() {
        int period = period();
        if (period > 0 && ++ticks >= period) {
            ticks = 0;
            epoch++;
        }
    }
}
