package com.fundamentum.loot;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;

/**
 * {@code fundamentum:from_pool}: the entry's item becomes one roll of a fundamentum pool ({"function":
 * "fundamentum:from_pool", "pool": "mymod:gems"}). Put it on any item entry (the item is only a placeholder); an empty roll
 * gives nothing. Pair it with the {@code fundamentum:pool} condition to skip the entry when the pool is missing.
 * (1.20.1 loot functions are read with a JSON serializer, not a codec.)
 */
public record FromPoolFunction(ResourceLocation pool) implements LootItemFunction {
    public static final net.minecraft.world.level.storage.loot.Serializer<FromPoolFunction> SERIALIZER =
        new net.minecraft.world.level.storage.loot.Serializer<>() {
            @Override
            public void serialize(JsonObject json, FromPoolFunction f, JsonSerializationContext context) {
                json.addProperty("pool", f.pool.toString());
            }

            @Override
            public FromPoolFunction deserialize(JsonObject json, JsonDeserializationContext context) {
                return new FromPoolFunction(new ResourceLocation(GsonHelper.getAsString(json, "pool")));
            }
        };

    @Override
    public ItemStack apply(ItemStack stack, LootContext context) {
        List<ItemStack> rolled = LootPools.roll(this.pool, context);
        return rolled.isEmpty() ? ItemStack.EMPTY : rolled.get(0);
    }

    @Override
    public LootItemFunctionType getType() {
        return Loots.FROM_POOL.get();
    }
}
