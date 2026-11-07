package com.fundamentum.client;

/**
 * The bits of 26.x's {@code net.minecraft.util.ARGB} that Fundamentum uses, for 1.21.1 (which has only
 * {@code FastColor.ARGB32}). {@link #srgbLerp} blends the channels straight, not in linear light.
 */
public final class Argb {
    public static int alpha(int c) {
        return c >>> 24;
    }

    public static int red(int c) {
        return c >> 16 & 255;
    }

    public static int green(int c) {
        return c >> 8 & 255;
    }

    public static int blue(int c) {
        return c & 255;
    }

    public static int color(int a, int r, int g, int b) {
        return (a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | b & 255;
    }

    public static int color(int r, int g, int b) {
        return color(255, r, g, b);
    }

    public static int multiply(int x, int y) {
        return color(alpha(x) * alpha(y) / 255, red(x) * red(y) / 255, green(x) * green(y) / 255, blue(x) * blue(y) / 255);
    }

    public static int srgbLerp(float t, int from, int to) {
        return color(lerp(t, alpha(from), alpha(to)), lerp(t, red(from), red(to)), lerp(t, green(from), green(to)), lerp(t, blue(from), blue(to)));
    }

    public static int scaleRGB(int c, float k) {
        return color(alpha(c), Math.min(255, (int) (red(c) * k)), Math.min(255, (int) (green(c) * k)), Math.min(255, (int) (blue(c) * k)));
    }

    public static int opaque(int c) {
        return c | 0xFF000000;
    }

    public static int color(float a, int rgb) {
        return (Math.round(a * 255) & 255) << 24 | rgb & 0xFFFFFF;
    }

    /** NativeImage on 1.21.1 hands out ABGR; this swaps it to ARGB. */
    public static int fromAbgr(int abgr) {
        return abgr & 0xFF00FF00 | (abgr & 255) << 16 | abgr >> 16 & 255;
    }

    private static int lerp(float t, int a, int b) {
        return Math.round(a + (b - a) * t);
    }

    private Argb() {}
}
