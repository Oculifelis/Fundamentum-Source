package com.fundamentum.enchant;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * One moment a weapon's enchantments may change: a scythe sweep, a flail hit, a parry. The weapon code fills in what it
 * knows, {@link FEnchantments#fire} lets the enchantments and Java listeners change the mutable numbers, and the weapon
 * code then uses them. Hooks: on_sweep (radius), on_scythe_hit (damage), on_flail_hit (damage, knockback, charge),
 * on_block (reflect is the chance a blocked projectile flies back), on_staggered (stagger and knockback scale what the
 * holder takes), on_bash (damage, knockback, stagger), on_combo (damage, count is the combo step), on_thrown_hit
 * (damage) and on_kill (the killer's main hand).
 */
public final class WeaponHook {
    public final String hook;
    public final ServerLevel level;
    public final LivingEntity user;
    public final ItemStack stack;
    public final @Nullable Entity target;

    public float damage;
    public double knockback;
    /** A sweep's reach before {@link #radius}, in blocks. */
    public double reach;
    /** Multiplier on a sweep's reach. */
    public double radius = 1;
    /** Multiplier on stagger time. */
    public double stagger = 1;
    /** On on_block, the chance (0 to 1) that a blocked projectile flies back. */
    public double reflect;
    /** A flail's spin charge, 0 to 1. */
    public double charge;
    /** A combo step or a hit count. */
    public int count;
    /** A free tag the weapon may set, like the flail mode. */
    public String mode = "";

    public WeaponHook(String hook, ServerLevel level, LivingEntity user, ItemStack stack, @Nullable Entity target) {
        this.hook = hook;
        this.level = level;
        this.user = user;
        this.stack = stack;
        this.target = target;
    }

    public WeaponHook damage(float damage) {
        this.damage = damage;
        return this;
    }

    public WeaponHook knockback(double knockback) {
        this.knockback = knockback;
        return this;
    }

    public WeaponHook reach(double reach) {
        this.reach = reach;
        return this;
    }

    public WeaponHook charge(double charge) {
        this.charge = charge;
        return this;
    }

    public WeaponHook count(int count) {
        this.count = count;
        return this;
    }

    public WeaponHook reflect(double reflect) {
        this.reflect = reflect;
        return this;
    }

    public WeaponHook mode(String mode) {
        this.mode = mode;
        return this;
    }

    public double field(String name) {
        return switch (name) {
            case "damage" -> damage;
            case "knockback" -> knockback;
            case "radius" -> radius;
            case "stagger" -> stagger;
            case "reflect" -> reflect;
            default -> 0;
        };
    }

    public void field(String name, double value) {
        switch (name) {
            case "damage" -> damage = (float) Math.max(0, value);
            case "knockback" -> knockback = Math.max(0, value);
            case "radius" -> radius = Math.max(0.1, value);
            case "stagger" -> stagger = Math.max(0, value);
            case "reflect" -> reflect = Math.max(0, Math.min(1, value));
            default -> { }
        }
    }
}
