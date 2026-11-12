package com.fundamentum.loot;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * Loot additions: extra loot for existing tables, matched by id or pattern. Files:
 * {@code data/<ns>/fundamentum/loot_additions/<name>.json}. They run from one global loot modifier
 * ({@code fundamentum:additions}), so they reach every table that the loader's loot modifiers reach.
 *
 * <pre>{
 *   "tables": ["minecraft:chests/*", "!minecraft:chests/spawn_bonus_chest"],
 *   "chance": 0.25,
 *   "rolls": 1,
 *   "entries": [{"item": "mymod:relic", "weight": 1}, {"item": "minecraft:gold_ingot", "count": {"min": 2, "max": 5}, "weight": 3}],
 *   "conditions": [{"type": "minecraft:random_chance", "chance": 0.5}],
 *   "replace": false
 * }</pre>
 * In {@code tables}, {@code *} matches anything (slashes too) and a leading {@code !} excludes. Instead of
 * {@code entries} an addition may name a {@code pool} (a fundamentum pool) or a {@code table} (a vanilla loot table) to
 * roll. {@code conditions} are vanilla loot conditions tested against the table's context. {@code replace} clears the
 * table's own loot first (only when this addition rolled).
 */
public final class LootAdditions {
    private LootAdditions() { }

    /** One addition. */
    public static final class Addition {
        final List<Pattern> include = new ArrayList<>();
        final List<Pattern> exclude = new ArrayList<>();
        final float chance;
        final boolean replace;
        final LootPools.@Nullable Pool pool;
        final @Nullable Identifier poolId;
        final @Nullable Identifier table;
        final @Nullable JsonArray conditionsJson;
        private volatile @Nullable List<LootItemCondition> conditions;

        Addition(List<String> tables, float chance, boolean replace, LootPools.@Nullable Pool pool, @Nullable Identifier poolId,
                @Nullable Identifier table, @Nullable JsonArray conditions) {
            for (String t : tables) {
                if (t.startsWith("!")) this.exclude.add(glob(t.substring(1)));
                else this.include.add(glob(t));
            }
            this.chance = chance;
            this.replace = replace;
            this.pool = pool;
            this.poolId = poolId;
            this.table = table;
            this.conditionsJson = conditions;
        }

        /** Whether this addition targets the table. */
        public boolean matches(Identifier table) {
            String s = table.toString();
            boolean in = false;
            for (Pattern p : this.include) if (p.matcher(s).matches()) { in = true; break; }
            if (!in) return false;
            for (Pattern p : this.exclude) if (p.matcher(s).matches()) return false;
            return true;
        }

        boolean test(LootContext context) {
            if (this.chance < 1f && context.getRandom().nextFloat() >= this.chance) return false;
            if (this.conditionsJson == null) return true;
            List<LootItemCondition> c = this.conditions;
            if (c == null) {
                var ops = RegistryOps.create(JsonOps.INSTANCE, context.getLevel().registryAccess());
                c = LootItemCondition.DIRECT_CODEC.listOf().parse(ops, this.conditionsJson).resultOrPartial(
                    err -> LoggerFactory.getLogger("fundamentum").error("loot addition conditions: {}", err)).orElse(List.of());
                this.conditions = c;
            }
            for (LootItemCondition cond : c) if (!cond.test(context)) return false;
            return true;
        }

        void roll(LootContext context, Consumer<ItemStack> out) {
            if (this.pool != null) {
                LootCode.rollInline(this.pool, context, out);
            } else if (this.poolId != null) {
                LootPools.roll(this.poolId, context.getRandom(), context, out, 0);
            } else if (this.table != null) {
                LootPools.rollTable(this.table, context, out);
            }
        }
    }

    public static final DataTable<Addition> TABLE = DataTables.register(Identifier.fromNamespaceAndPath("fundamentum", "loot_additions"),
        "fundamentum/loot_additions", LootAdditions::decode);

    private static final Map<Identifier, Addition> CODE = new LinkedHashMap<>();

    /** Registers an addition from code (a data file with the same id wins). */
    public static synchronized void register(Identifier id, Addition addition) {
        CODE.put(id, addition);
    }

    /** Builds an addition in code: target patterns and a pool (inline). */
    public static Addition of(List<String> tables, float chance, LootPools.Pool pool) {
        return new Addition(tables, chance, false, pool, null, null, null);
    }

    /** Builds an addition in code that rolls a pool by id. */
    public static Addition ofPool(List<String> tables, float chance, Identifier pool) {
        return new Addition(tables, chance, false, null, pool, null, null);
    }

    /** Every addition: code ones, then data (data replaces code with the same id). */
    public static Map<Identifier, Addition> all() {
        Map<Identifier, Addition> out = new LinkedHashMap<>(CODE);
        out.putAll(TABLE.entries());
        return out;
    }

    /** Runs every matching addition on a table's loot (the global loot modifier calls this). */
    public static void apply(Identifier tableId, List<ItemStack> loot, LootContext context) {
        for (var entry : all().entrySet()) {
            Addition a = entry.getValue();
            if (!a.matches(tableId) || !a.test(context)) continue;
            List<ItemStack> extra = new ArrayList<>();
            a.roll(context, extra::add);
            if (FEvents.post(new Events.LootAdded(FId.from(tableId), FId.from(entry.getKey()), extra.size()))) continue;
            if (a.replace) loot.clear();
            loot.addAll(extra);
        }
    }

    static Addition decode(JsonObject json) {
        List<String> tables = new ArrayList<>();
        JsonElement t = json.get("tables");
        if (t == null) t = json.get("table_ids");
        if (t == null) throw new IllegalArgumentException("loot addition needs \"tables\"");
        if (t.isJsonArray()) t.getAsJsonArray().forEach(x -> tables.add(x.getAsString()));
        else tables.add(t.getAsString());
        float chance = json.has("chance") ? json.get("chance").getAsFloat() : 1f;
        boolean replace = json.has("replace") && json.get("replace").getAsBoolean();
        LootPools.Pool pool = json.has("entries") ? LootPools.decode(json) : null;
        Identifier poolId = json.has("pool") ? Identifier.parse(json.get("pool").getAsString()) : null;
        Identifier table = json.has("table") ? Identifier.parse(json.get("table").getAsString()) : null;
        if (pool == null && poolId == null && table == null) throw new IllegalArgumentException("loot addition needs entries, pool or table");
        return new Addition(tables, chance, replace, pool, poolId, table, json.has("conditions") ? json.getAsJsonArray("conditions") : null);
    }

    static Pattern glob(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '*') b.append(".*");
            else b.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(b.toString());
    }
}
