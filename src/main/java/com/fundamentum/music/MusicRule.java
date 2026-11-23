package com.fundamentum.music;

import com.fundamentum.port.FId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One music rule from {@code data/<ns>/fundamentum/music/<name>.json}: a track, a priority, fade times and the
 * conditions that must all hold for it to play. A condition left out matches anything. The same file on every version.
 */
public record MusicRule(FId id, FId sound, int priority, int fadeIn, int fadeOut, List<FId> dimensions, List<String> biomes,
                        List<String> structures, long timeFrom, long timeTo, List<MusicContext.Weather> weather,
                        List<FId> seasons, Boss boss, List<FId> bosses, Boolean combat) {

    /** Whether a boss fight matters: any (left out), only outside one, or only during one. */
    public enum Boss { ANY, NONE, FIGHT }

    /** Named times of day, in day ticks: {@code [from, to)}, wrapping past 24000. */
    public static long[] namedTime(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "day" -> new long[] {1000, 12000};
            case "dusk" -> new long[] {12000, 13000};
            case "night" -> new long[] {13000, 23000};
            case "dawn" -> new long[] {23000, 1000};
            default -> null;
        };
    }

    /** Reads one rule; throws {@link IllegalArgumentException} with a reason when the file is wrong. */
    public static MusicRule parse(FId id, JsonObject json) {
        if (!json.has("sound")) throw new IllegalArgumentException("no \"sound\"");
        FId sound = FId.tryOf(json.get("sound").getAsString());
        if (sound == null) throw new IllegalArgumentException("bad sound id " + json.get("sound"));
        int priority = json.has("priority") ? json.get("priority").getAsInt() : 0;
        int fadeIn = Math.max(1, json.has("fade_in") ? json.get("fade_in").getAsInt() : 40);
        int fadeOut = Math.max(1, json.has("fade_out") ? json.get("fade_out").getAsInt() : 40);
        List<FId> dims = ids(json, "dimensions", "dimension");
        List<String> biomes = keys(json, "biomes", "biome");
        List<String> structures = keys(json, "structures", "structure");
        long from = -1;
        long to = -1;
        if (json.has("time")) {
            JsonElement t = json.get("time");
            if (t.isJsonPrimitive()) {
                long[] r = namedTime(t.getAsString());
                if (r == null) throw new IllegalArgumentException("unknown time " + t + " (day, dusk, night, dawn or {from, to})");
                from = r[0];
                to = r[1];
            } else if (t.isJsonObject()) {
                JsonObject o = t.getAsJsonObject();
                if (!o.has("from") || !o.has("to")) throw new IllegalArgumentException("time needs from and to");
                from = Math.floorMod(o.get("from").getAsLong(), 24000L);
                to = Math.floorMod(o.get("to").getAsLong(), 24000L);
            } else {
                throw new IllegalArgumentException("time must be a name or {from, to}");
            }
        }
        List<MusicContext.Weather> weather = new ArrayList<>();
        for (String w : strings(json, "weather", "weather")) {
            try {
                weather.add(MusicContext.Weather.valueOf(w.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown weather " + w + " (clear, rain or thunder)");
            }
        }
        List<FId> seasons = new ArrayList<>();
        for (String s : strings(json, "seasons", "season")) seasons.add(FId.of(s.contains(":") ? s : "fundamentum:" + s));
        Boss boss = Boss.ANY;
        List<FId> bosses = List.of();
        if (json.has("boss")) {
            JsonElement b = json.get("boss");
            if (b.isJsonPrimitive() && b.getAsJsonPrimitive().isBoolean()) {
                boss = b.getAsBoolean() ? Boss.FIGHT : Boss.NONE;
            } else {
                boss = Boss.FIGHT;
                bosses = ids(json, "boss", "boss");
            }
        }
        Boolean combat = json.has("combat") ? json.get("combat").getAsBoolean() : null;
        return new MusicRule(id, sound, priority, fadeIn, fadeOut, dims, biomes, structures, from, to, List.copyOf(weather),
            List.copyOf(seasons), boss, bosses, combat);
    }

    /** Whether every condition holds. */
    public boolean matches(MusicContext c) {
        if (!dimensions.isEmpty() && !dimensions.contains(c.dimension())) return false;
        if (!biomes.isEmpty() && !anyBiome(c)) return false;
        if (!structures.isEmpty() && structures.stream().noneMatch(c.structures()::contains)) return false;
        if (timeFrom >= 0 && !inTime(Math.floorMod(c.dayTime(), 24000L))) return false;
        if (!weather.isEmpty() && !weather.contains(c.weather())) return false;
        if (!seasons.isEmpty() && (c.season() == null || !seasons.contains(c.season()))) return false;
        if (boss == Boss.NONE && c.bossFight()) return false;
        if (boss == Boss.FIGHT && !c.bossFight()) return false;
        if (!bosses.isEmpty() && (c.bossType() == null || !bosses.contains(c.bossType()))) return false;
        if (combat != null && combat != c.combat()) return false;
        return true;
    }

    private boolean anyBiome(MusicContext c) {
        for (String b : biomes) {
            if (b.startsWith("#")) {
                if (c.biomeTag().test(FId.of(b.substring(1)))) return true;
            } else if (c.biome() != null && b.equals(c.biome().toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean inTime(long t) {
        if (timeFrom <= timeTo) return t >= timeFrom && t < timeTo;
        return t >= timeFrom || t < timeTo;
    }

    private static List<String> strings(JsonObject json, String plural, String single) {
        JsonElement e = json.has(plural) ? json.get(plural) : json.get(single);
        List<String> out = new ArrayList<>();
        if (e == null) return out;
        if (e.isJsonArray()) {
            for (JsonElement x : (JsonArray) e) out.add(x.getAsString());
        } else {
            out.add(e.getAsString());
        }
        return out;
    }

    private static List<FId> ids(JsonObject json, String plural, String single) {
        List<FId> out = new ArrayList<>();
        for (String s : strings(json, plural, single)) {
            FId id = FId.tryOf(s);
            if (id == null) throw new IllegalArgumentException("bad id " + s + " in " + plural);
            out.add(id);
        }
        return List.copyOf(out);
    }

    /** Ids or {@code #tags}, checked and kept as text. */
    private static List<String> keys(JsonObject json, String plural, String single) {
        List<String> out = new ArrayList<>();
        for (String s : strings(json, plural, single)) {
            String bare = s.startsWith("#") ? s.substring(1) : s;
            FId id = FId.tryOf(bare);
            if (id == null) throw new IllegalArgumentException("bad id " + s + " in " + plural);
            out.add(s.startsWith("#") ? "#" + id : id.toString());
        }
        return List.copyOf(out);
    }
}
