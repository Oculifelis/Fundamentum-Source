package com.fundamentum.food;

import com.fundamentum.port.FFood;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FTags;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Food groups, declared in {@code data/<ns>/fundamentum/food_groups/<path>.json}:
 * <pre>
 * {
 *   "name": "food_group.mymod.fruits",     translation key (default food_group.&lt;ns&gt;.&lt;path&gt;)
 *   "icon": "minecraft:apple",
 *   "color": "#D03030",
 *   "items": ["minecraft:apple", "#c:foods/berry"],   items and item tags that are fully in the group
 *   "tags": ["diet:fruits"],              more item tags (with or without #)
 *   "values": {"minecraft:golden_apple": 0.5},        a share from 0 to 1 for single items
 *   "exclude": ["minecraft:enchanted_golden_apple"],
 *   "guess": true,                        recipe-made foods count by their ingredients (default true)
 *   "disabled": false                     a pack can turn a group off by overriding its file
 * }
 * </pre>
 * Every group also reads the item tag {@code #<ns>:food_groups/<path>}. Fundamentum ships fruits, grains, proteins,
 * vegetables and sugars, each reading the matching {@code diet:} tag and the common {@code c:} food tags, so foods
 * from mods that tag them count with no work. A food listed nowhere gets each guessing group's share of its recipe
 * ingredients (first recipe found, ingredients that are in no group left out, up to four steps deep). Recipes come from
 * the server's live recipe manager, read again once the server has started and after every /reload, so recipes other
 * mods add in code count too. The recipe JSON files are only a fallback, for items the live recipes give no recipe for
 * (or before the server has started).
 */
public final class FoodGroups {
    private FoodGroups() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final int DEPTH = 4;

    /** One declared group. {@code entries} are item ids and {@code #tag} ids. */
    public record Group(FId id, String name, FId icon, int color, List<String> entries, Map<FId, Double> values, List<String> exclude,
                        boolean guess) { }

    private static volatile Map<FId, Group> groups = Map.of();
    /** For each recipe result, its first recipe's ingredient slots; each slot is its alternatives (ids or #tags). */
    private static volatile Map<FId, List<List<String>>> recipes = Map.of();
    private static volatile Map<FId, List<List<String>>> jsonRecipes = Map.of();
    private static volatile Map<FId, List<List<String>>> liveRecipes = Map.of();
    private static final Map<Item, Map<FId, Double>> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean dirty;

    static void init() {
        FLife.jsonData(FId.of("fundamentum", "food_groups"), "fundamentum/food_groups", FoodGroups::load);
        FLife.jsonData(FId.of("fundamentum", "food_group_recipes"), FFood.recipeFolder(), FoodGroups::loadRecipes);
        FLife.serverStarted(s -> CACHE.clear());
        FLife.dataReloaded(FoodGroups::rebuild);
        FLife.serverStopped(s -> {
            liveRecipes = Map.of();
            merge();
        });
        FLife.serverTick(s -> {
            if (dirty) {
                dirty = false;
                CACHE.clear();
            }
        });
    }

    // ---- reading

    private static void load(Map<FId, List<JsonElement>> files) {
        Map<FId, Group> out = new TreeMap<>();
        files.forEach((id, copies) -> {
            try {
                JsonObject o = copies.get(copies.size() - 1).getAsJsonObject();
                if (o.has("disabled") && o.get("disabled").getAsBoolean()) return;
                out.put(id, parse(id, o));
            } catch (RuntimeException e) {
                LOG.error("Food group {} could not be read: {}", id, e.toString());
            }
        });
        groups = Collections.unmodifiableMap(out);
        CACHE.clear();
        dirty = true;
        LOG.info("Loaded {} food groups", out.size());
    }

    /** Reads one group file. */
    public static Group parse(FId id, JsonObject o) {
        String name = o.has("name") ? o.get("name").getAsString() : "food_group." + id.namespace() + "." + id.path().replace('/', '.');
        FId icon = o.has("icon") ? FId.tryOf(o.get("icon").getAsString()) : null;
        int color = 0xFFFFFF;
        if (o.has("color")) {
            JsonElement c = o.get("color");
            String s = c.getAsString();
            color = s.startsWith("#") ? Integer.parseInt(s.substring(1), 16) : c.getAsInt();
        }
        List<String> entries = new ArrayList<>();
        entries.add("#" + id.namespace() + ":food_groups/" + id.path());
        for (String s : strings(o.get("items"))) entries.add(s);
        for (String s : strings(o.get("tags"))) entries.add(s.startsWith("#") ? s : "#" + s);
        Map<FId, Double> values = new LinkedHashMap<>();
        if (o.has("values") && o.get("values").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("values").entrySet()) {
                FId item = FId.tryOf(e.getKey());
                if (item != null) values.put(item, Math.max(0, Math.min(1, e.getValue().getAsDouble())));
            }
        }
        boolean guess = !o.has("guess") || o.get("guess").getAsBoolean();
        return new Group(id, name, icon, color, List.copyOf(entries), Collections.unmodifiableMap(values), List.copyOf(strings(o.get("exclude"))), guess);
    }

    private static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
        } else {
            out.add(e.getAsString());
        }
        return out;
    }

    private static void loadRecipes(Map<FId, List<JsonElement>> files) {
        Map<FId, List<List<String>>> out = new HashMap<>();
        files.forEach((id, copies) -> {
            try {
                JsonElement top = copies.get(copies.size() - 1);
                if (!top.isJsonObject()) return;
                JsonObject o = top.getAsJsonObject();
                FId result = result(o.get("result"));
                if (result == null || out.containsKey(result)) return;
                List<List<String>> slots = ingredients(o);
                if (!slots.isEmpty()) out.put(result, slots);
            } catch (RuntimeException e) {
                LOG.debug("Recipe {} skipped for food groups: {}", id, e.toString());
            }
        });
        jsonRecipes = out;
        merge();
    }

    /**
     * Reads the recipes again from the server's live recipe manager. Runs by itself once the server has started and
     * after every /reload; call it after adding or removing recipes in code at another time.
     */
    public static void rebuild(net.minecraft.server.MinecraftServer server) {
        Map<FId, List<List<String>>> live;
        try {
            live = FFood.liveRecipes(server);
        } catch (RuntimeException e) {
            LOG.warn("Food groups could not read the live recipes, using the recipe files: {}", e.toString());
            live = Map.of();
        }
        liveRecipes = live;
        merge();
    }

    private static void merge() {
        Map<FId, List<List<String>>> live = liveRecipes;
        Map<FId, List<List<String>>> out = new HashMap<>(jsonRecipes);
        out.putAll(live);
        recipes = out;
        CACHE.clear();
        dirty = true;
    }

    /** True when the item's recipe came from the live recipe manager rather than the JSON fallback. */
    public static boolean liveRecipe(FId item) {
        return liveRecipes.containsKey(item);
    }

    private static FId result(JsonElement r) {
        if (r == null || r.isJsonNull()) return null;
        if (r.isJsonPrimitive()) return FId.tryOf(r.getAsString());
        if (!r.isJsonObject()) return null;
        JsonObject o = r.getAsJsonObject();
        if (o.has("id")) return FId.tryOf(o.get("id").getAsString());
        if (o.has("item")) return FId.tryOf(o.get("item").getAsString());
        return null;
    }

    private static List<List<String>> ingredients(JsonObject o) {
        List<List<String>> out = new ArrayList<>();
        if (o.has("ingredients") && o.get("ingredients").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("ingredients")) addSlot(out, e);
        } else if (o.has("key") && o.has("pattern") && o.get("key").isJsonObject()) {
            JsonObject key = o.getAsJsonObject("key");
            for (JsonElement row : o.getAsJsonArray("pattern")) {
                for (char ch : row.getAsString().toCharArray()) {
                    String k = String.valueOf(ch);
                    if (key.has(k)) addSlot(out, key.get(k));
                }
            }
        } else if (o.has("ingredient")) {
            addSlot(out, o.get("ingredient"));
        }
        return out;
    }

    private static void addSlot(List<List<String>> out, JsonElement e) {
        List<String> alts = new ArrayList<>();
        alternatives(e, alts);
        if (!alts.isEmpty()) out.add(alts);
    }

    private static void alternatives(JsonElement e, List<String> out) {
        if (e == null || e.isJsonNull()) return;
        if (e.isJsonPrimitive()) {
            String s = e.getAsString();
            if (s.startsWith("#") ? FId.isValid(s.substring(1)) : FId.isValid(s)) out.add(s);
        } else if (e.isJsonArray()) {
            for (JsonElement x : (JsonArray) e) alternatives(x, out);
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("item")) alternatives(o.get("item"), out);
            else if (o.has("tag")) out.add("#" + o.get("tag").getAsString());
        }
    }

    // ---- queries

    /** Every group, by id. */
    public static Map<FId, Group> all() {
        return groups;
    }

    public static Group group(FId id) {
        return groups.get(id);
    }

    /** The stack's share of each group it is in (0 to 1), groups with no share left out. */
    public static Map<FId, Double> groups(ItemStack stack) {
        if (stack.isEmpty()) return Map.of();
        return groupsOf(stack.getItem(), 0);
    }

    /** The stack's share of one group, 0 when it is not in it. */
    public static double value(ItemStack stack, FId group) {
        return groups(stack).getOrDefault(group, 0.0);
    }

    public static boolean in(ItemStack stack, FId group) {
        return value(stack, group) > 0;
    }

    /** The recipe ingredient slots read for an item, for checking guesses. */
    public static List<List<String>> recipeOf(FId item) {
        return recipes.getOrDefault(item, List.of());
    }

    private static Map<FId, Double> groupsOf(Item item, int depth) {
        Map<FId, Double> cached = CACHE.get(item);
        if (cached != null) return cached;
        Map<FId, Double> out = compute(item, depth);
        if (depth == 0) CACHE.put(item, out);
        return out;
    }

    private static Map<FId, Double> compute(Item item, int depth) {
        Map<FId, Group> all = groups;
        if (all.isEmpty() || item == Items.AIR) return Map.of();
        FId id = FVanilla.idOf(item);
        ItemStack stack = new ItemStack(item);
        Map<FId, Double> out = new TreeMap<>();
        boolean excludedSomewhere = false;
        for (Group g : all.values()) {
            if (matchesAny(stack, id, g.exclude)) {
                excludedSomewhere = true;
                continue;
            }
            Double v = g.values.get(id);
            if (v == null && matchesAny(stack, id, g.entries)) v = 1.0;
            if (v != null && v > 0) out.put(g.id, v);
        }
        if (!out.isEmpty() || excludedSomewhere || depth >= DEPTH) return Collections.unmodifiableMap(out);
        List<List<String>> slots = recipes.get(id);
        if (slots == null) return Map.of();
        Map<FId, Double> sum = new HashMap<>();
        int counted = 0;
        for (List<String> slot : slots) {
            Item ing = resolve(slot);
            if (ing == null || ing == item) continue;
            Map<FId, Double> m = groupsOf(ing, depth + 1);
            if (m.isEmpty()) continue;
            counted++;
            m.forEach((g, v) -> sum.merge(g, v, Double::sum));
        }
        if (counted == 0) return Map.of();
        for (Map.Entry<FId, Double> e : sum.entrySet()) {
            Group g = all.get(e.getKey());
            if (g != null && g.guess) out.put(e.getKey(), Math.min(1, e.getValue() / counted));
        }
        return Collections.unmodifiableMap(out);
    }

    private static boolean matchesAny(ItemStack stack, FId id, List<String> entries) {
        for (String s : entries) {
            if (s.startsWith("#")) {
                FId tag = FId.tryOf(s.substring(1));
                if (tag != null && stack.is(FTags.item(tag))) return true;
            } else if (s.equals(id.toString())) {
                return true;
            }
        }
        return false;
    }

    /** The first item an ingredient slot names (an item, or the first item of a tag). */
    private static Item resolve(List<String> slot) {
        for (String s : slot) {
            if (s.startsWith("#")) {
                FId tag = FId.tryOf(s.substring(1));
                if (tag == null) continue;
                for (Holder<Item> h : BuiltInRegistries.ITEM.getTagOrEmpty(FTags.item(tag))) return h.value();
            } else {
                FId id = FId.tryOf(s);
                Item i = id == null ? null : FVanilla.item(id);
                if (i != null && i != Items.AIR) return i;
            }
        }
        return null;
    }
}
