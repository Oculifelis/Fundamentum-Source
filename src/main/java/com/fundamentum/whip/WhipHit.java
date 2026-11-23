package com.fundamentum.whip;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** One hit of a cord on a target, handed to {@link WhipHooks}. Server side. */
public final class WhipHit {
    public final ServerPlayer player;
    public final ItemStack stack;
    public final WhipDefinition whip;
    /** What the cord touched: the entity itself, or a part of a multipart one. */
    public final Entity touched;
    /** The whole target: {@link #touched}, or the multipart entity it is a part of. */
    public final Entity target;
    public final Vec3 contact;
    /** 0 (handle) to 1 (tip) along the cord. */
    public final double along;
    public final int strand;
    /** This swing's how-manyth target (0 the first). */
    public final int index;
    /** On the tip ({@link WhipTuning#tipFraction}). */
    public final boolean tip;
    /** Tip speed in blocks per second at the hit. */
    public final double tipSpeed;
    /** Near the crosshair (the aim assist cone). */
    public final boolean nearCrosshair;
    /** A tip hit at crack speed: the crit multiplier is already in {@link #damage}. */
    public boolean crit;
    /** The damage this hit will deal (before armour and enchantments); hooks may change it. */
    public float damage;
    private boolean cancelled;

    WhipHit(ServerPlayer player, ItemStack stack, WhipDefinition whip, Entity touched, Entity target, Vec3 contact, double along,
            int strand, int index, boolean tip, double tipSpeed, boolean nearCrosshair, boolean crit, float damage) {
        this.player = player;
        this.stack = stack;
        this.whip = whip;
        this.touched = touched;
        this.target = target;
        this.contact = contact;
        this.along = along;
        this.strand = strand;
        this.index = index;
        this.tip = tip;
        this.tipSpeed = tipSpeed;
        this.nearCrosshair = nearCrosshair;
        this.crit = crit;
        this.damage = damage;
    }

    /** The target as a living entity, or null (an end crystal, a boat...). */
    public @Nullable LivingEntity living() {
        return target instanceof LivingEntity l ? l : null;
    }

    /** No damage and no {@code landed}; the target still counts as hit this swing. */
    public void cancel() {
        cancelled = true;
    }

    public boolean cancelled() {
        return cancelled;
    }
}
