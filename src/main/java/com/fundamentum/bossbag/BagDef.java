package com.fundamentum.bossbag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

/**
 * One boss bag entry, read from {@code data/<namespace>/fundamentum/boss_bags/<name>.json}:
 *
 * <pre>{"entities": ["minecraft:wither"], "tag": "mymod:bagged_bosses",
 *  "item": "fundamentum:boss_bag", "loot_table": "minecraft:entities/wither",
 *  "xp": true, "own_seconds": 60, "always": [{"item": "minecraft:nether_star", "count": 1}],
 *  "participation": {"hurt": true, "killer": true, "fighters": true, "radius": 24, "height": 12},
 *  "puff": [3812894, 14202970], "name": "item.mymod.wither_bag", "sound": "minecraft:item.bundle.drop_contents",
 *  "enabled": true}</pre>
 *
 * Every field is optional but one of {@code entities} and {@code tag}. With no {@code loot_table} the bag rolls the boss's
 * own loot table; with {@code "xp": false} the boss drops its XP as usual instead of putting it in the bags.
 */
public record BagDef(List<ResourceLocation> entities, List<TagKey<EntityType<?>>> tags, ResourceLocation item, @Nullable ResourceLocation lootTable,
        boolean xp, int ownTicks, Participation participation, List<Always> always, int[] puff, @Nullable String name,
        @Nullable ResourceLocation sound, boolean enabled) {

    /** The generic bag item. */
    public static final ResourceLocation DEFAULT_ITEM = ResourceLocation.fromNamespaceAndPath("fundamentum", "boss_bag");
    /** Puff colours with none given: umber and gold. */
    public static final int[] DEFAULT_PUFF = {0x3A2A1E, 0xD8B45A};

    /**
     * Who gets a bag: a player who hurt the boss, the one who dealt the killing blow, the players on its Fundamentum boss
     * bar, and anyone within {@code radius} blocks across and {@code height} up or down when it fell. Spectators never do.
     */
    public record Participation(boolean hurt, boolean killer, boolean fighters, double radius, double height) {
        public static final Participation DEFAULT = new Participation(true, true, true, 24, 12);

        static Participation fromJson(@Nullable JsonObject o) {
            if (o == null) return DEFAULT;
            return new Participation(bool(o, "hurt", true), bool(o, "killer", true), bool(o, "fighters", true),
                o.has("radius") ? o.get("radius").getAsDouble() : DEFAULT.radius, o.has("height") ? o.get("height").getAsDouble() : DEFAULT.height);
        }

        /** Whether a player at this offset from the boss stood in its arena (radius 0 turns the arena check off). */
        public boolean inRange(double dx, double dy, double dz) {
            return this.radius > 0 && dx * dx + dz * dz <= this.radius * this.radius && Math.abs(dy) <= this.height;
        }
    }

    /** An item every bag of this boss holds besides its roll. */
    public record Always(ResourceLocation item, int count) { }

    public static BagDef fromJson(JsonObject o) {
        List<ResourceLocation> entities = new ArrayList<>();
        if (o.has("entity")) entities.add(ResourceLocation.parse(o.get("entity").getAsString()));
        if (o.has("entities")) for (JsonElement e : o.getAsJsonArray("entities")) entities.add(ResourceLocation.parse(e.getAsString()));
        List<TagKey<EntityType<?>>> tags = new ArrayList<>();
        if (o.has("tag")) {
            JsonElement t = o.get("tag");
            if (t.isJsonArray()) for (JsonElement e : t.getAsJsonArray()) tags.add(tag(e.getAsString()));
            else tags.add(tag(t.getAsString()));
        }
        List<Always> always = new ArrayList<>();
        if (o.has("always")) {
            for (JsonElement e : o.getAsJsonArray("always")) {
                if (e.isJsonPrimitive()) always.add(new Always(ResourceLocation.parse(e.getAsString()), 1));
                else {
                    JsonObject a = e.getAsJsonObject();
                    always.add(new Always(ResourceLocation.parse(a.get("item").getAsString()), a.has("count") ? a.get("count").getAsInt() : 1));
                }
            }
        }
        int[] puff = DEFAULT_PUFF;
        if (o.has("puff")) {
            JsonArray a = o.getAsJsonArray("puff");
            puff = new int[a.size()];
            for (int i = 0; i < a.size(); i++) puff[i] = colour(a.get(i));
        }
        int ownTicks = o.has("own_seconds") ? Math.max(0, (int) Math.round(o.get("own_seconds").getAsDouble() * 20)) : 1200;
        return new BagDef(List.copyOf(entities), List.copyOf(tags),
            o.has("item") ? ResourceLocation.parse(o.get("item").getAsString()) : DEFAULT_ITEM,
            o.has("loot_table") ? ResourceLocation.parse(o.get("loot_table").getAsString()) : null,
            bool(o, "xp", true), ownTicks, Participation.fromJson(o.has("participation") ? o.getAsJsonObject("participation") : null),
            List.copyOf(always), puff, o.has("name") ? o.get("name").getAsString() : null,
            o.has("sound") ? ResourceLocation.parse(o.get("sound").getAsString()) : null, bool(o, "enabled", true));
    }

    private static TagKey<EntityType<?>> tag(String s) {
        return TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.parse(s.startsWith("#") ? s.substring(1) : s));
    }

    private static int colour(JsonElement e) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) return e.getAsInt();
        String s = e.getAsString();
        return Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16);
    }

    private static boolean bool(JsonObject o, String k, boolean fallback) {
        return o.has(k) ? o.get(k).getAsBoolean() : fallback;
    }
}
