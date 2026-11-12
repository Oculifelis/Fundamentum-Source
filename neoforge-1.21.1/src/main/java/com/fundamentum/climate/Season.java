package com.fundamentum.climate;

import com.fundamentum.port.FClimate;
import com.fundamentum.port.FId;
import com.fundamentum.port.FTags;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One season from {@code data/<ns>/fundamentum/seasons/<name>.json}: where it falls in the year, how long it lasts,
 * what it does to temperature, rainfall and crops, and the grass and foliage tint clients blend toward.
 *
 * @param order      sort key in the year (ties go by id)
 * @param length     length in days, at least 1
 * @param temperature temperature offset for biomes no rule matches
 * @param rainfall   humidity offset for biomes no rule matches
 * @param biomes     per-biome offsets, the first matching rule wins
 * @param cropGrowth crop growth multiplier for crops no rule matches (1 = normal)
 * @param crops      per-crop multipliers, the first matching rule wins
 * @param grass      grass tint as 0xRRGGBB, or -1 for none
 * @param foliage    foliage tint as 0xRRGGBB, or -1 for none
 * @param tint       how far clients blend toward the tints, 0 to 1
 */
public record Season(FId id, int order, int length, double temperature, double rainfall, List<BiomeRule> biomes,
                     double cropGrowth, List<CropRule> crops, int grass, int foliage, double tint) {

    /** Offsets for the biomes it names: ids, or {@code #} and a biome tag. */
    public record BiomeRule(List<String> match, double temperature, double rainfall) {
        boolean matches(Holder<Biome> biome) {
            FId id = FClimate.biomeId(biome);
            for (String m : match) {
                if (m.startsWith("#")) {
                    if (biome.is(FTags.biome(FId.of(m.substring(1))))) return true;
                } else if (id != null && id.equals(FId.of(m))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A growth multiplier for the crops it names: block ids, or {@code #} and a block tag. */
    public record CropRule(String match, double multiplier) {
        boolean matches(BlockState state) {
            if (match.startsWith("#")) return state.is(FTags.block(FId.of(match.substring(1))));
            return FVanilla.idOf(state.getBlock()).equals(FId.of(match));
        }
    }

    /** The temperature offset this season gives a biome. */
    public double temperatureFor(Holder<Biome> biome) {
        for (BiomeRule r : biomes) if (r.matches(biome)) return r.temperature();
        return temperature;
    }

    /** The humidity offset this season gives a biome. */
    public double rainfallFor(Holder<Biome> biome) {
        for (BiomeRule r : biomes) if (r.matches(biome)) return r.rainfall();
        return rainfall;
    }

    /** The growth multiplier this season gives a crop block. */
    public double cropGrowthFor(BlockState state) {
        for (CropRule r : crops) if (r.matches(state)) return r.multiplier();
        return cropGrowth;
    }

    static Season parse(FId id, JsonObject o) {
        int order = o.has("order") ? o.get("order").getAsInt() : 0;
        int length = o.has("length") ? o.get("length").getAsInt() : 8;
        if (length < 1) throw new IllegalArgumentException("length must be at least 1 day");
        double temperature = num(o, "temperature", 0);
        double rainfall = num(o, "rainfall", 0);
        List<BiomeRule> biomes = new ArrayList<>();
        if (o.has("biomes")) {
            for (JsonElement e : o.getAsJsonArray("biomes")) {
                JsonObject r = e.getAsJsonObject();
                List<String> match = new ArrayList<>();
                JsonElement m = r.get("biomes");
                if (m == null) throw new IllegalArgumentException("a biome rule needs \"biomes\"");
                if (m.isJsonArray()) for (JsonElement x : m.getAsJsonArray()) match.add(check(x.getAsString()));
                else match.add(check(m.getAsString()));
                biomes.add(new BiomeRule(List.copyOf(match), num(r, "temperature", temperature), num(r, "rainfall", rainfall)));
            }
        }
        double cropGrowth = num(o, "crop_growth", 1);
        if (cropGrowth < 0) throw new IllegalArgumentException("crop_growth can't be negative");
        List<CropRule> crops = new ArrayList<>();
        if (o.has("crops")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("crops").entrySet()) {
                double v = e.getValue().getAsDouble();
                if (v < 0) throw new IllegalArgumentException("crop multiplier for " + e.getKey() + " can't be negative");
                crops.add(new CropRule(check(e.getKey()), v));
            }
        }
        int grass = o.has("grass") ? colour(o.get("grass").getAsString()) : -1;
        int foliage = o.has("foliage") ? colour(o.get("foliage").getAsString()) : -1;
        double tint = Math.max(0, Math.min(1, num(o, "tint", 0.25)));
        return new Season(id, order, length, temperature, rainfall, List.copyOf(biomes), cropGrowth, List.copyOf(crops),
            grass, foliage, tint);
    }

    private static double num(JsonObject o, String key, double def) {
        return o.has(key) ? o.get(key).getAsDouble() : def;
    }

    private static String check(String s) {
        String bare = s.startsWith("#") ? s.substring(1) : s;
        if (!FId.isValid(bare)) throw new IllegalArgumentException("bad id " + s);
        return s;
    }

    static int colour(String s) {
        String hex = s.startsWith("#") ? s.substring(1) : s;
        if (hex.length() != 6) throw new IllegalArgumentException("colour must be #RRGGBB: " + s);
        return Integer.parseInt(hex, 16);
    }
}
