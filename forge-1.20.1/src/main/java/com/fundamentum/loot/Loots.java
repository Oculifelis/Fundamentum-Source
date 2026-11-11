package com.fundamentum.loot;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Loot helpers: pools ({@link LootPools}), additions ({@link LootAdditions}, run by the global loot modifier
 * {@code fundamentum:additions}), the {@code fundamentum:from_pool} function and {@code fundamentum:pool} condition, boss
 * drops ({@link BossDrops}) and a datagen helper ({@link LootDatagen}).
 */
public final class Loots {
    private Loots() { }

    private static final DeferredRegister<Codec<? extends IGlobalLootModifier>> MODIFIERS =
        DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, "fundamentum");
    private static final DeferredRegister<LootItemFunctionType> FUNCTIONS =
        DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, "fundamentum");
    private static final DeferredRegister<LootItemConditionType> CONDITIONS =
        DeferredRegister.create(Registries.LOOT_CONDITION_TYPE, "fundamentum");

    static {
        MODIFIERS.register("additions", () -> AdditionsModifier.CODEC);
    }

    static final RegistryObject<LootItemFunctionType> FROM_POOL =
        FUNCTIONS.register("from_pool", () -> new LootItemFunctionType(FromPoolFunction.SERIALIZER));
    static final RegistryObject<LootItemConditionType> POOL =
        CONDITIONS.register("pool", () -> new LootItemConditionType(PoolCondition.SERIALIZER));

    public static void init(IEventBus modBus) {
        MODIFIERS.register(modBus);
        FUNCTIONS.register(modBus);
        CONDITIONS.register(modBus);
        LootPools.init();
        LootAdditions.TABLE.id();
        BossDrops.init();
    }
}
