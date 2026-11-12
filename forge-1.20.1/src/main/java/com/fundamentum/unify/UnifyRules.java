package com.fundamentum.unify;

import com.fundamentum.port.FId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The merged {@code data/<ns>/fundamentum/unify/priority.json} files: which tags are unified, the mod order, and the
 * per-material overrides. Files are read in id order, each pack's copy lowest first; a later file's mods go in front of
 * the earlier ones, and {@code "replace": true} drops everything read before it. The same file on every version.
 */
public final class UnifyRules {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum/unify");

    /** One material's override; null fields mean "not set". */
    public static final class Material {
        public List<String> mods;
        /** The items to keep, one per tag of this material (an ingot, a nugget, a block...); null when not set. */
        public List<FId> keep;
        public Boolean unify;
        public Boolean ores;

        void merge(JsonObject o) {
            if (o.has("mods")) mods = strings(o.get("mods"));
            if (o.has("keep")) {
                keep = new ArrayList<>();
                for (String k : strings(o.get("keep"))) {
                    FId id = FId.tryOf(k);
                    if (id != null) keep.add(id);
                }
            }
            if (o.has("unify")) unify = o.get("unify").getAsBoolean();
            if (o.has("ores")) ores = o.get("ores").getAsBoolean();
        }
    }

    final List<String> mods = new ArrayList<>();
    final Set<String> tags = new LinkedHashSet<>();
    final Set<FId> ignoreTags = new LinkedHashSet<>();
    final Set<FId> ignoreItems = new LinkedHashSet<>();
    final Map<String, Material> materials = new LinkedHashMap<>();

    /** Mods in priority order, best first. */
    public List<String> mods() { return List.copyOf(mods); }
    /** Tag patterns that are unified, like {@code c:ingots/*} (a trailing * matches any rest of the path). */
    public Set<String> tagPatterns() { return Set.copyOf(tags); }
    public Set<FId> ignoredTags() { return Set.copyOf(ignoreTags); }
    public Set<FId> ignoredItems() { return Set.copyOf(ignoreItems); }

    /** The override for a material, also found by the name without a leading {@code raw_}; null when none. */
    public Material material(String name) {
        Material m = materials.get(name);
        if (m == null && name.startsWith("raw_")) m = materials.get(name.substring(4));
        return m;
    }

    /** Whether a tag is unified by these rules. */
    public boolean covers(FId tag) {
        if (ignoreTags.contains(tag)) return false;
        String s = tag.toString();
        for (String p : tags) {
            if (p.endsWith("*") ? s.startsWith(p.substring(0, p.length() - 1)) : s.equals(p)) return true;
        }
        return false;
    }

    /** Reads every copy of every priority file, in order. */
    public static UnifyRules read(Map<FId, List<JsonElement>> files) {
        UnifyRules r = new UnifyRules();
        for (Map.Entry<FId, List<JsonElement>> e : new java.util.TreeMap<>(files).entrySet()) {
            for (JsonElement copy : e.getValue()) {
                try {
                    r.merge(copy.getAsJsonObject());
                } catch (RuntimeException ex) {
                    LOG.error("Unify priority file {} is not valid: {}", e.getKey(), ex.toString());
                }
            }
        }
        return r;
    }

    void merge(JsonObject o) {
        if (o.has("replace") && o.get("replace").getAsBoolean()) {
            mods.clear();
            tags.clear();
            ignoreTags.clear();
            ignoreItems.clear();
            materials.clear();
        }
        if (o.has("mods")) {
            List<String> front = strings(o.get("mods"));
            mods.removeAll(front);
            mods.addAll(0, front);
        }
        if (o.has("tags")) tags.addAll(strings(o.get("tags")));
        if (o.has("ignore_tags")) for (String s : strings(o.get("ignore_tags"))) addId(ignoreTags, s);
        if (o.has("ignore_items")) for (String s : strings(o.get("ignore_items"))) addId(ignoreItems, s);
        if (o.has("materials")) {
            for (Map.Entry<String, JsonElement> m : o.getAsJsonObject("materials").entrySet()) {
                materials.computeIfAbsent(m.getKey(), k -> new Material()).merge(m.getValue().getAsJsonObject());
            }
        }
    }

    private static void addId(Collection<FId> to, String s) {
        String t = s.startsWith("#") ? s.substring(1) : s;
        FId id = FId.tryOf(t);
        if (id != null) to.add(id);
        else LOG.warn("Unify: {} is not an id", s);
    }

    static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e.isJsonArray()) {
            for (JsonElement x : (JsonArray) e) out.add(x.getAsString());
        } else {
            out.add(e.getAsString());
        }
        return out;
    }
}
