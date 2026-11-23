package com.fundamentum.loot;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

/**
 * {@code fundamentum:pool}: true when the named fundamentum pool exists and can give at least one item that exists, so an
 * entry using {@code fundamentum:from_pool} drops out cleanly when its pool or its mod is missing. {@code chance} (default
 * 1) also has to pass. (1.20.1 loot conditions are read with a JSON serializer, not a codec.)
 */
public record PoolCondition(ResourceLocation pool, float chance) implements LootItemCondition {
    public static final net.minecraft.world.level.storage.loot.Serializer<PoolCondition> SERIALIZER =
        new net.minecraft.world.level.storage.loot.Serializer<>() {
            @Override
            public void serialize(JsonObject json, PoolCondition c, JsonSerializationContext context) {
                json.addProperty("pool", c.pool.toString());
                if (c.chance < 1f) json.addProperty("chance", c.chance);
            }

            @Override
            public PoolCondition deserialize(JsonObject json, JsonDeserializationContext context) {
                return new PoolCondition(new ResourceLocation(GsonHelper.getAsString(json, "pool")), GsonHelper.getAsFloat(json, "chance", 1f));
            }
        };

    @Override
    public boolean test(LootContext context) {
        return LootPools.usable(this.pool) && (this.chance >= 1f || context.getRandom().nextFloat() < this.chance);
    }

    @Override
    public LootItemConditionType getType() {
        return Loots.POOL.get();
    }
}
