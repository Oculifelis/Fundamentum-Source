package com.fundamentum.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;

/** {@code fundamentum:additions}: the one global loot modifier that runs every loot addition (Forge's global loot modifier). */
public final class AdditionsModifier extends LootModifier {
    public static final Codec<AdditionsModifier> CODEC = RecordCodecBuilder.create(i -> codecStart(i).apply(i, AdditionsModifier::new));

    public AdditionsModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        LootAdditions.apply(context.getQueriedLootTableId(), loot, context);
        return loot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
