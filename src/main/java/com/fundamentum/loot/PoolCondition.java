package com.fundamentum.loot;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/**
 * {@code fundamentum:pool}: true when the named fundamentum pool exists and can give at least one item that exists, so an
 * entry using {@code fundamentum:from_pool} drops out cleanly when its pool or its mod is missing. {@code chance} (default
 * 1) also has to pass.
 */
public record PoolCondition(ResourceLocation pool, float chance) implements LootItemCondition {
    public static final MapCodec<PoolCondition> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
        ResourceLocation.CODEC.fieldOf("pool").forGetter(PoolCondition::pool),
        com.mojang.serialization.Codec.FLOAT.optionalFieldOf("chance", 1f).forGetter(PoolCondition::chance)).apply(i, PoolCondition::new));

    @Override
    public boolean test(LootContext context) {
        return LootPools.usable(this.pool) && (this.chance >= 1f || context.getRandom().nextFloat() < this.chance);
    }

    @Override
    public net.minecraft.world.level.storage.loot.predicates.LootItemConditionType getType() {
        return Loots.POOL.get();
    }
}
