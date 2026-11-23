package com.fundamentum.loot;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;

/**
 * {@code fundamentum:from_pool}: the entry's item becomes one roll of a fundamentum pool ({"function":
 * "fundamentum:from_pool", "pool": "mymod:gems"}). Put it on any item entry (the item is only a placeholder); an empty roll
 * gives nothing. Pair it with the {@code fundamentum:pool} condition to skip the entry when the pool is missing.
 */
public record FromPoolFunction(Identifier pool) implements LootItemFunction {
    public static final MapCodec<FromPoolFunction> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
        Identifier.CODEC.fieldOf("pool").forGetter(FromPoolFunction::pool)).apply(i, FromPoolFunction::new));

    @Override
    public ItemStack apply(ItemStack stack, LootContext context) {
        List<ItemStack> rolled = LootPools.roll(this.pool, context);
        return rolled.isEmpty() ? ItemStack.EMPTY : rolled.get(0);
    }

    @Override
    public MapCodec<? extends LootItemFunction> codec() {
        return CODEC;
    }
}
