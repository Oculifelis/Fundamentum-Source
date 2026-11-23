package com.fundamentum.sky;

/** The Psychedelic Moon's rules with no game behind them, so unit tests can check them ({@link PsychedelicMoon}). */
public final class PsychedelicRules {
    private PsychedelicRules() { }

    public static final String ID = "fundamentum:psychedelic";

    /** The config default: off when released, on in dev runs; a {@code fundamentum.psychedelic} property wins. */
    public static boolean defaultOn(boolean production, String property) {
        return property != null ? Boolean.parseBoolean(property) : !production;
    }

    /** Tonight's chance for a moon with the switch on or off: the Psychedelic Moon's drops to 0 when off. */
    public static double chance(String moon, double chance, boolean enabled) {
        return ID.equals(moon) && !enabled ? 0.0 : chance;
    }
}
