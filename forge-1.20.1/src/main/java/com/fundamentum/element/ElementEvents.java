package com.fundamentum.element;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.Cancelable;
import org.jetbrains.annotations.Nullable;

/**
 * The events of elemental interactions, all on {@code MinecraftForge.EVENT_BUS}, all fired on the server thread. Mods hook a
 * reaction by its id (the JSON file's id) in {@link Reaction}, change what a creature resists in {@link Affinity}, and
 * change or stop auras in {@link AuraApply}.
 */
public final class ElementEvents {
    /**
     * A reaction is about to fire on {@code target}: {@code aura} (one of its auras) met a hit of the reaction's trigger
     * element. Cancel it to stop the reaction (the hit goes on as a plain hit); {@link #setAmount} changes the damage the
     * reaction leaves the hit with (after its multiplier and bonus). {@link #depth} is how many reactions are running
     * further up the chain (0 for a hit that isn't from a reaction).
     */
    @Cancelable
    public static final class Reaction extends Event {
        private final ResourceLocation id;
        private final ElementReactions.Reaction reaction;
        private final LivingEntity target;
        private final DamageSource source;
        private final ElementAuras.Aura aura;
        private final int depth;
        private float amount;

        public Reaction(ResourceLocation id, ElementReactions.Reaction reaction, LivingEntity target, DamageSource source, ElementAuras.Aura aura,
                        float amount, int depth) {
            this.id = id;
            this.reaction = reaction;
            this.target = target;
            this.source = source;
            this.aura = aura;
            this.amount = amount;
            this.depth = depth;
        }

        public ResourceLocation getId() { return this.id; }
        public ElementReactions.Reaction getReaction() { return this.reaction; }
        public LivingEntity getTarget() { return this.target; }
        public DamageSource getSource() { return this.source; }
        public ElementAuras.Aura getAura() { return this.aura; }
        public int getDepth() { return this.depth; }
        public float getAmount() { return this.amount; }
        public void setAmount(float amount) { this.amount = Math.max(0, amount); }
    }

    /** After a reaction fired and applied its results (for sounds, advancements, stats). */
    public static final class Reacted extends Event {
        private final ResourceLocation id;
        private final ElementReactions.Reaction reaction;
        private final LivingEntity target;
        private final DamageSource source;

        public Reacted(ResourceLocation id, ElementReactions.Reaction reaction, LivingEntity target, DamageSource source) {
            this.id = id;
            this.reaction = reaction;
            this.target = target;
            this.source = source;
        }

        public ResourceLocation getId() { return this.id; }
        public ElementReactions.Reaction getReaction() { return this.reaction; }
        public LivingEntity getTarget() { return this.target; }
        public DamageSource getSource() { return this.source; }
    }

    /** What a creature resists of a hit, summed from data; change it, or make the creature immune. */
    public static final class Affinity extends Event {
        private final LivingEntity entity;
        private final DamageSource source;
        private float resistance;
        private boolean immune;

        public Affinity(LivingEntity entity, DamageSource source, float resistance, boolean immune) {
            this.entity = entity;
            this.source = source;
            this.resistance = resistance;
            this.immune = immune;
        }

        public LivingEntity getEntity() { return this.entity; }
        public DamageSource getSource() { return this.source; }
        public float getResistance() { return this.resistance; }
        /** 0.5 takes half, -0.5 takes 1.5 times, 1 or more is immune. */
        public void setResistance(float resistance) { this.resistance = resistance; }
        public void addResistance(float resistance) { this.resistance += resistance; }
        public boolean isImmune() { return this.immune; }
        public void setImmune(boolean immune) { this.immune = immune; }
    }

    /** An aura is about to be put on (or stacked onto) a creature; cancel it, or swap its definition. */
    @Cancelable
    public static final class AuraApply extends Event {
        private final LivingEntity entity;
        private final ElementRef aura;
        private final @Nullable DamageSource source;
        private ElementAuras.AuraDef def;

        public AuraApply(LivingEntity entity, ElementRef aura, ElementAuras.AuraDef def, @Nullable DamageSource source) {
            this.entity = entity;
            this.aura = aura;
            this.def = def;
            this.source = source;
        }

        public LivingEntity getEntity() { return this.entity; }
        public ElementRef getAura() { return this.aura; }
        /** The hit that leaves it, or null when code applies it directly. */
        public @Nullable DamageSource getSource() { return this.source; }
        public ElementAuras.AuraDef getDef() { return this.def; }
        public void setDef(ElementAuras.AuraDef def) { this.def = def; }
    }

    private ElementEvents() {}
}
