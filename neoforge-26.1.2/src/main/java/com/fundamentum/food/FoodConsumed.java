package com.fundamentum.food;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * {@code fundamentum:food_consumed}: a player finished eating or drinking {@code stack} (a copy from before it was used
 * up) and {@code food} has been applied. Not cancellable; for reacting to meals (meters, diets, milestones).
 */
public record FoodConsumed(ServerPlayer player, ItemStack stack, boolean drink, int nutrition, float saturation, FoodValues food) { }
