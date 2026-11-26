package com.fundamentum.faction;

import com.fundamentum.port.FId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One faction, read from {@code data/<ns>/fundamentum/factions/<path>.json}. Every field is optional:
 * <pre>
 * {
 *   "name": "Order of the Lantern",          // text or a lang key
 *   "color": "#E0B040", "icon": "minecraft:lantern", "description": "...",
 *   "kind": "faction",                       // or "deity" (see the religion part)
 *   "members": ["minecraft:vindicator", "#minecraft:illager"],
 *   "professions": ["minecraft:cleric"],
 *   "relations": { "mymod:raiders": { "type": "enemy", "spill": 0.5 } },
 *   "min": -1000, "max": 1000, "start": 0,
 *   "ranks": [ { "id": "hated", "min": -1000, "attitude": "hostile", "price": 1.5 },
 *              { "id": "friendly", "min": 100, "attitude": "friendly", "price": 0.9, "rewards": { ... } } ],
 *   "actions": { "kill_member": -50, "kill_enemy": 10, "trade": 2,
 *                "break_block": { "rep": -25, "blocks": ["#mymod:shrine_stones"] },
 *                "kill_member_alignment": { "mymod:order": -1 } },
 *   "drift": { "per_day": 5, "toward": 0 },
 *   "membership": { "joinable": true, "group": "mymod:orders", "min_rank": "friendly", "requires": [conditions],
 *                   "leave_rep": -100 },
 *   "team_shared": false, "enabled": true
 * }
 * </pre>
 */
