package com.fundamentum.unlock;

import com.fundamentum.port.FId;
import com.fundamentum.port.FReg;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.advancements.criterion.ContextAwarePredicate;
import net.minecraft.advancements.criterion.EntityPredicate;
import net.minecraft.advancements.criterion.SimpleCriterionTrigger;
import net.minecraft.core.registries.Registries;
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

    public record Instance(Optional<ContextAwarePredicate> player, Optional<String> id, int minLevel)
        implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
            EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
            Codec.STRING.optionalFieldOf("id").forGetter(Instance::id),
            Codec.INT.optionalFieldOf("min_level", 1).forGetter(Instance::minLevel)
        ).apply(i, Instance::new));

        public boolean test(FId unlock, int level) {
            return level >= this.minLevel && this.id.map(s -> unlock.equals(FId.tryOf(s))).orElse(true);
        }
    }

    private UnlockTrigger() { }

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    static void init() {
        if (initialised) return;
        initialised = true;
        FReg.other(Registries.TRIGGER_TYPE, ID, () -> INSTANCE);
    }

    /** Fires the trigger for the player holding the unlock at this level. */
    public static void trigger(ServerPlayer player, FId unlock, int level) {
        INSTANCE.trigger(player, i -> i.test(unlock, level));
    }
}
