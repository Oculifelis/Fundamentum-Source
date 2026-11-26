package com.fundamentum.religion;

import com.fundamentum.faction.FactionDef;
import com.fundamentum.port.FId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The religion fields of a faction with {@code "kind": "deity"}. Devotion is the faction's rep, so the faction's own
 * {@code ranks}, {@code actions} (liked and hated acts), {@code relations} (rival gods) and {@code drift} all apply.
 * Extra fields, all optional:
 * <pre>
 * {
 *   "kind": "deity", "domain": "sun", "symbol": "minecraft:sunflower", "pantheon": "mymod:old_gods",
 *   "favour": { "max": 100, "regen": 1 },              // regen: favour per minute for followers
 *   "prayer": { "devotion": 2, "favour": 5, "cooldown": 1200 },
 *   "offerings": [ { "item": "minecraft:gold_ingot", "devotion": 5, "favour": 10 }, { "item": "#c:gems", "favour": 20 } ],
 *   "follow": { "min_rank": "neutral", "requires": [conditions], "forsake": -100 },
 *   "boons": { "mend": { "cost": 20, "min_rank": "devout", "cooldown": 1200, "rewards": { "heal": 10 } },
 *              "second_life": { "cost": 50, "min_rank": "exalted", "cooldown": 24000, "revive": true } },
 *   "wrath": { "enabled": false, "below": -200, "chance": 0.1, "cooldown": 6000,
 *              "rewards": { "lightning": true, "effects": [ { "id": "minecraft:weakness", "duration": 600 } ] } }
 * }
 * </pre>
 * Reward objects use every quest and rank reward key plus the miracle keys {@code heal}, {@code weather},
 * {@code smite}, {@code lightning}, {@code spawn} and {@code favour}.
 */
public record Deity(FactionDef faction, String domain, FId symbol, FId pantheon, double favourMax, double regen,
                    int prayDevotion, double prayFavour, int prayCooldown, List<Offering> offerings,
                    String followRank, JsonElement followRequires, int forsake, Map<String, Boon> boons, Wrath wrath) {

    public record Offering(String item, int devotion, double favour) { }

    public record Boon(String id, double cost, String minRank, int cooldown, boolean revive, JsonElement rewards) { }

    public record Wrath(boolean enabled, int below, double chance, int cooldown, JsonElement rewards) {
        static final Wrath OFF = new Wrath(false, -200, 0.1, 6000, null);
    }

    public FId id() {
        return faction.id();
    }

    public String displayName() {
        return faction.displayName();
    }

    static Deity parse(FactionDef f) {
        JsonObject o = f.raw() == null ? new JsonObject() : f.raw();
        JsonObject fav = obj(o, "favour");
        JsonObject pray = obj(o, "prayer");
        JsonObject follow = obj(o, "follow");
        List<Offering> offerings = new ArrayList<>();
        if (o.has("offerings") && o.get("offerings").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("offerings")) {
                if (!e.isJsonObject()) continue;
                JsonObject x = e.getAsJsonObject();
                if (!x.has("item")) continue;
                offerings.add(new Offering(x.get("item").getAsString(), i(x, "devotion", 0), d(x, "favour", 0)));
            }
        }
        Map<String, Boon> boons = new LinkedHashMap<>();
        JsonObject b = obj(o, "boons");
        for (Map.Entry<String, JsonElement> e : b.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject x = e.getValue().getAsJsonObject();
            boons.put(e.getKey(), new Boon(e.getKey(), Math.max(0, d(x, "cost", 0)), s(x, "min_rank", null),
                Math.max(0, i(x, "cooldown", 0)), bool(x, "revive", false), x.get("rewards")));
        }
        Wrath wrath = Wrath.OFF;
        if (o.has("wrath") && o.get("wrath").isJsonObject()) {
            JsonObject w = o.getAsJsonObject("wrath");
            wrath = new Wrath(bool(w, "enabled", false), i(w, "below", -200), d(w, "chance", 0.1),
                Math.max(20, i(w, "cooldown", 6000)), w.get("rewards"));
        }
        return new Deity(f, s(o, "domain", ""), id(o, "symbol"), id(o, "pantheon"), Math.max(0, d(fav, "max", 100)),
            Math.max(0, d(fav, "regen", 0)), i(pray, "devotion", 2), Math.max(0, d(pray, "favour", 5)),
            Math.max(0, i(pray, "cooldown", 1200)), List.copyOf(offerings), s(follow, "min_rank", null),
            follow.get("requires"), i(follow, "forsake", 0), Map.copyOf(boons), wrath);
    }

    static JsonObject obj(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    static FId id(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? FId.tryOf(o.get(k).getAsString()) : null;
    }

    static int i(JsonObject o, String k, int def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : def;
    }

    static double d(JsonObject o, String k, double def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
    }

    static boolean bool(JsonObject o, String k, boolean def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : def;
    }

    static String s(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
        } else {
            out.add(e.getAsString());
        }
        return out;
    }
}
