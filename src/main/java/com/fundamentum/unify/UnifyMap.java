package com.fundamentum.unify;

import com.fundamentum.port.FId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which item stands in for which: built from the priority rules and the item tags they name, plus the per-mod recipe fix
 * files. Immutable once built. Also rewrites recipe JSON so recipes give the kept item. The same file on every version.
 */
public final class UnifyMap {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum/unify");
    /** Where recipes keep their output, unless a fix file says otherwise. */
    public static final List<String> DEFAULT_PATHS = List.of("result", "results", "output", "outputs");
    public static final UnifyMap EMPTY = new UnifyMap();

    /** A fix file's paths for one recipe type. */
    record TypeFix(List<String[]> paths, boolean replaceDefaults) { }

    private final Map<FId, FId> to = new HashMap<>();
    private final Map<FId, FId> tagOf = new HashMap<>();
    private final Set<FId> ores = new HashSet<>();
    private final Map<FId, List<FId>> groups = new TreeMap<>();
    private final Map<FId, FId> kept = new TreeMap<>();
    private final Map<String, TypeFix> fixes = new HashMap<>();
    private final Set<FId> skip = new HashSet<>();
    private final Set<String> skipTypes = new HashSet<>();

    private UnifyMap() { }

    /**
     * Builds the map. {@code tags} reads item tags; {@code fixFiles} are the parsed {@code fixes/*.json}, every copy;
     * {@code oresOn} is the config switch for ore deduplication.
     */
    public static UnifyMap build(UnifyRules rules, TagReader tags, Map<FId, List<JsonElement>> fixFiles, boolean oresOn) {
        UnifyMap m = new UnifyMap();
        Set<FId> targetsAll = new HashSet<>();
        List<FId> tagIds = new ArrayList<>(tags.tags());
        Collections.sort(tagIds);
        for (FId tag : tagIds) {
            if (!rules.covers(tag)) continue;
            String material = tag.path().substring(tag.path().lastIndexOf('/') + 1);
            UnifyRules.Material mat = rules.material(material);
            if (mat != null && Boolean.FALSE.equals(mat.unify)) continue;
            List<FId> members = new ArrayList<>();
            for (FId item : tags.items(tag)) if (!rules.ignoreItems.contains(item)) members.add(item);
            if (members.size() < 2) continue;
            FId keep = choose(members, mat, rules);
            if (keep == null) continue;
            m.groups.put(tag, List.copyOf(members));
            m.kept.put(tag, keep);
            boolean ore = tag.path().startsWith("ores/") && oresOn && (mat == null || !Boolean.FALSE.equals(mat.ores));
            List<FId> targets = new ArrayList<>();
            if (mat != null && mat.keep != null && mat.keep.contains(keep)) targets.add(keep);
            else for (FId i : members) if (i.namespace().equals(keep.namespace())) targets.add(i);
            for (FId item : members) {
                // an item in two groups keeps its first answer; a kept item never points elsewhere
                if (targets.contains(item) || m.to.containsKey(item) || targetsAll.contains(item)) continue;
                FId target = closest(item, targets, keep);
                m.to.put(item, target);
                m.tagOf.put(item, tag);
                if (ore) m.ores.add(item);
            }
            targetsAll.addAll(targets);
        }
        m.to.keySet().removeIf(targetsAll::contains);
        m.readFixes(fixFiles);
        return m;
    }

    private static FId choose(List<FId> members, UnifyRules.Material mat, UnifyRules rules) {
        if (mat != null && mat.keep != null) {
            for (FId k : mat.keep) if (members.contains(k)) return k;
        }
        List<String> order = new ArrayList<>();
        if (mat != null && mat.mods != null) order.addAll(mat.mods);
        order.addAll(rules.mods);
        for (String mod : order) {
            for (FId i : members) if (i.namespace().equals(mod)) return i;
        }
        // mods not listed: alphabetical by namespace, then by id
        return members.stream().min(Comparator.comparing(FId::namespace).thenComparing(Comparator.naturalOrder())).orElse(null);
    }

