package com.fundamentum.condition;

import com.fundamentum.event.EventTrigger;
import com.fundamentum.port.FId;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fundamentum's JSON conditions: one small object per test, {@code {"type": "<type>", ...}}, and lists of them. Data
 * files that take conditions read them through here, so every system understands the same types and a mod can add its
 * own with {@link #register}.
 *
 * <pre>
 * {"type": "unlocked", "id": "mymod:smelting", "min": 2}           the player (or their team) has the unlock
 * {"type": "stat", "id": "mymod:strength", "min": 5, "max": 20}    a Fundamentum stat in range (either end optional)
 * {"type": "advancement", "id": "minecraft:story/mine_stone"}       the player has the advancement
 * {"type": "item", "id": "minecraft:diamond", "count": 3}           the player carries the item (count optional)
 * {"type": "vanilla_stat", "stat": "minecraft:custom", "value": "minecraft:jump", "min": 10}
 * {"type": "all", "of": [...]}, {"type": "any", "of": [...]}, {"type": "not", "of": {...}}
 * </pre>
 *
 * A list is true when every entry is true ({@link #all}); an empty or missing list is true. An unknown type is false
 * and logged once.
 */
public final class Conditions {
    private Conditions() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");

    /** What a condition is checked against: the entity (often a player) and, for item conditions, a stack. */
    public record Context(LivingEntity entity, ItemStack stack) {
        public static Context of(LivingEntity entity) {
            return new Context(entity, ItemStack.EMPTY);
        }

        /** The entity as a server player, or null. */
        public ServerPlayer player() {
            return this.entity instanceof ServerPlayer p ? p : null;
        }
    }

    /** One condition type. */
    @FunctionalInterface
    public interface Condition {
        boolean test(JsonObject json, Context context);
    }

    private static final Map<String, Condition> TYPES = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    static {
        register("all", (j, c) -> all(j.get("of"), c));
        register("any", (j, c) -> any(j.get("of"), c));
        register("not", (j, c) -> !test(j.get("of"), c));
        register("advancement", (j, c) -> {
            ServerPlayer p = c.player();
            FId id = id(j, "id");
            return p != null && id != null && EventTrigger.done(p, id);
        });
        register("item", (j, c) -> {
            FId id = id(j, "id");
            if (id == null || !(c.entity() instanceof net.minecraft.world.entity.player.Player p)) return false;
            int want = j.has("count") ? j.get("count").getAsInt() : 1;
            int have = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack s = p.getInventory().getItem(i);
                if (!s.isEmpty() && FVanilla.idOf(s.getItem()).equals(id)) have += s.getCount();
            }
            return have >= want;
        });
        register("vanilla_stat", (j, c) -> {
            ServerPlayer p = c.player();
            FId type = id(j, "stat");
            FId value = id(j, "value");
            if (p == null || type == null || value == null) return false;
            return inRange(FVanilla.statValue(p, type, value), j);
        });
    }

    /** Adds a condition type (or replaces one). Types are plain names; use {@code mymod:name} for a mod's own. */
    public static void register(String type, Condition condition) {
        TYPES.put(type, condition);
    }

    public static boolean known(String type) {
        return TYPES.containsKey(type);
    }

    public static Set<String> types() {
        return new TreeSet<>(TYPES.keySet());
    }

    /** One condition, or a list of them (all must hold). Null or JSON null is true. */
    public static boolean test(JsonElement condition, Context context) {
        if (condition == null || condition.isJsonNull()) return true;
        if (condition.isJsonArray()) return all(condition, context);
        if (!condition.isJsonObject()) return false;
        JsonObject o = condition.getAsJsonObject();
        String type = o.has("type") ? o.get("type").getAsString() : "";
        Condition c = TYPES.get(type);
        if (c == null) {
            if (WARNED.add(type)) LOG.warn("Unknown condition type '{}' in {}", type, o);
            return false;
        }
        try {
            return c.test(o, context);
        } catch (RuntimeException e) {
            if (WARNED.add(type + " " + o)) LOG.warn("Condition {} failed: {}", o, e.toString());
            return false;
        }
    }

    public static boolean test(JsonElement condition, LivingEntity entity) {
        return test(condition, Context.of(entity));
    }

    /** True when every condition holds (an empty or missing list is true). A single object counts as a list of one. */
    public static boolean all(JsonElement list, Context context) {
        if (list == null || list.isJsonNull()) return true;
        if (!list.isJsonArray()) return test(list, context);
        for (JsonElement e : list.getAsJsonArray()) {
            if (!test(e, context)) return false;
        }
        return true;
    }

    /** True when at least one condition holds (an empty or missing list is false). */
    public static boolean any(JsonElement list, Context context) {
        if (list == null || list.isJsonNull()) return false;
        if (!list.isJsonArray()) return test(list, context);
        JsonArray a = list.getAsJsonArray();
        for (JsonElement e : a) {
            if (test(e, context)) return true;
        }
        return false;
    }

    /** {@code min} and {@code max} in the object, either optional. */
    public static boolean inRange(double value, JsonObject json) {
        if (json.has("min") && value < json.get("min").getAsDouble()) return false;
        return !json.has("max") || value <= json.get("max").getAsDouble();
    }

    /** The id in the named field, or null when missing or not an id. */
    public static FId id(JsonObject json, String field) {
        return json.has(field) && json.get(field).isJsonPrimitive() ? FId.tryOf(json.get(field).getAsString()) : null;
    }
}
