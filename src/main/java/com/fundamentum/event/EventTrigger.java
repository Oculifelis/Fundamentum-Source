package com.fundamentum.event;

import com.fundamentum.port.FId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * The {@code fundamentum:event} advancement trigger: completes when an ecosystem event about the player fires.
 * <pre>
 * "criteria": { "done": { "trigger": "fundamentum:event", "conditions": {
 *     "event": "fundamentum:quest_completed", "where": { "quest": "mymod:first_steps" } } } }
 * </pre>
 * {@code where} works as in {@link FEvents#matches}; {@code player} takes the usual entity conditions.
 */
public final class EventTrigger extends SimpleCriterionTrigger<EventTrigger.Instance> {
    public static final FId ID = FId.of("fundamentum", "event");
    public static final EventTrigger INSTANCE = new EventTrigger();
    private static boolean initialised;

    public static final class Instance extends AbstractCriterionTriggerInstance {
        private final String event;
        private final JsonObject where;

        public Instance(ContextAwarePredicate player, String event, JsonObject where) {
            super(ID.vanilla(), player);
            this.event = event;
            this.where = where;
        }

        public String event() {
            return this.event;
        }

        /** True when the player meets the {@code player} conditions (or there are none). */
        public boolean testPlayer(ServerPlayer p) {
            return getPlayerPredicate().matches(EntityPredicate.createContext(p, p));
        }

        public boolean test(FEvents.Type type, Record event) {
            FId want = FId.tryOf(this.event);
            if (want == null || !want.equals(type.id())) return false;
            return FEvents.matches(event, this.where);
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            json.addProperty("event", this.event);
            if (this.where != null) json.add("where", this.where);
            return json;
        }
    }

    private EventTrigger() { }

    @Override
    public ResourceLocation getId() {
        return ID.vanilla();
    }

    @Override
    protected Instance createInstance(JsonObject json, ContextAwarePredicate player, DeserializationContext context) {
        return fromJson(json, player);
    }

    private static Instance fromJson(JsonObject json, ContextAwarePredicate player) {
        String event = json.get("event").getAsString();
        JsonElement w = json.get("where");
        return new Instance(player, event, w != null && w.isJsonObject() ? w.getAsJsonObject() : null);
    }

    /** Called once while Fundamentum loads: registers the trigger and hooks it to {@link FEvents#post}. */
    public static void init() {
        if (initialised) return;
        initialised = true;
        CriteriaTriggers.register(INSTANCE);
        FEvents.bridge((type, event, cancelled) -> {
            ServerPlayer player = FEvents.player(event);
            if (player != null && !cancelled) INSTANCE.trigger(player, i -> i.test(type, event));
            return false;
        });
    }

    /** Reads trigger conditions from JSON (for checks and tools), {@code player} conditions included while a server runs. */
    public static Instance read(JsonElement conditions) {
        JsonObject json = conditions.getAsJsonObject();
        ContextAwarePredicate player = ContextAwarePredicate.ANY;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (json.has("player") && server != null) {
            player = EntityPredicate.fromJson(json, "player", new DeserializationContext(ID.vanilla(), server.getLootData()));
        }
        return fromJson(json, player);
    }

    /** True when the player has completed the advancement. */
    public static boolean done(ServerPlayer player, FId advancement) {
        Advancement adv = player.getServer().getAdvancements().getAdvancement(advancement.vanilla());
        return adv != null && player.getAdvancements().getOrStartProgress(adv).isDone();
    }

    /** True when the server has loaded the advancement. */
    public static boolean known(MinecraftServer server, FId advancement) {
        return server.getAdvancements().getAdvancement(advancement.vanilla()) != null;
    }

    /** The trigger as the game's registry type, for code that needs it. */
    public static CriterionTrigger<?> trigger() {
        return INSTANCE;
    }
}
