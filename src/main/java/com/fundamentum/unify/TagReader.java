package com.fundamentum.unify;

import com.fundamentum.port.FId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Resolves item tags straight from their files, so unification can run while recipes are still loading (before the game
 * has bound any tag). Handles {@code replace}, nested {@code #tags}, optional entries ({@code "required": false}) and
 * NeoForge's {@code remove} lists. Items that are not registered are left out. The same file on every version.
 */
public final class TagReader {
    private final Map<FId, List<JsonElement>> files;
    private final Predicate<FId> exists;
    private final Map<FId, Set<FId>> resolved = new HashMap<>();
    private final Set<FId> resolving = new HashSet<>();

    public TagReader(Map<FId, List<JsonElement>> files, Predicate<FId> exists) {
        this.files = files;
        this.exists = exists;
    }

    /** Every tag that has a file. */
    public Set<FId> tags() {
        return files.keySet();
    }

    /** The items in a tag, in file order; empty for an unknown tag. */
    public Set<FId> items(FId tag) {
        Set<FId> done = resolved.get(tag);
        if (done != null) return done;
        if (!resolving.add(tag)) return Set.of(); // a loop: the outer call fills it
        Set<FId> out = new LinkedHashSet<>();
        Set<FId> removed = new HashSet<>();
        for (JsonElement copy : files.getOrDefault(tag, List.of())) {
            if (!copy.isJsonObject()) continue;
            JsonObject o = copy.getAsJsonObject();
            if (o.has("replace") && o.get("replace").getAsBoolean()) out.clear();
            if (o.has("values")) for (JsonElement v : o.getAsJsonArray("values")) add(out, v);
            if (o.has("remove")) {
                Set<FId> rm = new LinkedHashSet<>();
                for (JsonElement v : o.getAsJsonArray("remove")) add(rm, v);
                removed.addAll(rm);
            }
        }
        out.removeAll(removed);
        resolving.remove(tag);
        Set<FId> result = java.util.Collections.unmodifiableSet(out);
        resolved.put(tag, result);
        return result;
    }

    private void add(Set<FId> out, JsonElement v) {
        String s;
        if (v.isJsonObject()) {
            JsonObject o = v.getAsJsonObject();
            if (!o.has("id")) return;
            s = o.get("id").getAsString();
        } else {
            s = v.getAsString();
        }
        if (s.startsWith("#")) {
            FId t = FId.tryOf(s.substring(1));
            if (t != null) out.addAll(items(t));
            return;
        }
        FId id = FId.tryOf(s);
        if (id != null && exists.test(id)) out.add(id);
    }

    /** Copies the list so callers can sort it. */
    public List<FId> list(FId tag) {
        return new ArrayList<>(items(tag));
    }
}
