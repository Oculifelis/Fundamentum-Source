package com.fundamentum.event;

import com.fundamentum.port.FId;
import com.fundamentum.port.FReg;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.core.Holder;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ExtraCodecs;

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

    public record Instance(Optional<Holder<LootItemCondition>> player, String event, Optional<JsonElement> where)
        implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
            LootItemCondition.CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.STRING.fieldOf("event").forGetter(Instance::event),
            ExtraCodecs.JSON.optionalFieldOf("where").forGetter(Instance::where)
        ).apply(i, Instance::new));

        public boolean test(FEvents.Type type, Record event) {
            FId want = FId.tryOf(this.event);
            if (want == null || !want.equals(type.id())) return false;
            JsonObject w = this.where.filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElse(null);
            return FEvents.matches(event, w);
        }
    }

    private EventTrigger() { }

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    /** Called once while Fundamentum loads: registers the trigger and hooks it to {@link FEvents#post}. */
    public static void init() {
        if (initialised) return;
        initialised = true;
        FReg.other(Registries.TRIGGER_TYPE, ID, () -> INSTANCE);
        FEvents.bridge((type, event, cancelled) -> {
            ServerPlayer player = FEvents.player(event);
            if (player != null && !cancelled) INSTANCE.trigger(player, i -> i.test(type, event));
            return false;
        });
    }

    /** Reads trigger conditions from JSON (for checks and tools). */
    public static Instance read(JsonElement conditions) {
        return Instance.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, conditions).getOrThrow();
    }

    /** True when the player has completed the advancement. */
    public static boolean done(ServerPlayer player, FId advancement) {
        AdvancementHolder holder = player.level().getServer().getAdvancements().get(advancement.vanilla());
        return holder != null && player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    /** True when the server has loaded the advancement. */
    public static boolean known(MinecraftServer server, FId advancement) {
        return server.getAdvancements().get(advancement.vanilla()) != null;
    }

    /** The trigger as the game's registry type, for code that needs it. */
    public static CriterionTrigger<?> trigger() {
        return INSTANCE;
    }
}
