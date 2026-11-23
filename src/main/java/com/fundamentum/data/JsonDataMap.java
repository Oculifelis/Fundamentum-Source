package com.fundamentum.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The 1.20.1 stand-in for a NeoForge data map: 1.20.1 has none, so Fundamentum reads the same files itself, in the same
 * shape and at the same path, so one datapack serves both versions. For {@code fundamentum:element_aura} on damage types
 * that is {@code data/<any ns>/data_maps/damage_type/element_aura.json}:
 * <pre>{"replace": false, "values": {"minecraft:magic": {...}, "#fundamentum:element/fire": {...}}, "remove": ["minecraft:drown"]}</pre>
 * Every pack's file is read, lowest pack first; a later pack's entry for the same key wins, {@code "replace": true}
 * drops what the packs below said, and {@code remove} drops keys. A value may also be written
 * {@code {"value": {...}, "replace": true}}. Tag keys are matched when looked up (tags are bound after data loads), a
 * direct key before any tag, later tags before earlier ones. Server side only, like the data maps it stands in for
 * (they are not synced to clients here).
 */
public final class JsonDataMap<R, T> {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");

    private final ResourceLocation id;
    private final ResourceKey<? extends Registry<R>> registry;
    private final Codec<T> codec;
    private volatile Map<ResourceLocation, T> direct = Map.of();
    /** Tag entries, last read first. */
    private volatile List<Map.Entry<TagKey<R>, T>> tags = List.of();
    private final Map<ResourceKey<R>, Cached<T>> cache = new ConcurrentHashMap<>();

    private record Cached<T>(@Nullable T value) {}

    public JsonDataMap(ResourceLocation id, ResourceKey<? extends Registry<R>> registry, Codec<T> codec) {
        this.id = id;
        this.registry = registry;
        this.codec = codec;
    }

    public ResourceLocation id() {
        return this.id;
    }

    /** Hooks the loader into datapack (re)loads; call once while the mod is constructed. */
    public JsonDataMap<R, T> register() {
        MinecraftForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> e.addListener(new Loader()));
        MinecraftForge.EVENT_BUS.addListener((TagsUpdatedEvent e) -> this.cache.clear());
        return this;
    }

    /** The file every pack may hold: {@code data_maps/<registry path>/<map path>.json}. */
    public ResourceLocation file(String namespace) {
        return new ResourceLocation(namespace, "data_maps/" + this.registry.location().getPath() + "/" + this.id.getPath() + ".json");
    }

    /** The value for a registry entry, or null when no entry (direct or by tag) names it. */
    public @Nullable T get(net.minecraft.core.Holder<R> entry) {
        var key = entry.unwrapKey().orElse(null);
        if (key == null) {
            return null;
        }
        return this.cache.computeIfAbsent(key, k -> new Cached<>(find(entry, k))).value();
    }

    private @Nullable T find(net.minecraft.core.Holder<R> entry, ResourceKey<R> key) {
        T value = this.direct.get(key.location());
        if (value != null) {
            return value;
        }
        for (Map.Entry<TagKey<R>, T> tag : this.tags) {
            if (entry.is(tag.getKey())) {
                return tag.getValue();
            }
        }
        return null;
    }

    private T decode(JsonElement json, String where) {
        return this.codec.parse(JsonOps.INSTANCE, json).getOrThrow(false, e -> LOG.error("data map {}: {} in {}", this.id, e, where));
    }

    private record Loaded<T>(Map<ResourceLocation, T> direct, List<Map.Entry<TagKey<?>, T>> tags) {}

    private final class Loader extends SimplePreparableReloadListener<Loaded<T>> {
        @Override
        protected Loaded<T> prepare(ResourceManager manager, ProfilerFiller profiler) {
            // keys in file order, so a later entry for the same key replaces the earlier one in place
            Map<String, T> entries = new java.util.LinkedHashMap<>();
            for (String namespace : manager.getNamespaces()) {
                ResourceLocation file = file(namespace);
                for (Resource resource : manager.getResourceStack(file)) {
                    try (Reader reader = resource.openAsReader()) {
                        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                        if (root.has("replace") && root.get("replace").getAsBoolean()) {
                            entries.clear();
                        }
                        if (root.has("values")) {
                            for (var e : root.getAsJsonObject("values").entrySet()) {
                                JsonElement v = e.getValue();
                                if (v.isJsonObject() && v.getAsJsonObject().has("value") && v.getAsJsonObject().size() <= 2
                                    && (v.getAsJsonObject().size() == 1 || v.getAsJsonObject().has("replace"))) {
                                    v = v.getAsJsonObject().get("value");
                                }
                                try {
                                    entries.remove(e.getKey());
                                    entries.put(e.getKey(), decode(v, file + " (" + resource.sourcePackId() + ")"));
                                } catch (RuntimeException ex) {
                                    // logged by decode
                                }
                            }
                        }
                        if (root.has("remove")) {
                            for (JsonElement r : root.getAsJsonArray("remove")) {
                                entries.remove(r.getAsString());
                            }
                        }
                    } catch (Exception ex) {
                        LOG.error("data map {}: could not read {} from {}", JsonDataMap.this.id, file, resource.sourcePackId(), ex);
                    }
                }
            }
            Map<ResourceLocation, T> direct = new java.util.HashMap<>();
            List<Map.Entry<TagKey<?>, T>> tags = new ArrayList<>();
            entries.forEach((key, value) -> {
                if (key.startsWith("#")) {
                    ResourceLocation tag = ResourceLocation.tryParse(key.substring(1));
                    if (tag != null) {
                        tags.add(0, Map.entry(TagKey.create(JsonDataMap.this.registry, tag), value));
                    }
                } else {
                    ResourceLocation k = ResourceLocation.tryParse(key);
                    if (k != null) {
                        direct.put(k, value);
                    }
                }
            });
            return new Loaded<>(Map.copyOf(direct), List.copyOf(tags));
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        protected void apply(Loaded<T> loaded, ResourceManager manager, ProfilerFiller profiler) {
            JsonDataMap.this.direct = loaded.direct();
            JsonDataMap.this.tags = (List) loaded.tags();
            JsonDataMap.this.cache.clear();
        }
    }
}
