package com.fundamentum.material;

import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Palettes: per role, a weighted list of material sets. Files: {@code data/<ns>/fundamentum/palettes/<name>.json}.
 *
 * <pre>{
 *   "roles": {
 *     "wall":  [{"set": "minecraft:stone_bricks", "weight": 3}, {"set": "minecraft:mossy_stone_bricks", "weight": 1}],
 *     "floor": [{"set": "minecraft:polished_andesite", "weight": 1}],
 *     "roof":  [{"set": "minecraft:dark_oak", "weight": 1}]
 *   },
 *   "fallback": "minecraft:stone_bricks"
 * }</pre>
 * A role without entries (or whose sets are all missing or off) uses {@code fallback}.
 */
public final class Palettes {
    private Palettes() { }

    public record Weighted(Identifier set, int weight) { }

    /** One palette. */
    public record Palette(Map<String, List<Weighted>> roles, @Nullable Identifier fallback) {
        /** Draws the set for a role by weight among sets that exist and are on. */
        public Optional<MaterialSet> pick(String role, RandomSource random) {
            List<MaterialSet> sets = new ArrayList<>();
            List<Integer> weights = new ArrayList<>();
            int total = 0;
            for (Weighted w : this.roles.getOrDefault(role, List.of())) {
                Optional<MaterialSet> s = MaterialSets.get(w.set());
                if (s.isEmpty() || w.weight() <= 0) continue;
                sets.add(s.get());
                weights.add(w.weight());
                total += w.weight();
            }
            if (total > 0) {
                int r = random.nextInt(total);
                for (int i = 0; i < sets.size(); i++) {
                    r -= weights.get(i);
                    if (r < 0) return Optional.of(sets.get(i));
                }
            }
            return this.fallback == null ? Optional.empty() : MaterialSets.get(this.fallback);
        }

        /** A block for a role: draws the set, then the role's slot (role name) and shape. */
        public Optional<BlockState> block(String role, String shape, String wear, RandomSource random) {
            return pick(role, random).flatMap(s -> s.resolve(role, shape, wear));
        }
    }

    public static final DataTable<Palette> TABLE = DataTables.register(Identifier.fromNamespaceAndPath("fundamentum", "palettes"),
        "fundamentum/palettes", Palettes::decode);

    private static final Map<Identifier, Palette> CODE = new ConcurrentHashMap<>();

    public static void init() {
        // class load registers the table
    }

    public static void register(Identifier id, Palette palette) {
        CODE.put(id, palette);
    }

    /** The palette by id: data first, then code. */
    public static Optional<Palette> get(Identifier id) {
        Palette p = TABLE.get(id);
        return Optional.ofNullable(p != null ? p : CODE.get(id));
    }

    static Palette decode(JsonObject json) {
        Map<String, List<Weighted>> roles = new LinkedHashMap<>();
        if (json.has("roles")) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("roles").entrySet()) {
                List<Weighted> list = new ArrayList<>();
                for (JsonElement el : e.getValue().getAsJsonArray()) {
                    if (el.isJsonPrimitive()) {
                        list.add(new Weighted(Identifier.parse(el.getAsString()), 1));
                    } else {
                        JsonObject o = el.getAsJsonObject();
                        list.add(new Weighted(Identifier.parse(o.get("set").getAsString()), o.has("weight") ? o.get("weight").getAsInt() : 1));
                    }
                }
                roles.put(e.getKey(), List.copyOf(list));
            }
        }
        return new Palette(roles, json.has("fallback") ? Identifier.parse(json.get("fallback").getAsString()) : null);
    }
}
