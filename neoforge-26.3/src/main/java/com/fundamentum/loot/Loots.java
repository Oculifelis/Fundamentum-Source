package com.fundamentum.loot;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Loot helpers: pools ({@link LootPools}), additions ({@link LootAdditions}, run by the global loot modifier
 * {@code fundamentum:additions}), the {@code fundamentum:from_pool} function and {@code fundamentum:pool} condition, boss
 * drops ({@link BossDrops}) and a datagen helper ({@link LootDatagen}).
 */
public final class Loots {
    private Loots() { }

    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> MODIFIERS =
        DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, "fundamentum");
    private static final DeferredRegister<MapCodec<? extends LootItemFunction>> FUNCTIONS =
        DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, "fundamentum");
    private static final DeferredRegister<MapCodec<? extends LootItemCondition>> CONDITIONS =
        DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, "fundamentum");

    static {
        MODIFIERS.register("additions", () -> AdditionsModifier.CODEC);
        FUNCTIONS.register("from_pool", () -> FromPoolFunction.CODEC);
        CONDITIONS.register("pool", () -> PoolCondition.CODEC);
    }

    public static void init(IEventBus modBus) {
        MODIFIERS.register(modBus);
        FUNCTIONS.register(modBus);
        CONDITIONS.register(modBus);
        LootPools.init();
        LootAdditions.TABLE.id();
        BossDrops.init();
    }
}
