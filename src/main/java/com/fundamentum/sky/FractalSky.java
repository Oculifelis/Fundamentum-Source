package com.fundamentum.sky;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;

/**
 * A fractal sky: a sky drawn per pixel by Fundamentum's fractal shader in place of the vanilla one, while a moon, a
 * night event or a dimension calls for it. Data: {@code data/<namespace>/fundamentum/fractal_skies/<name>.json}, every
 * field optional except at least one of {@code moon}, {@code event} and {@code dimension} (all given must match):
 * <pre>{
 *   "moon": "fundamentum:psychedelic",   // while this moon shines
 *   "event": "mymod:eclipse",            // while this night event runs
 *   "dimension": "minecraft:the_end",    // in this dimension (alone: always there)
 *   "priority": 0,                       // the highest of those that match is drawn
 *   "fractal": "mandelbrot",             // "mandelbrot" | "julia" | "burning_ship"
 *   "iterations": 160,                   // 16..500 (a tour adds more as it zooms in)
 *   "palette": "rainbow",                // "rainbow" | "fire" | "ice" | "acid" | "neon" | "void"
 *   "tint": "#FFFFFF",
 *   "mapping": "zenith",                 // "zenith" (centred overhead) | "axes" (seen along each axis, blended: no seams)
 *   "center": [-0.7436, 0.1318],         // where the zoom heads (Julia: the offset of the view)
 *   "scale": 2.5,                        // how much of the plane the sky spans at the widest
 *   "zoom": {"speed": 0.01, "depth": 4}, // zoom cycles a second, and how deep (e-folds; at most 8, float precision)
 *   "tour": false,                       // Mandelbrot: the whole set spans the sky (centred on "center", "scale" wide),
 *                                        // and each zoom cycle dives into a different famous spot, then back out
 *   "palette_speed": 0.02, "morph_speed": 0.005, "rotation_speed": 0.002,   // cycles a second
 *   "brightness": 0.8,                   // 0..2
 *   "horizon": 0.25,                     // elevation (sine) where it is fully shown; it fades out below
 *   "fade_seconds": 4,                   // in and out
 *   "melt": 0                            // 0..1: the melting vision while it shows (client config scales it)
 * }</pre>
 */
public record FractalSky(ResourceLocation id, String moon, String event, String dimension, int priority, int fractal, int iterations, int palette,
                         int tint, int mapping, float centerX, float centerY, float scale, float zoomSpeed, float zoomDepth, float paletteSpeed,
                         float morphSpeed, float rotationSpeed, float brightness, float horizon, float fadeSeconds, float melt, boolean tour) {

    public static final List<String> FRACTALS = List.of("mandelbrot", "julia", "burning_ship");
    public static final List<String> PALETTES = List.of("rainbow", "fire", "ice", "acid", "neon", "void");
    public static final List<String> MAPPINGS = List.of("zenith", "axes");

    public static FractalSky parse(ResourceLocation id, JsonObject o) {
        JsonObject zoom = o.has("zoom") ? o.getAsJsonObject("zoom") : new JsonObject();
        float cx = 0.0F, cy = 0.0F;
        if (o.has("center")) {
            JsonArray c = o.getAsJsonArray("center");
            cx = c.get(0).getAsFloat();
            cy = c.get(1).getAsFloat();
        }
        return new FractalSky(id, str(o, "moon"), str(o, "event"), str(o, "dimension"), (int) num(o, "priority", 0),
            pick(FRACTALS, str(o, "fractal")), (int) clamp(num(o, "iterations", 160), 16, 500), pick(PALETTES, str(o, "palette")),
            o.has("tint") ? Integer.parseInt(o.get("tint").getAsString().replace("#", ""), 16) : 0xFFFFFF, pick(MAPPINGS, str(o, "mapping")),
            cx, cy, (float) num(o, "scale", 2.5), (float) num(zoom, "speed", 0.01), (float) clamp(num(zoom, "depth", 3), 0, 8),
            (float) num(o, "palette_speed", 0.02), (float) num(o, "morph_speed", 0.005), (float) num(o, "rotation_speed", 0.002),
            (float) clamp(num(o, "brightness", 0.8), 0, 2), (float) clamp(num(o, "horizon", 0.25), 0, 1), (float) Math.max(0.05, num(o, "fade_seconds", 4)),
            (float) clamp(num(o, "melt", 0), 0, 1), o.has("tour") && o.get("tour").getAsBoolean());
    }

    /** Whether it is wanted now: everything it names matches (and it names something). */
    public boolean matches(String moonNow, String eventNow, String dimensionNow) {
        if (this.moon.isEmpty() && this.event.isEmpty() && this.dimension.isEmpty()) {
            return false;
        }
        return (this.moon.isEmpty() || this.moon.equals(moonNow)) && (this.event.isEmpty() || this.event.equals(eventNow))
            && (this.dimension.isEmpty() || this.dimension.equals(dimensionNow));
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null ? "" : e.getAsString();
    }

    private static double num(JsonObject o, String key, double d) {
        JsonElement e = o.get(key);
        return e == null ? d : e.getAsDouble();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int pick(List<String> names, String name) {
        return Math.max(0, names.indexOf(name.toLowerCase(Locale.ROOT)));
    }
}