    /** The kept item whose name shares the most words with this one (deepslate ores find deepslate ores). */
    private static FId closest(FId item, List<FId> targets, FId fallback) {
        if (targets.size() == 1) return targets.get(0);
        Set<String> words = new HashSet<>(Arrays.asList(item.path().split("[_/]")));
        FId best = fallback;
        double bestScore = Double.NEGATIVE_INFINITY;
        List<FId> sorted = new ArrayList<>(targets);
        Collections.sort(sorted);
        for (FId t : sorted) {
            double score = 0;
            for (String w : t.path().split("[_/]")) score += words.contains(w) ? 1 : -0.01;
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    private void readFixes(Map<FId, List<JsonElement>> files) {
        for (Map.Entry<FId, List<JsonElement>> e : new TreeMap<>(files).entrySet()) {
            for (JsonElement copy : e.getValue()) {
                try {
                    JsonObject o = copy.getAsJsonObject();
                    if (o.has("recipes")) {
                        for (JsonElement r : o.getAsJsonArray("recipes")) {
                            JsonObject ro = r.getAsJsonObject();
                            boolean replace = ro.has("replace_defaults") && ro.get("replace_defaults").getAsBoolean();
                            for (String type : UnifyRules.strings(ro.get("type"))) {
                                List<String[]> paths = new ArrayList<>();
                                TypeFix old = fixes.get(type);
                                if (old != null) paths.addAll(old.paths());
                                for (String p : UnifyRules.strings(ro.get("paths"))) paths.add(p.split("/"));
                                fixes.put(type, new TypeFix(List.copyOf(paths), replace || (old != null && old.replaceDefaults())));
                            }
                        }
                    }
                    if (o.has("skip")) {
                        for (String s : UnifyRules.strings(o.get("skip"))) {
                            FId id = FId.tryOf(s);
                            if (id != null) skip.add(id);
                        }
                    }
                    if (o.has("skip_types")) skipTypes.addAll(UnifyRules.strings(o.get("skip_types")));
                } catch (RuntimeException ex) {
                    LOG.error("Unify fix file {} is not valid: {}", e.getKey(), ex.toString());
                }
            }
        }
    }

    // ---- reading

    /** The item this one is replaced by, or null when it is kept or not unified. */
    public FId target(FId item) {
        return to.get(item);
    }

    /** The tag that unified this item, or null. */
    public FId tagOf(FId item) {
        return tagOf.get(item);
    }

    /** Whether this item is replaced by another. */
    public boolean replaced(FId item) {
        return to.containsKey(item);
    }

    /** Whether this ore item is a duplicate whose worldgen is removed (ore deduplication on for its material). */
    public boolean oreRemoved(FId item) {
        return ores.contains(item);
    }

    /** Every replacement, from each replaced item to its kept item. */
    public Map<FId, FId> replacements() {
        return Collections.unmodifiableMap(to);
    }

    /** Each unified tag with its members (server side only; the client copy has none). */
    public Map<FId, List<FId>> groups() {
        return Collections.unmodifiableMap(groups);
    }

    /** Each unified tag with the item it keeps (server side only). */
    public Map<FId, FId> kept() {
        return Collections.unmodifiableMap(kept);
    }

    public boolean isEmpty() {
        return to.isEmpty();
    }

    // ---- sync

    /** The replacements as strings, for the client. Ore flags ride along as a {@code !} prefix on the key. */
    public Map<String, String> toStrings() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<FId, FId> e : new TreeMap<>(to).entrySet()) {
            out.put((ores.contains(e.getKey()) ? "!" : "") + e.getKey(), e.getValue() + "|" + tagOf.get(e.getKey()));
        }
        return out;
    }

