package com.fundamentum.food;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * {@code fundamentum:food_eaten}, cancellable: a player is about to finish eating or drinking {@code stack} (a copy,
 * taken before it is used up). {@code food} holds the values after every {@link Food#modify modifier} ran;
 * {@code nutrition} and {@code saturation} repeat them for event matching. Cancelling stops the eat: the item stays,
 * nothing is applied.
 */
public record FoodEaten(ServerPlayer player, ItemStack stack, boolean drink, int nutrition, float saturation, FoodValues food) { }