public record FactionDef(FId id, String name, int color, FId icon, String description, String kind,
                         List<String> members, List<FId> professions, Map<FId, Relation> relations,
                         int min, int max, int start, List<Rank> ranks, Map<String, Action> actions,
                         double driftPerDay, int driftToward, Membership membership, boolean teamShared,
                         boolean enabled, JsonObject raw) {

    /** How this faction stands with another. {@code spill} is the share of a rep change passed on (minus for enemies). */
    public record Relation(String type, double spill) {
        public boolean enemy() {
            return "enemy".equals(type);
        }

        public boolean ally() {
            return "ally".equals(type);
        }
    }

    /**
     * A named band of rep from {@code min} up to the next rank. {@code attitude} is how members treat the player:
     * hostile (attack on sight), neutral (as vanilla), friendly (never target them) or defend (also fight for them).
     * {@code price} multiplies member villagers' prices.
     */
    public record Rank(String id, int min, String attitude, double price, JsonElement rewards) { }

    /** A rep change for one kind of act, plus alignment pushes and (for block acts) which blocks count. */
    public record Action(int rep, Map<FId, Double> alignment, List<String> blocks) { }

    /** Join rules. Factions in the same {@code group} exclude each other. */
    public record Membership(boolean joinable, FId group, String minRank, JsonElement requires, int leaveRep) {
        static final Membership NONE = new Membership(false, null, null, null, 0);
    }

    public static final String HOSTILE = "hostile";
    public static final String NEUTRAL = "neutral";
    public static final String FRIENDLY = "friendly";
    public static final String DEFEND = "defend";

    public boolean deity() {
        return "deity".equals(kind);
    }

    /** The rank for a rep value, or null when the faction has no ranks. */
    public Rank rankAt(int rep) {
        Rank out = null;
        for (Rank r : ranks) {
            if (r.min() <= rep) out = r;
        }
        return out == null && !ranks.isEmpty() ? ranks.get(0) : out;
    }

    public Rank rank(String id) {
        for (Rank r : ranks) {
            if (r.id().equals(id)) return r;
        }
        return null;
    }

    public Relation relation(FId other) {
        return relations.get(other);
    }

    /** The unlock a player holds while at (or, for ranks at or above the starting one, past) a rank. */
    public FId rankUnlock(String rank) {
        return FId.of(id.namespace(), "rank/" + id.path() + "/" + rank);
    }

    public FId memberUnlock() {
        return FId.of(id.namespace(), "member/" + id.path());
    }

    public String displayName() {
        return name == null ? id.toString() : name;
    }

    static FactionDef parse(FId id, JsonObject o) {
        int min = i(o, "min", -1000);
        int max = Math.max(min, i(o, "max", 1000));
        int start = Math.max(min, Math.min(max, i(o, "start", 0)));
        Map<FId, Relation> relations = new LinkedHashMap<>();
        if (o.has("relations")) {
            o.getAsJsonObject("relations").entrySet().forEach(e -> {
                FId other = FId.tryOf(e.getKey());
                if (other == null) return;
                JsonElement v = e.getValue();
                if (v.isJsonPrimitive()) {
                    relations.put(other, new Relation(v.getAsString(), 0));
                } else {
                    JsonObject r = v.getAsJsonObject();
                    relations.put(other, new Relation(s(r, "type", "neutral"), d(r, "spill", 0)));
                }
            });
        }
        List<Rank> ranks = new ArrayList<>();
        if (o.has("ranks")) {
            for (JsonElement e : o.getAsJsonArray("ranks")) {
                JsonObject r = e.getAsJsonObject();
                ranks.add(new Rank(s(r, "id", "rank" + ranks.size()), i(r, "min", min), s(r, "attitude", NEUTRAL),
                    d(r, "price", 1.0), r.get("rewards")));
            }
        }
        ranks.sort(Comparator.comparingInt(Rank::min));
        Map<String, Action> actions = new LinkedHashMap<>();
        if (o.has("actions")) {
            JsonObject a = o.getAsJsonObject("actions");
            for (Map.Entry<String, JsonElement> e : a.entrySet()) {
                if (e.getKey().endsWith("_alignment")) continue;
                JsonElement v = e.getValue();
                int rep;
                List<String> blocks = List.of();
                Map<FId, Double> align = new LinkedHashMap<>();
                if (v.isJsonPrimitive()) {
                    rep = v.getAsInt();
                } else {
                    JsonObject ao = v.getAsJsonObject();
                    rep = i(ao, "rep", 0);
                    blocks = strings(ao.get("blocks"));
                    if (ao.has("alignment")) readAlign(ao.getAsJsonObject("alignment"), align);
                }
                if (a.has(e.getKey() + "_alignment")) readAlign(a.getAsJsonObject(e.getKey() + "_alignment"), align);
                actions.put(e.getKey(), new Action(rep, Map.copyOf(align), blocks));
            }
        }
        double perDay = 0;
        int toward = start;
        if (o.has("drift")) {
            JsonObject dr = o.getAsJsonObject("drift");
            perDay = d(dr, "per_day", 0);
            toward = i(dr, "toward", start);
        }
        Membership m = Membership.NONE;
        if (o.has("membership")) {
            JsonObject mo = o.getAsJsonObject("membership");
            m = new Membership(b(mo, "joinable", true), mo.has("group") ? FId.tryOf(mo.get("group").getAsString()) : null,
                mo.has("min_rank") ? mo.get("min_rank").getAsString() : null, mo.get("requires"), i(mo, "leave_rep", 0));
        }
        List<FId> professions = new ArrayList<>();
        for (String p : strings(o.get("professions"))) {
            FId f = FId.tryOf(p);
            if (f != null) professions.add(f);
        }
        return new FactionDef(id, o.has("name") ? o.get("name").getAsString() : null, color(o), o.has("icon") ? FId.tryOf(o.get("icon").getAsString()) : null,
            s(o, "description", ""), s(o, "kind", "faction"), strings(o.get("members")), List.copyOf(professions),
            Map.copyOf(relations), min, max, start, List.copyOf(ranks), Map.copyOf(actions), perDay, toward, m,
            b(o, "team_shared", false), b(o, "enabled", true), o);
    }

    private static void readAlign(JsonObject o, Map<FId, Double> out) {
        o.entrySet().forEach(e -> {
            FId axis = FId.tryOf(e.getKey());
            if (axis != null) out.put(axis, e.getValue().getAsDouble());
        });
    }

    private static int color(JsonObject o) {
        if (!o.has("color")) return 0xFFFFFF;
        JsonElement c = o.get("color");
        if (c.isJsonPrimitive() && c.getAsJsonPrimitive().isNumber()) return c.getAsInt();
        String s = c.getAsString().replace("#", "");
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }

    static List<String> strings(JsonElement e) {
        if (e == null || e.isJsonNull()) return List.of();
        if (e.isJsonPrimitive()) return List.of(e.getAsString());
        List<String> out = new ArrayList<>();
        for (JsonElement x : (JsonArray) e) out.add(x.getAsString());
        return List.copyOf(out);
    }

    static int i(JsonObject o, String k, int def) {
        return o.has(k) ? o.get(k).getAsInt() : def;
    }

    static double d(JsonObject o, String k, double def) {
        return o.has(k) ? o.get(k).getAsDouble() : def;
    }

    static boolean b(JsonObject o, String k, boolean def) {
        return o.has(k) ? o.get(k).getAsBoolean() : def;
    }

    static String s(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }
}
