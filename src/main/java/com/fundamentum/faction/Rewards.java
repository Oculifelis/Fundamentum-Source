package com.fundamentum.faction;

import com.fundamentum.port.FId;
import com.fundamentum.port.FVanilla;
import com.fundamentum.unlock.Unlocks;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * Rewards given by rank rewards, quests and boons, all from one JSON shape:
 * <pre>
 * { "items": [ { "id": "minecraft:emerald", "count": 5 } ], "loot": "minecraft:chests/village/village_weaponsmith",
 *   "xp": 30, "effects": [ { "id": "minecraft:regeneration", "duration": 200, "amplifier": 0 } ],
 *   "unlocks": [ "mymod:lantern_recipes" ], "rep": { "mymod:lantern": 50 }, "alignment": { "mymod:order": 5 },
 *   "favour": { "mymod:sun_god": 10 } }
 * </pre>
 * Mods add keys with {@link #register}.
 */
public final class Rewards {
    private Rewards() { }

    private static final Map<String, BiConsumer<ServerPlayer, JsonElement>> TYPES = new ConcurrentHashMap<>();

    /** Adds a reward key; the handler gets the player and that key's value. */
    public static void register(String key, BiConsumer<ServerPlayer, JsonElement> handler) {
        TYPES.put(key, handler);
    }

    /** Gives everything in a reward object. Unknown keys are skipped. */
    public static void give(ServerPlayer player, JsonElement rewards) {
        if (rewards == null || !rewards.isJsonObject()) return;
        JsonObject o = rewards.getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            switch (e.getKey()) {
                case "items" -> {
                    for (JsonElement i : e.getValue().isJsonArray() ? e.getValue().getAsJsonArray() : List.of(e.getValue())) items(player, i);
                }
                case "loot" -> loot(player, FId.tryOf(e.getValue().getAsString()));
                case "xp" -> player.giveExperiencePoints(e.getValue().getAsInt());
                case "effects" -> {
                    for (JsonElement i : e.getValue().getAsJsonArray()) effect(player, i.getAsJsonObject());
                }
                case "unlocks" -> {
                    for (String u : FactionDef.strings(e.getValue())) {
                        FId id = FId.tryOf(u);
                        if (id != null) Unlocks.grant(player, id);
                    }
                }
                case "rep" -> e.getValue().getAsJsonObject().entrySet().forEach(r -> {
                    FId f = FId.tryOf(r.getKey());
                    if (f != null) Factions.addRep(player, f, r.getValue().getAsInt());
                });
                case "alignment" -> e.getValue().getAsJsonObject().entrySet().forEach(r -> {
                    FId a = FId.tryOf(r.getKey());
                    if (a != null) Factions.addAlignment(player, a, r.getValue().getAsDouble());
                });
                default -> {
                    BiConsumer<ServerPlayer, JsonElement> h = TYPES.get(e.getKey());
                    if (h != null) h.accept(player, e.getValue());
                }
            }
        }
    }

    private static void items(ServerPlayer player, JsonElement e) {
        FId id;
        int count = 1;
        if (e.isJsonPrimitive()) {
            id = FId.tryOf(e.getAsString());
        } else {
            JsonObject o = e.getAsJsonObject();
            id = FId.tryOf(o.get("id").getAsString());
            count = FactionDef.i(o, "count", 1);
        }
        if (id == null) return;
        Item item = FVanilla.item(id);
        if (item == null) return;
        handOver(player, new ItemStack(item, count));
    }

    static void handOver(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    private static void loot(ServerPlayer player, FId table) {
        if (table == null) return;
        ServerLevel level = (ServerLevel) player.level();
        LootTable t = level.getServer().reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, table.vanilla()));
        LootParams params = new LootParams.Builder(level)
            .withParameter(LootContextParams.ORIGIN, player.position())
            .withOptionalParameter(LootContextParams.THIS_ENTITY, player)
            .withLuck(player.getLuck())
            .create(LootContextParamSets.CHEST);
        for (ItemStack s : new ArrayList<>(t.getRandomItems(params))) handOver(player, s);
    }

    private static void effect(ServerPlayer player, JsonObject o) {
        FId id = FId.tryOf(o.get("id").getAsString());
        if (id == null) return;
        Holder<MobEffect> h = BuiltInRegistries.MOB_EFFECT.get(id.vanilla()).<Holder<MobEffect>>map(x -> x).orElse(null);
        if (h != null) FVanilla.addEffect(player, h, FactionDef.i(o, "duration", 200), FactionDef.i(o, "amplifier", 0));
    }
}
