package com.fundamentum.armorset;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/**
 * The registry of custom armour set rewards. A tier lists them as {@code {"type": "mymod:aura", ...fields}}; the codec (or
 * function) gets the whole object. Register during mod construction, before data loads.
 */
public final class SetRewards {
    private static final Map<ResourceLocation, Function<JsonObject, ? extends SetReward>> TYPES = new ConcurrentHashMap<>();

    public static void register(ResourceLocation id, Codec<? extends SetReward> codec) {
        TYPES.put(id, json -> {
            var r = codec.parse(JsonOps.INSTANCE, json);
            return r.result().orElseThrow(() -> new IllegalArgumentException(r.error().map(e -> e.message()).orElse("bad reward")));
        });
    }

    public static void register(ResourceLocation id, Function<JsonObject, ? extends SetReward> decoder) {
        TYPES.put(id, decoder);
    }

    public static Set<ResourceLocation> types() {
        return Set.copyOf(TYPES.keySet());
    }

    public static SetReward parse(JsonObject json) {
        ResourceLocation type = new ResourceLocation(GsonHelper.getAsString(json, "type"));
        Function<JsonObject, ? extends SetReward> decoder = TYPES.get(type);
        if (decoder == null) {
            throw new IllegalArgumentException("unknown set reward type " + type);
        }
        JsonObject copy = JsonParser.parseString(json.toString()).getAsJsonObject();
        copy.remove("type");
        return decoder.apply(copy);
    }

    private SetRewards() {}
}
