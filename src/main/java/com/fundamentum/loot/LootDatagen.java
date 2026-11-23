package com.fundamentum.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

/**
 * Datagen for fundamentum pools and loot additions. Subclass it (or pass a filler) and add it to your data generator:
 *
 * <pre>event.createProvider(out -> new LootDatagen(out, "mymod", g -> {
 *     g.pool("gems").rolls(1, 2).item("minecraft:diamond", 1).item("minecraft:emerald", 3).empty(4);
 *     g.addition("dungeon_relics").tables("minecraft:chests/*", "!minecraft:chests/spawn_bonus_chest").chance(0.2f).pool("mymod:gems");
 * }));</pre>
 */
public class LootDatagen implements DataProvider {
    private final PackOutput output;
    private final String modid;
    private final Consumer<LootDatagen> filler;
    private final Map<String, JsonObject> pools = new LinkedHashMap<>();
    private final Map<String, JsonObject> additions = new LinkedHashMap<>();

    public LootDatagen(PackOutput output, String modid, Consumer<LootDatagen> filler) {
        this.output = output;
        this.modid = modid;
        this.filler = filler;
    }

    /** Override to add pools and additions instead of passing a filler. */
    protected void addAll() {
        this.filler.accept(this);
    }

    public PoolBuilder pool(String name) {
        JsonObject o = new JsonObject();
        o.add("entries", new JsonArray());
        this.pools.put(name, o);
        return new PoolBuilder(o);
    }

    public AdditionBuilder addition(String name) {
        JsonObject o = new JsonObject();
        this.additions.put(name, o);
        return new AdditionBuilder(o);
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        this.pools.clear();
        this.additions.clear();
        addAll();
        var root = this.output.getOutputFolder(PackOutput.Target.DATA_PACK).resolve(this.modid).resolve("fundamentum");
        List<CompletableFuture<?>> all = new ArrayList<>();
        this.pools.forEach((n, j) -> all.add(DataProvider.saveStable(cache, j, root.resolve("loot_pools").resolve(n + ".json"))));
        this.additions.forEach((n, j) -> all.add(DataProvider.saveStable(cache, j, root.resolve("loot_additions").resolve(n + ".json"))));
        return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new));
    }

    @Override
    public String getName() {
        return "Fundamentum loot: " + this.modid;
    }

    /** Entries for a pool or an inline addition. */
    public static class EntriesBuilder<B extends EntriesBuilder<B>> {
        protected final JsonObject json;

        EntriesBuilder(JsonObject json) {
            this.json = json;
        }

        @SuppressWarnings("unchecked")
        private B self() {
            return (B) this;
        }

        private JsonArray entries() {
            if (!this.json.has("entries")) this.json.add("entries", new JsonArray());
            return this.json.getAsJsonArray("entries");
        }

        public B rolls(int min, int max) {
            JsonObject r = new JsonObject();
            r.addProperty("min", min);
            r.addProperty("max", max);
            this.json.add("rolls", r);
            return self();
        }

        public B item(String item, int weight) {
            return item(item, 1, 1, weight);
        }

        public B item(String item, int minCount, int maxCount, int weight) {
            JsonObject e = new JsonObject();
            e.addProperty("item", item);
            if (minCount != 1 || maxCount != 1) {
                JsonObject c = new JsonObject();
                c.addProperty("min", minCount);
                c.addProperty("max", maxCount);
                e.add("count", c);
            }
            e.addProperty("weight", weight);
            entries().add(e);
            return self();
        }

        public B poolEntry(String pool, int weight) {
            JsonObject e = new JsonObject();
            e.addProperty("pool", pool);
            e.addProperty("weight", weight);
            entries().add(e);
            return self();
        }

        public B tableEntry(String table, int weight) {
            JsonObject e = new JsonObject();
            e.addProperty("table", table);
            e.addProperty("weight", weight);
            entries().add(e);
            return self();
        }

        public B empty(int weight) {
            JsonObject e = new JsonObject();
            e.addProperty("empty", true);
            e.addProperty("weight", weight);
            entries().add(e);
            return self();
        }
    }

    public static final class PoolBuilder extends EntriesBuilder<PoolBuilder> {
        PoolBuilder(JsonObject json) {
            super(json);
        }
    }

    public static final class AdditionBuilder extends EntriesBuilder<AdditionBuilder> {
        AdditionBuilder(JsonObject json) {
            super(json);
        }

        public AdditionBuilder tables(String... patterns) {
            JsonArray a = new JsonArray();
            for (String p : patterns) a.add(p);
            this.json.add("tables", a);
            return this;
        }

        public AdditionBuilder chance(float chance) {
            this.json.addProperty("chance", chance);
            return this;
        }

        public AdditionBuilder replace() {
            this.json.addProperty("replace", true);
            return this;
        }

        public AdditionBuilder pool(String pool) {
            this.json.addProperty("pool", pool);
            return this;
        }

        public AdditionBuilder table(String table) {
            this.json.addProperty("table", table);
            return this;
        }

        /** A vanilla loot condition, as JSON. */
        public AdditionBuilder condition(JsonObject condition) {
            if (!this.json.has("conditions")) this.json.add("conditions", new JsonArray());
            this.json.getAsJsonArray("conditions").add(condition);
            return this;
        }
    }
}
