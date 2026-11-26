package com.fundamentum.material;

import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.resources.Identifier;

/**
 * The name rules behind auto-detected material sets, read from {@code data/<namespace>/fundamentum/material_rules/*.json}.
 * Every file adds to the lists: {@code wear_prefixes} (a block named prefix + name joins name's set as that wear),
 * {@code nether} and {@code end} (a name containing one of these puts the set in that dimension), and
 * {@code slot_fallback} (missing slot -> the slot that stands in for it; a later file id wins a clash). The shipped rules
 * are the entry {@code fundamentum:default}; a pack file with that id (data/fundamentum/fundamentum/material_rules/default.json)
 * is merged over it, and a list there replaces the shipped list.
 */
public final class MaterialRules {
    private MaterialRules() { }

    static final List<String> DEFAULT_WEAR_PREFIXES = List.of("cracked_", "mossy_", "chiseled_", "polished_", "smooth_", "cut_");
    static final List<String> DEFAULT_NETHER = List.of("nether_brick", "red_nether", "blackstone", "basalt", "crimson", "warped", "soul_", "netherrack");
    static final List<String> DEFAULT_END = List.of("end_stone", "purpur", "chorus");
    static final Map<String, String> DEFAULT_SLOT_FALLBACK = Map.ofEntries(
        Map.entry("wall", "main"), Map.entry("wall_accent", "wall"), Map.entry("floor", "main"), Map.entry("floor_accent", "floor"),
        Map.entry("pillar", "main"), Map.entry("trim", "wall_accent"), Map.entry("ceiling", "floor"), Map.entry("rubble", "main"),
        Map.entry("foundation", "wall"), Map.entry("roof", "main"), Map.entry("roof_b", "roof"), Map.entry("roof_c", "roof_b"),
        Map.entry("quoin", "wall_accent"), Map.entry("paving", "floor"), Map.entry("carved", "main"), Map.entry("statue", "carved"),
        Map.entry("plaster", "main"), Map.entry("planks", "main"), Map.entry("post", "log"), Map.entry("timber", "stripped"),
        Map.entry("stripped", "log"));

    private record Rules(List<String> wearPrefixes, List<String> nether, List<String> end, Map<String, String> slotFallback) { }

    private static volatile Rules rules = new Rules(DEFAULT_WEAR_PREFIXES, DEFAULT_NETHER, DEFAULT_END, DEFAULT_SLOT_FALLBACK);

    public static final DataTable<JsonObject> TABLE = DataTables.registerJson(Identifier.fromNamespaceAndPath("fundamentum", "material_rules"),
        "fundamentum/material_rules").builtins(MaterialRules::builtins).onLoad(t -> {
            rules = read(t.rawEntries());
            MaterialSets.invalidate();
        });

    static void init() {
        // class load registers the data table
    }

    /** Wear prefixes, each ending in "_" (cracked_, mossy_...). */
    public static List<String> wearPrefixes() {
        return rules.wearPrefixes();
    }

    /** Name fragments that put a detected set in the nether. */
    public static List<String> nether() {
        return rules.nether();
    }

    /** Name fragments that put a detected set in the end. */
    public static List<String> end() {
        return rules.end();
    }

    /** Missing slot -> stand-in slot, shipped chain plus every pack's additions. */
    public static Map<String, String> slotFallback() {
        return rules.slotFallback();
    }

    /** A read-only map that always shows the current {@link #slotFallback()}. */
    static Map<String, String> slotFallbackView() {
        return new AbstractMap<>() {
            @Override
            public Set<Map.Entry<String, String>> entrySet() {
                return rules.slotFallback().entrySet();
            }

            @Override
            public String get(Object key) {
                return rules.slotFallback().get(key);
            }

            @Override
            public boolean containsKey(Object key) {
                return rules.slotFallback().containsKey(key);
            }
        };
    }

    private static Map<Identifier, JsonObject> builtins() {
        JsonObject j = new JsonObject();
        j.add("wear_prefixes", array(DEFAULT_WEAR_PREFIXES));
        j.add("nether", array(DEFAULT_NETHER));
        j.add("end", array(DEFAULT_END));
        JsonObject slots = new JsonObject();
        new TreeMap<>(DEFAULT_SLOT_FALLBACK).forEach(slots::addProperty);
        j.add("slot_fallback", slots);
        return Map.of(Identifier.fromNamespaceAndPath("fundamentum", "default"), j);
    }

    private static JsonArray array(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    private static Rules read(Map<Identifier, JsonObject> entries) {
        if (entries.isEmpty()) {
            return new Rules(DEFAULT_WEAR_PREFIXES, DEFAULT_NETHER, DEFAULT_END, DEFAULT_SLOT_FALLBACK);
        }
        Set<String> wears = new LinkedHashSet<>();
        Set<String> nether = new LinkedHashSet<>();
        Set<String> end = new LinkedHashSet<>();
        Map<String, String> slots = new LinkedHashMap<>();
        for (JsonObject j : new TreeMap<>(entries).values()) {
            strings(j, "wear_prefixes").forEach(s -> wears.add(s.endsWith("_") ? s : s + "_"));
            nether.addAll(strings(j, "nether"));
            end.addAll(strings(j, "end"));
            if (j.has("slot_fallback") && j.get("slot_fallback").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("slot_fallback").entrySet()) {
                    if (e.getValue().isJsonPrimitive() && !e.getKey().equals(e.getValue().getAsString())) {
                        slots.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
        }
        return new Rules(List.copyOf(wears), List.copyOf(nether), List.copyOf(end), Collections.unmodifiableMap(slots));
    }

    private static List<String> strings(JsonObject j, String field) {
        List<String> out = new ArrayList<>();
        if (j.has(field) && j.get(field).isJsonArray()) {
            for (JsonElement e : j.getAsJsonArray(field)) {
                if (e.isJsonPrimitive() && !e.getAsString().isBlank()) out.add(e.getAsString());
            }
        }
        return out;
    }
}
