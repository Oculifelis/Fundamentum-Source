package com.fundamentum.shield;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.Cancelable;

/**
 * Shield blocks, on the game bus, server side, for every blocked hit (any shield, vanilla's too). A combat mod builds
 * its parry or riposte on these without patching Fundamentum: change the blocked amount in {@link Pre}, mark the hit
 * {@link Pre#setParried parried} and leave notes in {@link Pre#data()}; read the outcome in {@link Post}.
 */
public final class ShieldBlockEvent {
    /**
     * Before the block takes effect (and before Fundamentum's own shield parry). Cancel it and the hit is not blocked at
     * all. {@code parried} starts true when the hit falls in the shield's own parry window; it is a flag for mods to
     * read and set, and does not by itself switch Fundamentum's parry on or off.
     */
    @Cancelable
    public static final class Pre extends Event {
        private final LivingEntity defender;
        private final DamageSource source;
        private final ItemStack shield;
        private final float originalBlocked;
        private float blocked;
        private boolean parried;
        private final Map<String, Object> data = new HashMap<>();

        public Pre(LivingEntity defender, DamageSource source, ItemStack shield, float blocked, boolean parried) {
            this.defender = defender;
            this.source = source;
            this.shield = shield;
            this.originalBlocked = blocked;
            this.blocked = blocked;
            this.parried = parried;
        }

        public LivingEntity getDefender() { return this.defender; }
        public DamageSource getSource() { return this.source; }
        /** The item blocking (may be empty for a block not made with an item). */
        public ItemStack getShield() { return this.shield; }
        /** How much the block would have stopped before any listener changed it. */
        public float getOriginalBlockedAmount() { return this.originalBlocked; }
        public float getBlockedAmount() { return this.blocked; }
        public void setBlockedAmount(float amount) { this.blocked = Math.max(0, amount); }
        public boolean isParried() { return this.parried; }
        public void setParried(boolean parried) { this.parried = parried; }
        /** Free notes for other listeners; handed on to {@link Post#data()}. */
        public Map<String, Object> data() { return this.data; }
    }

    /** After every listener had its say: what the block came to. */
    public static final class Post extends Event {
        private final LivingEntity defender;
        private final DamageSource source;
        private final ItemStack shield;
        private final boolean blocked;
        private final float blockedAmount;
        private final boolean parried;
        private final Map<String, Object> data;

        public Post(LivingEntity defender, DamageSource source, ItemStack shield, boolean blocked, float blockedAmount, boolean parried,
                    Map<String, Object> data) {
            this.defender = defender;
            this.source = source;
            this.shield = shield;
            this.blocked = blocked;
            this.blockedAmount = blockedAmount;
            this.parried = parried;
            this.data = data;
        }

        public LivingEntity getDefender() { return this.defender; }
        public DamageSource getSource() { return this.source; }
        public ItemStack getShield() { return this.shield; }
        /** False when the block was canceled (by {@link Pre} or another mod): the hit went through. */
        public boolean wasBlocked() { return this.blocked; }
        /** The damage the block stopped (0 when not blocked). */
        public float getBlockedAmount() { return this.blocked ? this.blockedAmount : 0; }
        public boolean isParried() { return this.parried; }
        public Map<String, Object> data() { return this.data; }
    }

    private ShieldBlockEvent() {}
}
