package com.fundamentum.unlock;

import com.fundamentum.port.FId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * One declared unlock, from {@code data/<ns>/fundamentum/unlocks/<path>.json}:
 * <pre>
 * {
 *   "name": "unlock.mymod.smelting",          translation key (default unlock.&lt;ns&gt;.&lt;path&gt;)
 *   "description": "unlock.mymod.smelting.desc",
 *   "icon": "minecraft:furnace",
 *   "hidden": false,                          hide from lists until held
 *   "team_shared": false,                     granted to the player's team when they have one
 *   "max_level": 1,                           0 for no cap
 *   "parents": ["mymod:fire"],                needed first (commands skip this check)
 *   "grant": [ {"type": "advancement", "id": "minecraft:story/smelt_iron"},
 *              {"type": "item", "id": "minecraft:furnace"} ]   any one grants it (Fundamentum conditions)
 * }
 * </pre>
 */
public record UnlockDef(FId id, String name, String description, FId icon, boolean hidden, boolean teamShared, int maxLevel,
                        List<FId> parents, JsonArray grant) {

    public static UnlockDef parse(FId id, JsonObject o) {
        String name = o.has("name") ? o.get("name").getAsString() : "unlock." + id.namespace() + "." + id.path().replace('/', '.');
        String desc = o.has("description") ? o.get("description").getAsString() : name + ".desc";
        FId icon = o.has("icon") ? FId.tryOf(o.get("icon").getAsString()) : null;
        List<FId> parents = new ArrayList<>();
        if (o.has("parents")) {
            for (JsonElement e : o.getAsJsonArray("parents")) {
                FId p = FId.tryOf(e.getAsString());
                if (p != null) parents.add(p);
            }
        }
        JsonArray grant = new JsonArray();
        if (o.has("grant")) {
            JsonElement g = o.get("grant");
            if (g.isJsonArray()) grant = g.getAsJsonArray();
            else grant.add(g);
        }
        return new UnlockDef(id, name, desc, icon,
            o.has("hidden") && o.get("hidden").getAsBoolean(),
            o.has("team_shared") && o.get("team_shared").getAsBoolean(),
            o.has("max_level") ? o.get("max_level").getAsInt() : 1,
            List.copyOf(parents), grant);
    }
}
