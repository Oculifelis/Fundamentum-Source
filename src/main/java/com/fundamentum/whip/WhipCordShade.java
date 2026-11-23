package com.fundamentum.whip;

import com.fundamentum.client.Argb;
import net.minecraft.util.Mth;

/**
 * The cord animations of {@code fundamentum:core/whip_cord} done on the vertex colours instead (1.25.3), for while a
 * shader pack draws the cords with its own program (see {@link com.fundamentum.client.Emissive}): energy bands run to
 * the tip, lightning flickers and jags, fire licks and flickers, water shimmers, wind streaks. {@code v} runs along the
 * cord (0 handle, 1 tip), {@code t} is seconds of any steady clock. Coarser than the shader (per cord segment, not per
 * pixel) but it moves and glows the same colours.
 */
final class WhipCordShade {
    private WhipCordShade() { }

    static double hash(double x, double y) {
        double s = Math.sin(x * 127.1 + y * 311.7) * 43758.5453;
        return s - Math.floor(s);
    }

    static double noise(double x, double y) {
        double ix = Math.floor(x), iy = Math.floor(y), fx = x - ix, fy = y - iy;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        double a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        return Mth.lerp(fy, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d));
    }

    /** The colour of the cord at {@code v}: {@code argb} animated as the shader would. */
    static int shade(WhipCordFx fx, int argb, float v, double t) {
        float a = Argb.alpha(argb) / 255.0F, r = Argb.red(argb) / 255.0F, g = Argb.green(argb) / 255.0F, b = Argb.blue(argb) / 255.0F;
        switch (fx) {
            case ENERGY -> {
                float band = (float) (0.5 + 0.5 * Math.sin(v * 22.0 - t * 10.0));
                float k = 0.55F + band * 0.75F;
                r = r * k + 0.25F * band;
                g = g * k + 0.25F * band;
                b = b * k + 0.25F * band;
                a *= 0.6F + 0.4F * band;
            }
            case LIGHTNING -> {
                double frame = Math.floor(t * 18.0);
                float flicker = (float) (0.65 + 0.6 * (hash(frame, 7.0) > 0.3 ? 1 : 0));
                float spark = (float) Math.max(0, noise(v * 9.0, frame) - 0.45) * 1.6F;
                r = Mth.lerp(0.55F + spark * 0.3F, r, 1.0F) * flicker;
                g = Mth.lerp(0.55F + spark * 0.3F, g, 1.0F) * flicker;
                b = Mth.lerp(0.55F + spark * 0.3F, b, 1.0F) * flicker;
            }
            case FIRE -> {
                float flame = (float) (noise(t * 1.3, v * 14.0 - t * 9.0) * 0.65 + noise(-t * 2.1, v * 31.0 - t * 15.0) * 0.35);
                float hr = Mth.lerp(flame, 0.85F, 1.0F), hg = Mth.lerp(flame, 0.18F, 0.62F), hb = Mth.lerp(flame, 0.02F, 0.12F);
                float white = Mth.clamp((flame - 0.55F) / 0.4F, 0, 1) * 0.6F;
                hr = Mth.lerp(white, hr, 1.0F);
                hg = Mth.lerp(white, hg, 0.95F);
                hb = Mth.lerp(white, hb, 0.7F);
                float k = 0.8F + 0.4F * flame;
                r = Mth.lerp(0.75F, r, hr) * k;
                g = Mth.lerp(0.75F, g, hg) * k;
                b = Mth.lerp(0.75F, b, hb) * k;
                a *= Mth.clamp(0.75F + 0.5F * flame, 0, 1);
            }
            case WATER -> {
                float shimmer = (float) noise(t * 0.7, v * 9.0 - t * 2.2);
                float k = 0.85F + 0.35F * shimmer;
                r *= k;
                g *= k;
                b *= k;
                a *= 0.62F + 0.25F * shimmer;
            }
            case WIND -> {
                float streak = (float) noise(v * 3.0, v * 12.0 - t * 7.0);
                r = Mth.lerp(0.35F * streak, r, 1.0F);
                g = Mth.lerp(0.35F * streak, g, 1.0F);
                b = Mth.lerp(0.35F * streak, b, 1.0F);
                a *= Mth.clamp((streak * 0.7F + 0.2F - 0.3F) / 0.55F, 0.15F, 1.0F);
            }
            default -> {
                return argb;
            }
        }
        return Argb.color(c255(a), c255(r), c255(g), c255(b));
    }

    private static int c255(float x) {
        return Math.round(Mth.clamp(x, 0.0F, 1.0F) * 255.0F);
    }

    /** How far the lightning cord's centre is pushed sideways at {@code v}, in cord widths (0 for other modes). */
    static double jag(WhipCordFx fx, float v, double t) {
        if (fx != WhipCordFx.LIGHTNING || v <= 0.02F) {
            return 0;
        }
        double frame = Math.floor(t * 18.0);
        return (noise(v * 28.0, frame) - 0.5) * 1.4 * Math.min(1.0, v * 6.0);
    }
}
