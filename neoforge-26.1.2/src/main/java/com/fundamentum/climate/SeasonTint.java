package com.fundamentum.climate;

/**
 * The client's grass and leaf tint: the biome's own colour blended toward the season's by the season's {@code tint}
 * share. Leaves the colour alone with no season, no colour for that layer, or the tint config off.
 */
public final class SeasonTint {
    private SeasonTint() { }

    public static int grass(int colour) {
        Seasons.Sync s = Seasons.clientSync();
        return s.grass() < 0 ? colour : blend(colour, s.grass(), s.tint());
    }

    public static int foliage(int colour) {
        Seasons.Sync s = Seasons.clientSync();
        return s.foliage() < 0 ? colour : blend(colour, s.foliage(), s.tint());
    }

    /** Mixes two 0xRRGGBB colours, {@code share} of the way from {@code from} to {@code to}. Keeps the top byte of {@code from}. */
    public static int blend(int from, int to, double share) {
        if (share <= 0) return from;
        double t = Math.min(1, share);
        int r = mix((from >> 16) & 255, (to >> 16) & 255, t);
        int g = mix((from >> 8) & 255, (to >> 8) & 255, t);
        int b = mix(from & 255, to & 255, t);
        return (from & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int mix(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }
}
