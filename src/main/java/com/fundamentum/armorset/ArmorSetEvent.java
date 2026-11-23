package com.fundamentum.armorset;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.Event;

/** Armour set events, on the game bus, server side, after the tier's rewards were granted or taken away. */
public final class ArmorSetEvent {
    /** The wearer reached a tier (one event per tier, lowest first). */
    public static final class TierGained extends Event {
        private final LivingEntity entity;
        private final ArmorSetDefinition set;
        private final ArmorSetDefinition.Tier tier;
        private final int pieces;

        public TierGained(LivingEntity entity, ArmorSetDefinition set, ArmorSetDefinition.Tier tier, int pieces) {
            this.entity = entity;
            this.set = set;
            this.tier = tier;
            this.pieces = pieces;
        }

        public LivingEntity getEntity() { return this.entity; }
        public ArmorSetDefinition getSet() { return this.set; }
        public ArmorSetDefinition.Tier getTier() { return this.tier; }
        /** How many pieces are worn now. */
        public int getPieces() { return this.pieces; }
    }

    /** The wearer dropped below a tier (one event per tier, highest first). */
    public static final class TierLost extends Event {
        private final LivingEntity entity;
        private final ArmorSetDefinition set;
        private final ArmorSetDefinition.Tier tier;
        private final int pieces;

        public TierLost(LivingEntity entity, ArmorSetDefinition set, ArmorSetDefinition.Tier tier, int pieces) {
            this.entity = entity;
            this.set = set;
            this.tier = tier;
            this.pieces = pieces;
        }

        public LivingEntity getEntity() { return this.entity; }
        public ArmorSetDefinition getSet() { return this.set; }
        public ArmorSetDefinition.Tier getTier() { return this.tier; }
        public int getPieces() { return this.pieces; }
    }

    private ArmorSetEvent() {}
}
