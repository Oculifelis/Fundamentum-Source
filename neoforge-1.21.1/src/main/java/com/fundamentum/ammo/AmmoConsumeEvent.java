package com.fundamentum.ammo;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Fired on the game bus before {@link Ammo#consume} takes ammo, server side, including shots from the vanilla bow and
 * crossbow. Cancel it to use no ammo at all; change {@link #setAmount} or {@link #setConservationChance} (the chance, per
 * unit, that the unit is kept; starts at the shooter's {@code fundamentum:ammo_conservation}).
 */
public final class AmmoConsumeEvent extends Event implements ICancellableEvent {
    private final LivingEntity shooter;
    private final ItemStack weapon;
    private final Ammo.Found ammo;
    private int amount;
    private double conservationChance;

    public AmmoConsumeEvent(LivingEntity shooter, ItemStack weapon, Ammo.Found ammo, int amount, double conservationChance) {
        this.shooter = shooter;
        this.weapon = weapon;
        this.ammo = ammo;
        this.amount = amount;
        this.conservationChance = conservationChance;
    }

    public LivingEntity getShooter() { return this.shooter; }
    public ItemStack getWeapon() { return this.weapon; }
    /** Where the ammo is and what it is. */
    public Ammo.Found getAmmo() { return this.ammo; }
    public int getAmount() { return this.amount; }
    public void setAmount(int amount) { this.amount = Math.max(0, amount); }
    public double getConservationChance() { return this.conservationChance; }
    public void setConservationChance(double chance) { this.conservationChance = Math.max(0, Math.min(1, chance)); }
}
