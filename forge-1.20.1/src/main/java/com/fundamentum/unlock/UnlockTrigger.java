package com.fundamentum.unlock;

import com.fundamentum.port.FId;
import com.google.gson.JsonObject;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The {@code fundamentum:unlocked} advancement trigger: completes when the player (or their team) holds the unlock,
 * at {@code min_level} or more. Fires on every grant and again at login, so advancements added later still complete.
 * <pre>
 * "criteria": { "got": { "trigger": "fundamentum:unlocked", "conditions": { "id": "mymod:smelting", "min_level": 2 } } }
 * </pre>
 * Without {@code id} any unlock counts.
 */
public final class UnlockTrigger extends SimpleCriterionTrigger<UnlockTrigger.Instance> {
    public static final FId ID = FId.of("fundamentum", "unlocked");
    public static final UnlockTrigger INSTANCE = new UnlockTrigger();
    private static boolean initialised;

    public static final class Instance extends AbstractCriterionTriggerInstance {
        private final String id;
        private final int minLevel;

        public Instance(ContextAwarePredicate player, String id, int minLevel) {
            super(ID.vanilla(), player);
            this.id = id;
            this.minLevel = minLevel;
        }

        public boolean test(FId unlock, int level) {
            return level >= this.minLevel && (this.id == null || unlock.equals(FId.tryOf(this.id)));
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            if (this.id != null) json.addProperty("id", this.id);
            json.addProperty("min_level", this.minLevel);
            return json;
        }
    }

    private UnlockTrigger() { }

    @Override
    public ResourceLocation getId() {
        return ID.vanilla();
    }

    @Override
    protected Instance createInstance(JsonObject json, ContextAwarePredicate player, DeserializationContext context) {
        String id = json.has("id") ? json.get("id").getAsString() : null;
        int min = json.has("min_level") ? json.get("min_level").getAsInt() : 1;
        return new Instance(player, id, min);
    }

    static void init() {
        if (initialised) return;
        initialised = true;
        CriteriaTriggers.register(INSTANCE);
    }

    /** Fires the trigger for the player holding the unlock at this level. */
    public static void trigger(ServerPlayer player, FId unlock, int level) {
        INSTANCE.trigger(player, i -> i.test(unlock, level));
    }
}
