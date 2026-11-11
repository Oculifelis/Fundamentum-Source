package com.fundamentum.loot;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;

/** {@code fundamentum:additions}: the one global loot modifier that runs every loot addition. */
public final class AdditionsModifier extends LootModifier {
    public static final MapCodec<AdditionsModifier> CODEC = RecordCodecBuilder.mapCodec(i -> codecStart(i).apply(i, AdditionsModifier::new));

    public AdditionsModifier(LootItemCondition[] conditions, int priority) {
        super(conditions, priority);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        LootAdditions.apply(context.getQueriedLootTableId(), loot, context);
        return loot;
    }

    @Override
    public MapCodec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
