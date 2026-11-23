package com.fundamentum.loot;

import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jspecify.annotations.Nullable;

/**
 * Weighted pools, reusable by id from loot additions, loot tables ({@code fundamentum:from_pool}), boss drops and code.
 * Files: {@code data/<ns>/fundamentum/loot_pools/<name>.json}.
 *
 * <pre>{
 *   "rolls": {"min": 1, "max": 2},
 *   "entries": [
 *     {"item": "minecraft:diamond", "count": {"min": 1, "max": 3}, "weight": 1},
 *     {"item": "minecraft:emerald", "weight": 3},
 *     {"pool": "mymod:gems", "weight": 2},
 *     {"table": "minecraft:chests/simple_dungeon", "weight": 1},
 *     {"empty": true, "weight": 4}
 *   ]
 * }</pre>
 * An entry whose item is missing (an optional mod's) counts as empty. A {@code table} entry rolls a vanilla loot table and
 * needs a loot context (it is skipped in context-free rolls).
 */
public final class LootPools {
    private LootPools() { }

    /** One pool: rolls (min..max) and weighted entries. */
    public record Pool(int minRolls, int maxRolls, List<Entry> entries) {
        public int totalWeight() {
            int t = 0;
            for (Entry e : this.entries) t += e.weight;
            return t;
        }
    }

    /** One entry: an item stack range, a nested pool, a vanilla table, or nothing. */
    public record Entry(@Nullable Identifier item, int minCount, int maxCount, @Nullable Identifier pool, @Nullable Identifier table, int weight) {
        public boolean empty() {
            return this.item == null && this.pool == null && this.table == null;
        }
    }

    public static final DataTable<Pool> TABLE = DataTables.register(Identifier.fromNamespaceAndPath("fundamentum", "loot_pools"),
        "fundamentum/loot_pools", LootPools::decode);

    public static void init() {
        // class load registers the table
    }

    /** The pool by id: data first, then code. */
    public static @Nullable Pool get(Identifier id) {
        Pool p = TABLE.get(id);
        return p != null ? p : LootCode.POOLS.get(id);
    }

    /** Registers a pool from code (a data file with the same id wins). */
    public static void register(Identifier id, Pool pool) {
        LootCode.POOLS.put(id, pool);
    }

    /** Rolls a pool without a loot context (table entries are skipped). */
    public static List<ItemStack> roll(Identifier id, RandomSource random) {
        List<ItemStack> out = new ArrayList<>();
        roll(id, random, null, out::add, 0);
        return out;
    }

    /** Rolls a pool inside a loot context (table entries roll their table). */
    public static List<ItemStack> roll(Identifier id, LootContext context) {
        List<ItemStack> out = new ArrayList<>();
        roll(id, context.getRandom(), context, out::add, 0);
        return out;
    }

    /** Draws one entry of a pool by weight (or null when the pool is missing or has no weight). */
    public static @Nullable Entry draw(Pool pool, RandomSource random) {
        int total = pool.totalWeight();
        if (total <= 0) return null;
        int r = random.nextInt(total);
        for (Entry e : pool.entries) {
            r -= e.weight;
            if (r < 0) return e;
        }
        return null;
    }

    static void roll(Identifier id, RandomSource random, @Nullable LootContext context, Consumer<ItemStack> out, int depth) {
        Pool pool = get(id);
        if (pool != null) rollPool(pool, random, context, out, depth);
    }

    /** Rolls a pool object (inline pools, code). */
    public static void rollPool(Pool pool, RandomSource random, @Nullable LootContext context, Consumer<ItemStack> out, int depth) {
        if (depth > 8) return;
        int rolls = pool.minRolls + (pool.maxRolls > pool.minRolls ? random.nextInt(pool.maxRolls - pool.minRolls + 1) : 0);
        for (int i = 0; i < rolls; i++) {
            Entry e = draw(pool, random);
            if (e == null || e.empty()) continue;
            if (e.pool != null) {
                roll(e.pool, random, context, out, depth + 1);
            } else if (e.table != null) {
                if (context != null) rollTable(e.table, context, out);
            } else {
                Item item = BuiltInRegistries.ITEM.getValue(e.item);
                if (item == null || item == net.minecraft.world.item.Items.AIR) continue;
                int count = e.minCount + (e.maxCount > e.minCount ? random.nextInt(e.maxCount - e.minCount + 1) : 0);
                if (count > 0) out.accept(new ItemStack(item, count));
            }
        }
    }

    @SuppressWarnings("deprecation")
    static void rollTable(Identifier table, LootContext context, Consumer<ItemStack> out) {
        context.getResolver().lookupOrThrow(Registries.LOOT_TABLE).get(ResourceKey.create(Registries.LOOT_TABLE, table))
            .ifPresent(t -> t.value().getRandomItemsRaw(context, LootTable.createStackSplitter(context.getLevel(), out)));
    }

    /** Whether the pool exists and can give at least one item that exists. */
    public static boolean usable(Identifier id) {
        return usable(id, 0);
    }

    private static boolean usable(Identifier id, int depth) {
        Pool p = get(id);
        if (p == null || depth > 8) return false;
        for (Entry e : p.entries) {
            if (e.weight <= 0) continue;
            if (e.item != null && BuiltInRegistries.ITEM.containsKey(e.item)) return true;
            if (e.table != null) return true;
            if (e.pool != null && usable(e.pool, depth + 1)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ json
    static Pool decode(JsonObject json) {
        int[] rolls = range(json.get("rolls"), 1);
        List<Entry> entries = new ArrayList<>();
        for (JsonElement el : json.getAsJsonArray("entries")) entries.add(entry(el.getAsJsonObject()));
        return new Pool(rolls[0], rolls[1], List.copyOf(entries));
    }

    static Entry entry(JsonObject o) {
        int weight = o.has("weight") ? o.get("weight").getAsInt() : 1;
        int[] count = range(o.get("count"), 1);
        Identifier item = o.has("item") ? Identifier.parse(o.get("item").getAsString()) : null;
        Identifier pool = o.has("pool") ? Identifier.parse(o.get("pool").getAsString()) : null;
        Identifier table = o.has("table") ? Identifier.parse(o.get("table").getAsString()) : null;
        if (item == null && pool == null && table == null && !(o.has("empty") && o.get("empty").getAsBoolean())) {
            throw new IllegalArgumentException("entry needs item, pool, table or \"empty\": true");
        }
        return new Entry(item, count[0], count[1], pool, table, weight);
    }

    /** An int, or {"min": a, "max": b}. */
    static int[] range(@Nullable JsonElement e, int fallback) {
        if (e == null) return new int[] {fallback, fallback};
        if (e.isJsonPrimitive()) return new int[] {e.getAsInt(), e.getAsInt()};
        JsonObject o = e.getAsJsonObject();
        int min = o.has("min") ? o.get("min").getAsInt() : fallback;
        int max = o.has("max") ? o.get("max").getAsInt() : min;
        return new int[] {min, Math.max(min, max)};
    }
}
