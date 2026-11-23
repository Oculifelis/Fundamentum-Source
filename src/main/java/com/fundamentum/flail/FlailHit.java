package com.fundamentum.flail;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** One of Fundamentum's own flail hits, before it is dealt (see {@link FlailHooks#hit}). */
public final class FlailHit {
    public final ServerPlayer player;
    public final ItemStack stack;
    public final FlailSpec spec;
    public final LivingEntity target;
    /** Where the head (or link) touched. */
    public final Vec3 contact;
    /** Which head, from 0. */
    public final int head;
    /** True for a hit by the chain rather than a head ({@code chain_hits}). */
    public final boolean chain;
    /** The head's speed, blocks a tick. */
    public final double speed;
    /** What the hand was doing: swing, spin, smash, launch or reel. */
    public final FlailSim.Mode mode;
    /** The damage to deal; hooks may change it. */
    public float damage;
    private boolean cancelled;

    FlailHit(ServerPlayer player, ItemStack stack, FlailSpec spec, LivingEntity target, Vec3 contact, int head, boolean chain, double speed,
             FlailSim.Mode mode, float damage) {
        this.player = player;
        this.stack = stack;
        this.spec = spec;
        this.target = target;
        this.contact = contact;
        this.head = head;
        this.chain = chain;
        this.speed = speed;
        this.mode = mode;
        this.damage = damage;
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean cancelled() {
        return cancelled;
    }
}
