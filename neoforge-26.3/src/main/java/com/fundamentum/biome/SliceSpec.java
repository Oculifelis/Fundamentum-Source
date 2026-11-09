package com.fundamentum.biome;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * One {@code data/<ns>/fundamentum/biome_slices/<name>.json} file as written, before any game lookup (see the README's
 * "Biome slices" section for the format). Parsing throws {@link IllegalArgumentException} with a readable reason.
 */
public record SliceSpec(String id, String biome, String dimension, List<String> shareWith, int axis, int side, double weight,
                        long[] band, long shift, long[] climate, boolean climateDepth, long offset, Placement placement,
                        List<String> requires, String toggle, boolean enabled) {

    public enum Placement { SPLIT, ADD }

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";

    public boolean nether() {
        return NETHER.equals(dimension);
    }

    public String describe() {
        if (!shareWith.isEmpty()) return "share " + String.join(",", shareWith) + " on " + SliceMath.AXIS_NAMES[axis];
        return placement == Placement.ADD ? "add climate box" : "split climate box";
    }

    public static SliceSpec parse(String id, JsonObject json) {
        String biome = string(json, "biome", null);
        if (biome == null) throw new IllegalArgumentException("no \"biome\"");
        String dimension = string(json, "dimension", OVERWORLD);
        boolean nether = NETHER.equals(dimension);

        List<String> with = new ArrayList<>();
        int axis = nether ? SliceMath.HUMIDITY : SliceMath.WEIRDNESS;
        int side = 1;
        long[] band = null;
        double shift = 0.15;
        JsonObject share = json.has("share") ? json.getAsJsonObject("share") : null;
        if (share != null) {
            JsonElement w = share.get("with");
            if (w == null) throw new IllegalArgumentException("\"share\" has no \"with\"");
            if (w.isJsonArray()) for (JsonElement e : w.getAsJsonArray()) with.add(e.getAsString());
            else with.add(w.getAsString());
            if (with.isEmpty()) throw new IllegalArgumentException("\"share.with\" is empty");
            if (share.has("axis")) {
                axis = SliceMath.axis(share.get("axis").getAsString());
                if (axis < 0 || axis == SliceMath.DEPTH) throw new IllegalArgumentException("bad axis " + share.get("axis"));
            }
            side = switch (string(share, "side", "high")) {
                case "high" -> 1;
                case "low" -> -1;
                case "middle" -> 0;
                default -> throw new IllegalArgumentException("side must be high, low or middle");
            };
            if (share.has("band")) band = range(share.get("band"), "share.band");
            if (share.has("shift")) shift = share.get("shift").getAsDouble();
        }

        long[] climate = null;
        boolean depth = false;
        if (json.has("climate")) {
            JsonObject c = json.getAsJsonObject("climate");
            climate = SliceMath.open();
            for (String key : c.keySet()) {
                int d = SliceMath.axis(key);
                if (d < 0) throw new IllegalArgumentException("unknown climate axis " + key);
                long[] r = range(c.get(key), "climate." + key);
                climate[2 * d] = r[0];
                climate[2 * d + 1] = r[1];
                if (d == SliceMath.DEPTH) depth = true;
            }
        }
        if (share == null && climate == null) throw new IllegalArgumentException("needs \"share\" or \"climate\"");

        Placement placement;
        if (json.has("placement")) {
            placement = switch (json.get("placement").getAsString()) {
                case "split" -> Placement.SPLIT;
                case "add" -> Placement.ADD;
                default -> throw new IllegalArgumentException("placement must be split or add");
            };
        } else {
            placement = share != null || (!depth && !nether) ? Placement.SPLIT : Placement.ADD;
        }
        if (placement == Placement.ADD && climate == null) throw new IllegalArgumentException("\"add\" needs a \"climate\" box");
        double weight = json.has("weight") ? json.get("weight").getAsDouble() : share != null ? 0.5 : 1.0;
        if (weight <= 0 || weight > 1) throw new IllegalArgumentException("weight must be above 0 and at most 1");

        List<String> requires = new ArrayList<>();
        if (json.has("requires")) {
            JsonElement r = json.get("requires");
            if (r.isJsonArray()) for (JsonElement e : r.getAsJsonArray()) requires.add(e.getAsString());
            else requires.add(r.getAsString());
        }
        return new SliceSpec(id, biome, dimension, List.copyOf(with), axis, side, weight, band, SliceMath.quantize(shift), climate,
            depth, SliceMath.quantize(json.has("offset") ? json.get("offset").getAsDouble() : 0.0), placement,
            List.copyOf(requires), string(json, "toggle", null), !json.has("enabled") || json.get("enabled").getAsBoolean());
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) ? json.get(key).getAsString() : fallback;
    }

    /** A single value or a [min, max] pair in -2..2, like vanilla's climate parameters. */
    static long[] range(JsonElement e, String what) {
        double lo, hi;
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            if (a.size() != 2) throw new IllegalArgumentException(what + " must be a number or [min, max]");
            lo = a.get(0).getAsDouble();
            hi = a.get(1).getAsDouble();
        } else {
            lo = hi = e.getAsDouble();
        }
        if (lo > hi || lo < -2 || hi > 2) throw new IllegalArgumentException(what + " must lie in -2..2 with min <= max");
        return new long[] {SliceMath.quantize(lo), SliceMath.quantize(hi)};
    }
}
