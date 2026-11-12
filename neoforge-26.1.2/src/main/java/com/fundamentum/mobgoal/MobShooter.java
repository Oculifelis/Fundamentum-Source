package com.fundamentum.mobgoal;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * Fires one shot for {@code fundamentum:ranged_ammo} once the ammo is paid for. Return false to let the next shooter
 * (and finally the mob's own ranged attack, when it has one) handle it. Add one with {@link MobGoals#addShooter}.
 */
@FunctionalInterface
public interface MobShooter {
    boolean fire(Mob mob, LivingEntity target, ItemStack ammo, float power);
}
