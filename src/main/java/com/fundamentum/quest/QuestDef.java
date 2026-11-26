package com.fundamentum.quest;

import com.fundamentum.port.FId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * One quest, read from {@code data/<ns>/fundamentum/quests/<path>.json}. Every field but {@code objectives} is optional:
 * <pre>
 * {
 *   "name": "Clear the road", "description": "...",
 *   "faction": "mymod:lantern", "min_rank": "friendly",      // rank needed to be offered it (at least)
 *   "requires": [conditions],                                 // also needed to be offered it
 *   "givers": ["minecraft:villager", "#mymod:priests"],       // mobs that offer it (shift + use)
 *   "npcs": ["mymod:abbot"],                                  // quest-giver NPC types that offer it
 *   "objectives": [ { "type": "kill", "target": "#minecraft:raiders", "count": 5 },
 *                   { "type": "collect", "item": "minecraft:wheat", "count": 10 } ],
 *   "time_limit": 24000, "repeatable": true, "cooldown": 24000,
 *   "turn_in": false,                                         // true: hand it in at a giver or NPC that offers it
 *   "group": "main",                                          // which tab of the quest screen lists it
 *   "screen": "mymod:ledger",                                 // quest screen its offers open in (assets fundamentum/quest_screens)
 *   "rewards": { ... },                                       // see Rewards
 *   "fail": { "rep": { "mymod:lantern": -20 } },             // given on failure or abandoning
 *   "enabled": true
 * }
 * </pre>
 */
public record QuestDef(FId id, String name, String description, FId faction, String minRank, JsonElement requires,
                       List<String> givers, List<FId> npcs,
                       List<JsonObject> objectives, int timeLimit, boolean repeatable, int cooldown, boolean turnIn,
                       JsonElement rewards, JsonElement fail, boolean enabled, String group, FId screen) {

    public String displayName() {
        return name == null ? id.toString() : name;
    }

    /** How many an objective needs (its {@code count}, default 1). */
    public static int count(JsonObject objective) {
        return objective.has("count") ? Math.max(1, objective.get("count").getAsInt()) : 1;
    }

    static QuestDef parse(FId id, JsonObject o) {
        List<JsonObject> objectives = new ArrayList<>();
        if (o.has("objectives")) {
            for (JsonElement e : o.getAsJsonArray("objectives")) {
                if (e.isJsonObject() && e.getAsJsonObject().has("type")) objectives.add(e.getAsJsonObject());
            }
        }
        List<FId> npcs = new ArrayList<>();
        for (String s : strings(o.get("npcs"))) {
            FId f = FId.tryOf(s);
            if (f != null) npcs.add(f);
        }
        return new QuestDef(id, o.has("name") ? o.get("name").getAsString() : null, s(o, "description", ""),
            o.has("faction") ? FId.tryOf(o.get("faction").getAsString()) : null, o.has("min_rank") ? o.get("min_rank").getAsString() : null,
            o.get("requires"), strings(o.get("givers")), List.copyOf(npcs),
            List.copyOf(objectives), Math.max(0, i(o, "time_limit", 0)), b(o, "repeatable", false), Math.max(0, i(o, "cooldown", 0)),
            b(o, "turn_in", false), o.get("rewards"), o.get("fail"), b(o, "enabled", true),
            s(o, "group", ""), o.has("screen") ? FId.tryOf(o.get("screen").getAsString()) : null);
    }

    static List<String> strings(JsonElement e) {
        if (e == null || e.isJsonNull()) return List.of();
        if (e.isJsonPrimitive()) return e.getAsJsonPrimitive().isString() ? List.of(e.getAsString()) : List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement x : (JsonArray) e) out.add(x.getAsString());
        return List.copyOf(out);
    }

    static int i(JsonObject o, String k, int def) {
        return o.has(k) ? o.get(k).getAsInt() : def;
    }

    static boolean b(JsonObject o, String k, boolean def) {
        return o.has(k) ? o.get(k).getAsBoolean() : def;
    }

    static String s(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }
}
