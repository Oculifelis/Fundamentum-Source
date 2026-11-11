package com.fundamentum.loot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;

/** Pools and helpers registered from code. */
final class LootCode {
    private LootCode() { }

    static final Map<ResourceLocation, LootPools.Pool> POOLS = new ConcurrentHashMap<>();

    static void rollInline(LootPools.Pool pool, LootContext context, Consumer<ItemStack> out) {
        LootPools.rollPool(pool, context.getRandom(), context, out, 0);
    }
}
