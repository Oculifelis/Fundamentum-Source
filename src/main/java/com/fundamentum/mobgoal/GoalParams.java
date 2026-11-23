package com.fundamentum.mobgoal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

/** The {@code params} object of a mob goal entry, with defaults for anything missing or of the wrong type. */
public final class GoalParams {
    public static final GoalParams EMPTY = new GoalParams(new JsonObject());

    private final JsonObject json;

    public GoalParams(JsonObject json) {
        this.json = json == null ? new JsonObject() : json;
    }

    public JsonObject json() {
        return json;
    }

    public boolean has(String key) {
        return json.has(key) && !json.get(key).isJsonNull();
    }

    public double d(String key, double def) {
        try {
            return has(key) ? json.get(key).getAsDouble() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    public float f(String key, double def) {
        return (float) d(key, def);
    }

    public int i(String key, int def) {
        try {
            return has(key) ? json.get(key).getAsInt() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    public boolean b(String key, boolean def) {
        try {
            return has(key) ? json.get(key).getAsBoolean() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    public String s(String key, String def) {
        try {
            return has(key) ? json.get(key).getAsString() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    /** {@code [x, y, z]}, or null when missing. */
    public BlockPos pos(String key) {
        if (!has(key) || !json.get(key).isJsonArray()) return null;
        JsonArray a = json.get(key).getAsJsonArray();
        if (a.size() != 3) return null;
        try {
            return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
        } catch (RuntimeException e) {
            return null;
        }
    }

    public JsonElement get(String key) {
        return json.get(key);
    }

    @Override
    public String toString() {
        return json.toString();
    }
}
