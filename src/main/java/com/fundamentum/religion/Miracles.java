package com.fundamentum.religion;

import com.fundamentum.faction.Rewards;
import com.fundamentum.port.FId;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * The miracle reward keys, usable in any reward object (boons, rituals, wrath, quests, ranks):
 * <ul>
 *   <li>{@code "heal": 10} heals that much health.</li>
 *   <li>{@code "weather": "clear" | "rain" | "thunder"} or {@code { "type": "rain", "duration": 6000 }}.</li>
 *   <li>{@code "smite": { "damage": 10, "radius": 16 }} strikes the nearest monster with lightning.</li>
 *   <li>{@code "lightning": true} or {@code { "damage": 5, "visual": false }} strikes the player.</li>
 *   <li>{@code "spawn": { "entity": "minecraft:wolf", "count": 2 }} spawns mobs beside the player.</li>
 *   <li>{@code "favour": 20} adds favour with the player's patron god, or {@code { "mymod:sun": 20 }} with named gods.</li>
 * </ul>
 */
final class Miracles {
    private Miracles() { }

    static void init() {
        Rewards.register("heal", (p, v) -> p.heal(v.getAsFloat()));
        Rewards.register("weather", Miracles::weather);
        Rewards.register("smite", Miracles::smite);
        Rewards.register("lightning", Miracles::lightning);
        Rewards.register("spawn", Miracles::spawn);
        Rewards.register("favour", Miracles::favour);
    }

    private static void weather(ServerPlayer p, JsonElement v) {
        String type;
        int duration = 6000;
        if (v.isJsonObject()) {
            JsonObject o = v.getAsJsonObject();
            type = Deity.s(o, "type", "clear");
            duration = Math.max(20, Deity.i(o, "duration", duration));
        } else {
            type = v.getAsString();
        }
        var server = ((ServerLevel) p.level()).getServer();
        switch (type) {
            case "rain" -> server.setWeatherParameters(0, duration, true, false);
            case "thunder" -> server.setWeatherParameters(0, duration, true, true);
            default -> server.setWeatherParameters(duration, 0, false, false);
        }
    }

    private static void smite(ServerPlayer p, JsonElement v) {
        JsonObject o = v.isJsonObject() ? v.getAsJsonObject() : new JsonObject();
        float damage = v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsFloat() : (float) Deity.d(o, "damage", 10);
        Entity target = Religion.nearestEnemy(p, Math.max(1, Math.min(32, Deity.d(o, "radius", 16))));
        if (target == null) return;
        Religion.lightning((ServerLevel) p.level(), target.getX(), target.getY(), target.getZ(), p, 0);
        if (target instanceof LivingEntity le) le.hurtServer((ServerLevel) p.level(), p.damageSources().lightningBolt(), damage);
    }

    private static void lightning(ServerPlayer p, JsonElement v) {
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && !v.getAsBoolean()) return;
        JsonObject o = v.isJsonObject() ? v.getAsJsonObject() : new JsonObject();
        float damage = (float) Deity.d(o, "damage", 5);
        Religion.lightning((ServerLevel) p.level(), p.getX(), p.getY(), p.getZ(), null, Deity.bool(o, "visual", false) ? 0 : damage);
    }

    private static void spawn(ServerPlayer p, JsonElement v) {
        JsonObject o = v.isJsonObject() ? v.getAsJsonObject() : new JsonObject();
        FId id = v.isJsonPrimitive() ? FId.tryOf(v.getAsString()) : Deity.id(o, "entity");
        if (id == null) return;
        int count = Math.max(1, Math.min(16, Deity.i(o, "count", 1)));
        ServerLevel level = (ServerLevel) p.level();
        for (int n = 0; n < count; n++) {
            Entity e = FVanilla.create(id, level);
            if (e == null) return;
            double a = p.getRandom().nextDouble() * Math.PI * 2;
            e.setPos(p.getX() + Math.cos(a) * 2, p.getY(), p.getZ() + Math.sin(a) * 2);
            level.addFreshEntity(e);
        }
    }

    private static void favour(ServerPlayer p, JsonElement v) {
        if (v.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet()) {
                FId g = FId.tryOf(e.getKey());
                if (g != null && Religion.deity(g) != null) Religion.addFavour(p, g, e.getValue().getAsDouble());
            }
            return;
        }
        FId g = Religion.favourGod(p);
        if (g != null) Religion.addFavour(p, g, v.getAsDouble());
    }
}
