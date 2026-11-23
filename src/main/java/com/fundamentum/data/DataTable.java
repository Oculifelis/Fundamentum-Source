package com.fundamentum.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A table of JSON entries read from {@code data/<namespace>/<path>/<name>.json}, patched by datapacks and (when synced) sent
 * to clients. Built-in entries load first; then every file, in file id order, is merged over the entry it names.
 * Configure it right after {@link DataTables#register}, before the first world loads.
 *
 * <p>Merging: {@link Merge#DEEP} (the default) merges objects field by field, a {@code null} field removes it, anything
 * else replaces. {@link Merge#SHALLOW} replaces whole top-level fields.
 *
 * <p>Keys: by default an entry's key is its file id ({@code <namespace>:<name>}). {@link #keyField} reads the key from a
 * field instead (so {@code data/mypack/.../x.json} can patch {@code ultimaratio:x}); {@link #keyByName} keys by file name
 * alone in this table's own namespace, so every namespace's {@code tiers.json} lands in one entry.
 */
public final class DataTable<T> {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");

    public enum Merge { DEEP, SHALLOW }

    private record Snapshot<T>(Map<ResourceLocation, JsonObject> raw, Map<ResourceLocation, T> values) {}

    private final ResourceLocation id;
    private final String path;
    private final Function<JsonObject, T> decoder;
    private Merge merge = Merge.DEEP;
    private String keyField;
    private boolean keyByName;
    private boolean synced;
    private Supplier<Map<ResourceLocation, JsonObject>> builtins = Map::of;
    private final List<Consumer<DataTable<T>>> listeners = new ArrayList<>();
    private volatile Snapshot<T> snapshot = new Snapshot<>(Map.of(), Map.of());

    DataTable(ResourceLocation id, String path, Function<JsonObject, T> decoder) {
        this.id = id;
        this.path = path;
        this.decoder = decoder;
    }

    /** A decoder that reads entries with a Codec. */
    public static <T> Function<JsonObject, T> codec(Codec<T> codec) {
        return json -> codec.parse(JsonOps.INSTANCE, json).getOrThrow();
    }

    public DataTable<T> merge(Merge merge) {
        this.merge = merge;
        return this;
    }

    public DataTable<T> keyField(String field) {
        this.keyField = field;
        return this;
    }

    public DataTable<T> keyByName() {
        this.keyByName = true;
        return this;
    }

    /** Sends the merged table to every client on login and /reload. */
    public DataTable<T> synced() {
        this.synced = true;
        return this;
    }

    /** Entries every load starts from, before datapack files (read on the reload thread). */
    public DataTable<T> builtins(Supplier<Map<ResourceLocation, JsonObject>> builtins) {
        this.builtins = builtins;
        return this;
    }

    /** Runs after every load, on the server and (for a synced table) on the client. */
    public DataTable<T> onLoad(Consumer<DataTable<T>> listener) {
        this.listeners.add(listener);
        return this;
    }

    public ResourceLocation id() {
        return this.id;
    }

    public String path() {
        return this.path;
    }

    public boolean isSynced() {
        return this.synced;
    }

    public T get(ResourceLocation key) {
        return this.snapshot.values().get(key);
    }

    public T getOrDefault(ResourceLocation key, T fallback) {
        return this.snapshot.values().getOrDefault(key, fallback);
    }

    /** Every decoded entry, in key order. */
    public Map<ResourceLocation, T> entries() {
        return this.snapshot.values();
    }

    /** The merged JSON of an entry, or null. Do not modify it. */
    public JsonObject raw(ResourceLocation key) {
        return this.snapshot.raw().get(key);
    }

    /** Every merged JSON entry, in key order. Do not modify them. */
    public Map<ResourceLocation, JsonObject> rawEntries() {
        return this.snapshot.raw();
    }

    /** A number field of an entry, or the fallback when the entry or field is missing or not a number. */
    public double number(ResourceLocation key, String field, double fallback) {
        JsonObject entry = raw(key);
        JsonElement value = entry == null ? null : entry.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsDouble() : fallback;
    }

    /** Merges a patch over a base, by this table's rule; the base is not changed. */
    public JsonObject merged(JsonObject base, JsonObject patch) {
        return this.merge == Merge.DEEP ? deepMerge(base, patch) : shallowMerge(base, patch);
    }

    public static JsonObject shallowMerge(JsonObject base, JsonObject patch) {
        JsonObject out = base.deepCopy();
        for (var e : patch.entrySet()) {
            out.add(e.getKey(), e.getValue().deepCopy());
        }
        return out;
    }

    public static JsonObject deepMerge(JsonObject base, JsonObject patch) {
        JsonObject out = base.deepCopy();
        for (var e : patch.entrySet()) {
            JsonElement value = e.getValue();
            JsonElement old = out.get(e.getKey());
            if (value.isJsonNull()) {
                out.remove(e.getKey());
            } else if (value.isJsonObject() && old != null && old.isJsonObject()) {
                out.add(e.getKey(), deepMerge(old.getAsJsonObject(), value.getAsJsonObject()));
            } else {
                out.add(e.getKey(), value.deepCopy());
            }
        }
        return out;
    }

    /** The key a file patches: its key field, its name, or its file id. */
    ResourceLocation keyOf(ResourceLocation file, JsonObject json) {
        if (this.keyField != null && json.has(this.keyField)) {
            return ResourceLocation.parse(json.get(this.keyField).getAsString());
        }
        return this.keyByName ? ResourceLocation.fromNamespaceAndPath(this.id.getNamespace(), file.getPath()) : file;
    }

    /** Built-ins merged with the files (already in file id order). */
    Map<ResourceLocation, JsonObject> assemble(List<Map.Entry<ResourceLocation, JsonObject>> files) {
        Map<ResourceLocation, JsonObject> out = new TreeMap<>();
        try {
            this.builtins.get().forEach((k, v) -> out.put(k, v.deepCopy()));
        } catch (RuntimeException e) {
            LOG.error("data table {}: built-ins failed", this.id, e);
        }
        for (var file : files) {
            JsonObject json = file.getValue();
            ResourceLocation key = keyOf(file.getKey(), json);
            JsonObject old = out.get(key);
            out.put(key, old == null ? json : merged(old, json));
        }
        return out;
    }

    /** Decodes and installs a merged table, then runs the listeners. Entries that fail to decode are left out. */
    void install(Map<ResourceLocation, JsonObject> raw) {
        Map<ResourceLocation, JsonObject> rawOut = new LinkedHashMap<>();
        Map<ResourceLocation, T> values = new LinkedHashMap<>();
        new TreeMap<>(raw).forEach((key, json) -> {
            try {
                values.put(key, this.decoder.apply(json));
                rawOut.put(key, json);
            } catch (RuntimeException e) {
                LOG.error("data table {}: entry {} is invalid, left out", this.id, key, e);
            }
        });
        this.snapshot = new Snapshot<>(Collections.unmodifiableMap(rawOut), Collections.unmodifiableMap(values));
        for (var listener : this.listeners) {
            try {
                listener.accept(this);
            } catch (RuntimeException e) {
                LOG.error("data table {}: a load listener failed", this.id, e);
            }
        }
    }
}
