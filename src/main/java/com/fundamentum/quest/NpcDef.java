package com.fundamentum.quest;

import com.fundamentum.port.FId;
import com.google.gson.JsonObject;

/**
 * A quest-giver NPC type from {@code data/<ns>/fundamentum/npcs/*.json}. Quests list it under {@code "npcs"}.
 * <pre>
 * {
 *   "name": "Brother Aldric",                          // shown over its head
 *   "texture": "mymod:textures/entity/aldric.png",     // a 64x64 player-layout skin; left out, one of the nine vanilla skins
 *   "slim": false,                                     // true: the slim (3 pixel) arm model
 *   "wander": false,                                   // true: strolls around; false: stays put and looks at players
 *   "invulnerable": true,                              // false: it can be hurt and killed like any mob
 *   "held": "minecraft:book",                          // item in its main hand, or leave it out
 *   "screen": "mymod:ledger"                           // quest screen its offers open in
 * }
 * </pre>
 */
public record NpcDef(FId id, String name, String texture, boolean slim, boolean wander, boolean invulnerable, String held,
                     FId screen) {
    /** The skin used when the entity has no type yet. */
    public static final String DEFAULT_TEXTURE = "minecraft:textures/entity/player/wide/steve.png";
    /** The vanilla default skins; an NPC type without a texture wears one of them, picked by the entity's UUID. */
    public static final String[] VANILLA_SKINS = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};

    /** The vanilla skin for an entity with this UUID hash. */
    public static String vanillaSkin(int hash, boolean slim) {
        return "minecraft:textures/entity/player/" + (slim ? "slim" : "wide") + "/" + VANILLA_SKINS[Math.floorMod(hash, VANILLA_SKINS.length)] + ".png";
    }

    static NpcDef parse(FId id, JsonObject o) {
        return new NpcDef(id, QuestDef.s(o, "name", ""), QuestDef.s(o, "texture", ""),
            QuestDef.b(o, "slim", false), QuestDef.b(o, "wander", false), QuestDef.b(o, "invulnerable", true),
            QuestDef.s(o, "held", ""), o.has("screen") ? FId.tryOf(o.get("screen").getAsString()) : null);
    }
}
