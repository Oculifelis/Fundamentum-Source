package com.fundamentum.material;

import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Material sets: groups of blocks that build together (stone bricks with their stairs, slab, wall, cracked and mossy
 * kinds; oak planks with log, stripped log, door, fence...). Three sources, merged in order: the scan at server start
 * ({@link MaterialDetect}), code ({@link #register}, {@link #patch}), then datapacks
 * ({@code data/<ns>/fundamentum/material_sets/<name>.json}), which may add, change or remove any slot, shape or whole
 * set.
 *
 * <pre>{
 *   "set": "minecraft:stone_bricks",            // the set this file edits (default: the file id)
 *   "kind": "stone", "family": "minecraft:stone_bricks", "weight": 2, "dimension": "overworld",
 *   "tags": ["castle", "grey"], "remove_tags": ["mid"], "biome_tags": ["#minecraft:is_mountain"],
 *   "color": "#7a7a7a", "light": "minecraft:lantern", "bars": "minecraft:iron_bars",
 *   "slots": {
 *     "main": {"wall": "minecraft:deepslate_brick_wall"},
 *     "wall_accent": {"base": "minecraft:chiseled_stone_bricks", "stairs": "...", "slab": "..."},
 *     "floor": {"base": "minecraft:polished_andesite", "base:cracked": "minecraft:cobblestone"}
 *   },
 *   "roles": {"stairs": "minecraft:stone_brick_stairs"}, // same as slots.main
 *   "remove_slots": ["roof", "main:fence", "main:base:mossy"],
 *   "replace": false,                           // true: drop the scanned and code blocks of this set first
 *   "enabled": true,                            // false turns the set off
 *   "remove": false                             // true deletes the set
 * }</pre>
 */
public final class MaterialSets {
    private MaterialSets() { }

    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");

    /** Missing slot -> the slot that stands in for it. Every chain ends at "main". */
    public static final Map<String, String> SLOT_FALLBACK = Map.ofEntries(
        Map.entry("wall", "main"), Map.entry("wall_accent", "wall"), Map.entry("floor", "main"), Map.entry("floor_accent", "floor"),
        Map.entry("pillar", "main"), Map.entry("trim", "wall_accent"), Map.entry("ceiling", "floor"), Map.entry("rubble", "main"),
        Map.entry("foundation", "wall"), Map.entry("roof", "main"), Map.entry("roof_b", "roof"), Map.entry("roof_c", "roof_b"),
        Map.entry("quoin", "wall_accent"), Map.entry("paving", "floor"), Map.entry("carved", "main"), Map.entry("statue", "carved"),
        Map.entry("plaster", "main"), Map.entry("planks", "main"), Map.entry("post", "log"), Map.entry("timber", "stripped"),
        Map.entry("stripped", "log"));

    public static final DataTable<JsonObject> TABLE = DataTables.registerJson(ResourceLocation.fromNamespaceAndPath("fundamentum", "material_sets"),
        "fundamentum/material_sets").keyField("set").onLoad(t -> rebuild());

    private static final List<MaterialSet.Builder> CODE_SETS = new CopyOnWriteArrayList<>();
    private static final List<Map.Entry<ResourceLocation, Consumer<MaterialSet.Builder>>> CODE_PATCHES = new CopyOnWriteArrayList<>();
    private static final List<Predicate<String>> DISABLED_CHECKS = new CopyOnWriteArrayList<>();

    private static volatile Map<ResourceLocation, MaterialSet> sets = Map.of();
    private static volatile Set<ResourceLocation> dataDisabled = Set.of();
    private static volatile boolean built;
    private static volatile long generation;

    /** Goes up by one each time the sets are rebuilt (processors re-read their candidates). */
    public static long generation() {
        ensure();
        return generation;
    }

    public static void init() {
        // class load registers the data table
    }

    // ------------------------------------------------------------------------------------------------ code
    /** Adds a set from code (before the server starts; datapacks may still change it). */
    public static void register(MaterialSet.Builder set) {
        CODE_SETS.add(set);
        built = false;
    }

    /** Changes a set (scanned, registered or not yet existing) from code, after the scan and before datapacks. */
    public static void patch(ResourceLocation set, Consumer<MaterialSet.Builder> patch) {
        CODE_PATCHES.add(Map.entry(set, patch));
        built = false;
    }

    /** Adds a test that turns sets off by id string (true = off), on top of the config list. */
    public static void addDisabledCheck(Predicate<String> disabled) {
        DISABLED_CHECKS.add(disabled);
    }

    // ------------------------------------------------------------------------------------------------ queries
    /** Every enabled set. */
    public static Collection<MaterialSet> all() {
        ensure();
        List<MaterialSet> out = new ArrayList<>();
        for (MaterialSet s : sets.values()) if (enabled(s.id())) out.add(s);
        return out;
    }

    /** Every set, turned off ones included. */
    public static Map<ResourceLocation, MaterialSet> allIncludingDisabled() {
        ensure();
        return sets;
    }

    /** The set by id, if it exists and is on. */
    public static Optional<MaterialSet> get(ResourceLocation id) {
        ensure();
        MaterialSet s = sets.get(id);
        return s != null && enabled(id) ? Optional.of(s) : Optional.empty();
    }

    /** Sets with a theme tag. */
    public static List<MaterialSet> withTag(String tag) {
        List<MaterialSet> out = new ArrayList<>();
        for (MaterialSet s : all()) if (s.tags().contains(tag)) out.add(s);
        return out;
    }

    /** The first set holding this block anywhere, if any (main slot and plain wear preferred). */
    public static Optional<MaterialSet> of(Block block) {
        MaterialSet best = null;
        for (MaterialSet s : all()) {
            Location l = s.locate(block);
            if (l == null) continue;
            if ("main".equals(l.slot()) && l.wear().isEmpty() && s.id().equals(BuiltInRegistries.BLOCK.getKey(block))) return Optional.of(s);
            if (best == null) best = s;
        }
        return Optional.ofNullable(best);
    }

    /**
     * A block of a set by role name: a shape ({@code "stairs"}, main slot), {@code "slot:shape"} or
     * {@code "slot:shape:wear"}. Missing slots follow {@link #SLOT_FALLBACK}, missing shapes (not wall or fence) and wears
     * fall back to base and plain.
     */
    public static Optional<BlockState> role(ResourceLocation set, String role) {
        return get(set).flatMap(s -> role(s, role));
    }

    public static Optional<BlockState> role(MaterialSet set, String role) {
        String[] p = role.split(":", 3);
        if (p.length == 1) return set.resolve("main", p[0], "");
        return set.resolve(p[0], p[1], p.length > 2 ? p[2] : "");
    }

    public static boolean enabled(ResourceLocation id) {
        if (dataDisabled.contains(id)) return false;
        String s = id.toString();
        for (String d : MaterialConfig.disabled()) {
            if (d.equals(s) || d.endsWith(":*") && s.startsWith(d.substring(0, d.length() - 1))) return false;
        }
        for (Predicate<String> p : DISABLED_CHECKS) if (p.test(s)) return false;
        return true;
    }

    /** Where a block sits in a set. */
    public record Location(String slot, String shape, String wear) { }

    // ------------------------------------------------------------------------------------------------ ranked query
    /** One ranked answer. Higher scores are better matches. */
    public record Match(MaterialSet set, double score) { }

    public static Query query() {
        return new Query();
    }

    public static Query query(MaterialSet.Kind kind) {
        return new Query().kind(kind);
    }

    /**
     * A ranked search: kind, required usable slots, theme tags (soft, or required), a target colour, a biome, alien sets
     * allowed or not (not by default), family. {@link #run} returns usable sets, best first; a vanilla set of the kind is
     * always in the list when one fits the hard rules.
     */
    public static final class Query {
        private @Nullable MaterialSet.Kind kind;
        private final List<String> slots = new ArrayList<>();
        private final List<String> tags = new ArrayList<>();
        private final List<String> requireTags = new ArrayList<>();
        private float @Nullable [] color;
        private @Nullable ResourceLocation biome;
        private final Set<String> biomeTags = new HashSet<>();
        private boolean allowAlien;
        private @Nullable String dimension;
        private @Nullable String family;
        private int limit = Integer.MAX_VALUE;

        public Query kind(MaterialSet.Kind kind) { this.kind = kind; return this; }
        public Query slots(String... slots) { Collections.addAll(this.slots, slots); return this; }
        public Query slots(Collection<String> slots) { this.slots.addAll(slots); return this; }
        /** Soft theme tags: each one a set has raises its score. */
        public Query tags(String... tags) { Collections.addAll(this.tags, tags); return this; }
        public Query tags(Collection<String> tags) { this.tags.addAll(tags); return this; }
        /** Hard tags: a set without all of them is left out. */
        public Query requireTags(String... tags) { Collections.addAll(this.requireTags, tags); return this; }
        public Query color(int rgb) { this.color = MaterialColor.lab(rgb); return this; }
        public Query color(String hex) { return color(MaterialColor.parseHex(hex)); }
        public Query lab(float l, float a, float b) { this.color = new float[] {l, a, b}; return this; }
        /** The biome by id plus the tag ids it is in (written "#ns:path" or "ns:path"). */
        public Query biome(ResourceLocation biome, Collection<String> tags) {
            this.biome = biome;
            for (String t : tags) this.biomeTags.add(t.startsWith("#") ? t : "#" + t);
            return this;
        }
        public Query biome(Holder<Biome> biome) {
            biome.unwrapKey().ifPresent(k -> this.biome = k.location());
            biome.tags().forEach(t -> this.biomeTags.add("#" + t.location()));
            return this;
        }
        /** Allows nether, end, sky and magic sets (overworld buildings leave this off). */
        public Query allowAlien() { this.allowAlien = true; return this; }
        /** Only sets of this dimension tag ("nether", "end", ...); implies alien allowed. */
        public Query dimension(String dimension) { this.dimension = dimension; this.allowAlien = true; return this; }
        /** Only sets of this family (a family stands in only for itself). */
        public Query family(String family) { this.family = family; return this; }
        public Query limit(int limit) { this.limit = limit; return this; }

        boolean hard(MaterialSet s) {
            if (this.kind != null && s.kind() != this.kind) return false;
            if (!this.allowAlien && s.alien()) return false;
            if (this.dimension != null && !this.dimension.equals(s.dimension())) return false;
            if (this.family != null && !this.family.equals(s.family())) return false;
            if (!s.tags().containsAll(this.requireTags)) return false;
            if (this.slots.isEmpty()) return s.usable("main");
            return s.usable(this.slots);
        }

        double score(MaterialSet s) {
            double score = Math.log(Math.max(0.01, s.weight()));
            for (String t : this.tags) if (s.tags().contains(t)) score += 2;
            if (this.color != null) score -= MaterialColor.distance(this.color, s.lab()) / 10.0;
            if (this.biome != null && !s.biomeTags().isEmpty()) {
                boolean hit = false;
                for (String t : s.biomeTags()) {
                    if (t.startsWith("#") ? this.biomeTags.contains(t) : t.equals(this.biome.toString())) { hit = true; break; }
                }
                score += hit ? 3 : -3;
            }
            return score;
        }

        public List<Match> run() {
            List<Match> out = new ArrayList<>();
            for (MaterialSet s : all()) if (hard(s)) out.add(new Match(s, score(s)));
            out.sort(ORDER);
            List<Match> top = new ArrayList<>(out.subList(0, Math.min(this.limit, out.size())));
            boolean vanilla = false;
            for (Match m : top) if (m.set().isVanilla()) { vanilla = true; break; }
            if (!vanilla) {
                for (Match m : out) if (m.set().isVanilla()) { top.add(m); vanilla = true; break; }
            }
            if (!vanilla) {
                // soft rules aside, the best vanilla set that passes kind, alien and slot rules
                Query loose = new Query();
                loose.kind = this.kind;
                loose.slots.addAll(this.slots);
                loose.allowAlien = this.allowAlien;
                loose.dimension = this.dimension;
                loose.color = this.color;
                Match best = null;
                for (MaterialSet s : all()) {
                    if (!s.isVanilla() || !loose.hard(s)) continue;
                    Match m = new Match(s, loose.score(s) - 1000);
                    if (best == null || ORDER.compare(m, best) < 0) best = m;
                }
                if (best != null) top.add(best);
            }
            return top;
        }
    }

    private static final Comparator<Match> ORDER = Comparator.comparingDouble((Match m) -> -m.score()).thenComparing(m -> m.set().id().toString());

    // ------------------------------------------------------------------------------------------------ seeded picks
    /** Splitmix64 finaliser (the same one Orbis Novus uses). */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A seed for one chunk of one world: the same inputs always give the same pick. */
    public static long seed(long worldSeed, int chunkX, int chunkZ, long salt) {
        long pos = (long) chunkX & 0xFFFFFFFFL | ((long) chunkZ & 0xFFFFFFFFL) << 32;
        return mix(worldSeed ^ mix(pos)) ^ salt;
    }

    /** Weighted pick (by set weight) among the first {@code topN} matches; null for an empty list. */
    public static @Nullable MaterialSet pick(List<Match> ranked, long seed, int topN) {
        int n = Math.min(topN, ranked.size());
        if (n <= 0) return null;
        double total = 0;
        for (int i = 0; i < n; i++) total += Math.max(0, ranked.get(i).set().weight());
        if (total <= 0) return ranked.get(0).set();
        double r = new java.util.Random(seed).nextDouble() * total;
        for (int i = 0; i < n; i++) {
            r -= Math.max(0, ranked.get(i).set().weight());
            if (r < 0) return ranked.get(i).set();
        }
        return ranked.get(n - 1).set();
    }

    /** A second pick for mixing, different from {@code first} when the top matches allow it. */
    public static @Nullable MaterialSet pickMix(List<Match> ranked, long seed, int topN, @Nullable MaterialSet first) {
        List<Match> rest = new ArrayList<>();
        for (Match m : ranked.subList(0, Math.min(topN, ranked.size()))) if (m.set() != first) rest.add(m);
        return rest.isEmpty() ? first : pick(rest, seed ^ 0x5DEECE66DL, rest.size());
    }

    // ------------------------------------------------------------------------------------------------ build
    private static void ensure() {
        if (!built) rebuild();
    }

    /** Rebuilds every set from the scan, code and data. Runs after each datapack load. */
    public static synchronized void rebuild() {
        Map<ResourceLocation, MaterialSet.Builder> b = new LinkedHashMap<>();
        if (MaterialConfig.autoDetect()) {
            try {
                b.putAll(MaterialDetect.scan());
            } catch (Throwable t) {
                LOG.error("material set scan failed", t);
            }
        }
        for (MaterialSet.Builder code : CODE_SETS) b.put(code.id, copy(code));
        for (var p : CODE_PATCHES) {
            try {
                p.getValue().accept(b.computeIfAbsent(p.getKey(), MaterialSet.Builder::new));
            } catch (Throwable t) {
                LOG.error("material set patch for {} failed", p.getKey(), t);
            }
        }
        Set<ResourceLocation> off = new HashSet<>();
        for (Map.Entry<ResourceLocation, JsonObject> e : TABLE.rawEntries().entrySet()) {
            try {
                apply(b, e.getKey(), e.getValue(), off);
            } catch (Throwable t) {
                LOG.error("material set {}: {}", e.getKey(), t.toString());
            }
        }
        Map<ResourceLocation, MaterialSet> out = new LinkedHashMap<>();
        for (MaterialSet.Builder x : b.values()) {
            if (x.slots.isEmpty()) continue;
            MaterialSet first = x.build();
            MaterialDetect.autoTags(x, first.rgb());
            x.tags.removeAll(x.removedTags);
            out.put(x.id, x.build());
        }
        sets = Collections.unmodifiableMap(out);
        dataDisabled = Set.copyOf(off);
        built = true;
        generation++;
    }

    private static MaterialSet.Builder copy(MaterialSet.Builder s) {
        MaterialSet.Builder c = new MaterialSet.Builder(s.id);
        c.kind = s.kind;
        c.family = s.family;
        c.source = s.source;
        c.weight = s.weight;
        c.tags.addAll(s.tags);
        c.removedTags.addAll(s.removedTags);
        c.biomeTags.addAll(s.biomeTags);
        c.dimension = s.dimension;
        c.color = s.color;
        c.light = s.light;
        c.bars = s.bars;
        c.detected = s.detected;
        s.slots.forEach((slot, shapes) -> shapes.forEach((shape, wears) -> wears.forEach((w, blk) -> c.block(slot, shape, w, blk))));
        return c;
    }

    static void apply(Map<ResourceLocation, MaterialSet.Builder> all, ResourceLocation key, JsonObject j, Set<ResourceLocation> off) {
        if (j.has("remove") && j.get("remove").getAsBoolean()) {
            all.remove(key);
            return;
        }
        MaterialSet.Builder b = all.computeIfAbsent(key, MaterialSet.Builder::new);
        if (j.has("replace") && j.get("replace").getAsBoolean()) b.slots.clear();
        if (j.has("enabled") && !j.get("enabled").getAsBoolean()) off.add(key);
        if (j.has("kind")) b.kind = MaterialSet.Kind.parse(j.get("kind").getAsString());
        if (j.has("family")) b.family = j.get("family").getAsString();
        if (j.has("source")) b.source = j.get("source").getAsString();
        if (j.has("weight")) b.weight = j.get("weight").getAsFloat();
        if (j.has("dimension")) b.dimension = j.get("dimension").getAsString();
        if (j.has("tags")) for (JsonElement t : j.getAsJsonArray("tags")) b.tags.add(t.getAsString());
        if (j.has("remove_tags")) for (JsonElement t : j.getAsJsonArray("remove_tags")) { b.tags.remove(t.getAsString()); b.removedTags.add(t.getAsString()); }
        if (j.has("biome_tags")) for (JsonElement t : j.getAsJsonArray("biome_tags")) b.biomeTags.add(t.getAsString());
        if (j.has("color")) b.color = MaterialColor.parseHex(j.get("color").getAsString());
        if (j.has("light")) b.light = blockOrWarn(key, j.get("light").getAsString());
        if (j.has("bars")) b.bars = blockOrWarn(key, j.get("bars").getAsString());
        if (j.has("roles")) slot(b, key, "main", j.getAsJsonObject("roles"));
        if (j.has("slots")) {
            for (Map.Entry<String, JsonElement> s : j.getAsJsonObject("slots").entrySet()) {
                if (s.getValue().isJsonObject()) slot(b, key, s.getKey(), s.getValue().getAsJsonObject());
            }
        }
        if (j.has("remove_slots")) {
            JsonArray rs = j.getAsJsonArray("remove_slots");
            for (JsonElement r : rs) {
                String[] p = r.getAsString().split(":", 3);
                if (p.length == 1) b.removeSlot(p[0]);
                else if (p.length == 2) b.removeShape(p[0], p[1]);
                else b.removeBlock(p[0], p[1], p[2]);
            }
        }
    }

    private static void slot(MaterialSet.Builder b, ResourceLocation key, String slot, JsonObject shapes) {
        for (Map.Entry<String, JsonElement> e : shapes.entrySet()) {
            String[] p = e.getKey().split(":", 2);
            String shape = p[0], wear = p.length > 1 ? p[1] : "";
            if (e.getValue().isJsonNull()) {
                b.removeBlock(slot, shape, wear);
                continue;
            }
            Block block = blockOrWarn(key, e.getValue().getAsString());
            if (block != null) b.block(slot, shape, wear, block);
        }
    }

    private static @Nullable Block blockOrWarn(ResourceLocation set, String id) {
        ResourceLocation bid = ResourceLocation.tryParse(id);
        if (bid == null || !BuiltInRegistries.BLOCK.containsKey(bid)) {
            LOG.debug("material set {}: no block {}", set, id);
            return null;
        }
        return BuiltInRegistries.BLOCK.get(bid);
    }
}
