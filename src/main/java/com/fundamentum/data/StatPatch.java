package com.fundamentum.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Named stat patches (tiers, infusions, upgrades) applied in order over a JSON object of stats. Each field of a patch is
 * either a plain value, which sets the stat, or an object of steps run in this order: {@code "set"}, {@code "add"},
 * {@code "multiply"}, then the {@code "min"} / {@code "max"} clamps. A number stat that is missing counts as 0.
 * Example: {@code {"damage": {"multiply": 1.25}, "magazine": {"add": 4}, "fire_mode": "burst"}}.
 */
public final class StatPatch {
    /** Applies the patches in order; the stats are not changed. */
    public static JsonObject apply(JsonObject stats, JsonObject... patches) {
        JsonObject out = stats.deepCopy();
        for (JsonObject patch : patches) {
            for (var e : patch.entrySet()) {
                out.add(e.getKey(), step(out.get(e.getKey()), e.getValue()));
            }
        }
        return out;
    }

    private static JsonElement step(JsonElement old, JsonElement change) {
        if (!change.isJsonObject() || !isSteps(change.getAsJsonObject())) {
            return change.deepCopy();
        }
        JsonObject steps = change.getAsJsonObject();
        if (steps.has("set") && !steps.get("set").isJsonPrimitive()) {
            return steps.get("set").deepCopy();
        }
        double value = steps.has("set") ? steps.get("set").getAsDouble() : number(old);
        if (steps.has("add")) {
            value += steps.get("add").getAsDouble();
        }
        if (steps.has("multiply")) {
            value *= steps.get("multiply").getAsDouble();
        }
        if (steps.has("min")) {
            value = Math.max(value, steps.get("min").getAsDouble());
        }
        if (steps.has("max")) {
            value = Math.min(value, steps.get("max").getAsDouble());
        }
        // whole numbers stay whole, so integer stats (magazine, count) read back as ints
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? new JsonPrimitive((long) value) : new JsonPrimitive(value);
    }

    private static boolean isSteps(JsonObject o) {
        if (o.size() == 0) {
            return false;
        }
        for (String key : o.keySet()) {
            if (!key.equals("set") && !key.equals("add") && !key.equals("multiply") && !key.equals("min") && !key.equals("max")) {
                return false;
            }
        }
        return true;
    }

    private static double number(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : 0;
    }

    private StatPatch() {}
}
