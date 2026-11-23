package com.fundamentum.effect;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * The events of data-driven status effects, all on {@code NeoForge.EVENT_BUS}, all on the server thread. {@link Apply} comes
 * first and may be cancelled or changed; then {@link Applied} (the effect was not there) or {@link Stacked} (it was). It ends
 * with {@link Expired}, {@link Cured} or {@link Removed}.
 */
public final class EffectEvent {
    /** Before an effect is applied or stacked. Cancel it to refuse the effect; the duration and amplifier may be changed. */
    public static final class Apply extends Event implements ICancellableEvent {
        private final LivingEntity entity;
        private final EffectDefinition definition;
        private int duration;
        private int amplifier;

        public Apply(LivingEntity entity, EffectDefinition definition, int duration, int amplifier) {
            this.entity = entity;
            this.definition = definition;
            this.duration = duration;
            this.amplifier = amplifier;
        }

        public LivingEntity getEntity() { return this.entity; }
        public EffectDefinition getDefinition() { return this.definition; }
        /** Ticks; negative for one that never runs out. */
        public int getDuration() { return this.duration; }
        public void setDuration(int duration) { this.duration = duration; }
        public int getAmplifier() { return this.amplifier; }
        public void setAmplifier(int amplifier) { this.amplifier = Math.max(0, amplifier); }
    }

    /** The effect started on an entity that did not have it. */
    public static final class Applied extends Event {
        private final LivingEntity entity;
        private final EffectDefinition definition;
        private final ActiveEffect effect;

        public Applied(LivingEntity entity, EffectDefinition definition, ActiveEffect effect) {
            this.entity = entity;
            this.definition = definition;
            this.effect = effect;
        }

        public LivingEntity getEntity() { return this.entity; }
        public EffectDefinition getDefinition() { return this.definition; }
        public ActiveEffect getEffect() { return this.effect; }
    }

    /** The effect was applied again while it lasted and stacked by its rule. */
    public static final class Stacked extends Event {
        private final LivingEntity entity;
        private final EffectDefinition definition;
        private final ActiveEffect effect;
        private final int oldLevel;

        public Stacked(LivingEntity entity, EffectDefinition definition, ActiveEffect effect, int oldLevel) {
            this.entity = entity;
            this.definition = definition;
            this.effect = effect;
            this.oldLevel = oldLevel;
        }

        public LivingEntity getEntity() { return this.entity; }
        public EffectDefinition getDefinition() { return this.definition; }
        public ActiveEffect getEffect() { return this.effect; }
        public int getOldLevel() { return this.oldLevel; }
        public int getNewLevel() { return this.effect.level(this.definition); }
    }

    /** The effect ran out. */
    public static final class Expired extends Event {
        private final LivingEntity entity;
        private final EffectDefinition definition;

        public Expired(LivingEntity entity, EffectDefinition definition) {
            this.entity = entity;
            this.definition = definition;
        }

        public LivingEntity getEntity() { return this.entity; }
        public EffectDefinition getDefinition() { return this.definition; }
    }

    /** The effect was cured with a cure tag. */
    public static final class Cured extends Event {
        private final LivingEntity entity;
        private final EffectDefinition definition;
        private final ResourceLocation cure;

        public Cured(LivingEntity entity, EffectDefinition definition, ResourceLocation cure) {
            this.entity = entity;
            this.definition = definition;
            this.cure = cure;
        }

        public LivingEntity getEntity() { return this.entity; }
        public EffectDefinition getDefinition() { return this.definition; }
        public ResourceLocation getCure() { return this.cure; }
    }

    /** The effect was taken off by {@link Effects#remove} or by death. */
    public static final class Removed extends Event {
        private final LivingEntity entity;
        private final EffectDefinition definition;

        public Removed(LivingEntity entity, EffectDefinition definition) {
            this.entity = entity;
            this.definition = definition;
        }

        public LivingEntity getEntity() { return this.entity; }
        public EffectDefinition getDefinition() { return this.definition; }
    }

    private EffectEvent() {}
}