    public static UnifyMap fromStrings(Map<String, String> in) {
        UnifyMap m = new UnifyMap();
        for (Map.Entry<String, String> e : in.entrySet()) {
            boolean ore = e.getKey().startsWith("!");
            FId from = FId.tryOf(ore ? e.getKey().substring(1) : e.getKey());
            String[] v = e.getValue().split("\\|", 2);
            FId target = FId.tryOf(v[0]);
            if (from == null || target == null) continue;
            m.to.put(from, target);
            FId tag = v.length > 1 ? FId.tryOf(v[1]) : null;
            if (tag != null) m.tagOf.put(from, tag);
            if (ore) m.ores.add(from);
        }
        return m;
    }

    // ---- recipes

    /** Points a recipe's outputs at the kept items, in place. True when anything changed. */
    public boolean rewriteRecipe(FId id, JsonElement json) {
        if (to.isEmpty() || json == null || !json.isJsonObject() || skip.contains(id)) return false;
        JsonObject o = json.getAsJsonObject();
        // Forge 1.20.1's conditional wrapper: each inner recipe
        if ("forge:conditional".equals(type(o)) && o.has("recipes") && o.get("recipes").isJsonArray()) {
            boolean changed = false;
            for (JsonElement inner : o.getAsJsonArray("recipes")) {
                if (inner.isJsonObject() && inner.getAsJsonObject().has("recipe")) {
                    changed |= rewriteObject(inner.getAsJsonObject().get("recipe"));
                }
            }
            return changed;
        }
        return rewriteObject(o);
    }

    private static String type(JsonObject o) {
        JsonElement t = o.get("type");
        return t != null && t.isJsonPrimitive() ? t.getAsString() : "";
    }

    private boolean rewriteObject(JsonElement json) {
        if (!json.isJsonObject()) return false;
        JsonObject o = json.getAsJsonObject();
        String type = type(o);
        if (skipTypes.contains(type)) return false;
        TypeFix fix = fixes.get(type);
        boolean[] changed = {false};
        if (fix == null || !fix.replaceDefaults()) {
            for (String p : DEFAULT_PATHS) {
                JsonElement old = o.get(p);
                if (old == null) continue;
                JsonElement now = value(old, changed);
                if (now != old) o.add(p, now);
            }
        }
        if (fix != null) for (String[] path : fix.paths()) walk(o, path, 0, changed);
        return changed[0];
    }

    private JsonElement walk(JsonElement node, String[] path, int i, boolean[] changed) {
        if (i == path.length) return value(node, changed);
        String seg = path[i];
        if (node.isJsonObject()) {
            JsonObject o = node.getAsJsonObject();
            JsonElement child = o.get(seg);
            if (child == null) return node;
            JsonElement now = walk(child, path, i + 1, changed);
            if (now != child) o.add(seg, now);
        } else if (node.isJsonArray()) {
            JsonArray a = node.getAsJsonArray();
            for (int k = 0; k < a.size(); k++) {
                if (!seg.equals("*") && !seg.equals(Integer.toString(k))) continue;
                JsonElement child = a.get(k);
                JsonElement now = walk(child, path, i + 1, changed);
                if (now != child) a.set(k, now);
            }
        }
        return node;
    }

    /** One output value: an id string, an object with {@code id} or {@code item}, or a list of those. */
    private JsonElement value(JsonElement v, boolean[] changed) {
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
            FId id = FId.tryOf(v.getAsString());
            FId t = id == null ? null : to.get(id);
            if (t == null) return v;
            changed[0] = true;
            return new JsonPrimitive(t.toString());
        }
        if (v.isJsonObject()) {
            JsonObject o = v.getAsJsonObject();
            for (String key : List.of("id", "item")) {
                JsonElement e = o.get(key);
                if (e != null && e.isJsonPrimitive()) {
                    JsonElement now = value(e, changed);
                    if (now != e) o.add(key, now);
                }
            }
            return v;
        }
        if (v.isJsonArray()) {
            JsonArray a = v.getAsJsonArray();
            for (int k = 0; k < a.size(); k++) {
                JsonElement old = a.get(k);
                JsonElement now = value(old, changed);
                if (now != old) a.set(k, now);
            }
        }
        return v;
    }
}
