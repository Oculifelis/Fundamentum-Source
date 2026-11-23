package com.fundamentum.loot;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.boss.Boss;
import com.fundamentum.boss.BossEvents;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.Nullable;

/**
 * Boss drops on top of the boss's own loot table: each fighter gets their own roll (dropped only they can pick up, or
 * straight into their inventory), and fighters killing it for the first time get a bonus roll. Set per boss in its data
 * file ({@code "drops": {...}}) or from code ({@link #register}).
 *
 * <pre>"drops": {"pool": "mymod:bone_lord", "table": "mymod:bosses/bone_lord_extra",
 *           "first_kill_pool": "mymod:bone_lord_first", "first_kill_table": "...",
 *           "per_player": true, "to_inventory": false}</pre>
 */
public final class BossDrops {
    private BossDrops() { }

    /** Drop settings for one boss. Any of the pools and tables may be null. */
    public record Spec(@Nullable ResourceLocation pool, @Nullable ResourceLocation table, @Nullable ResourceLocation firstKillPool,
            @Nullable ResourceLocation firstKillTable, boolean perPlayer, boolean toInventory) {
        static Spec fromJson(JsonObject o) {
            return new Spec(id(o, "pool"), id(o, "table"), id(o, "first_kill_pool"), id(o, "first_kill_table"),
                !o.has("per_player") || o.get("per_player").getAsBoolean(), o.has("to_inventory") && o.get("to_inventory").getAsBoolean());
        }

        private static @Nullable ResourceLocation id(JsonObject o, String k) {
            return o.has(k) ? new ResourceLocation(o.get(k).getAsString()) : null;
        }
    }

    private static final Map<ResourceLocation, Spec> CODE = new ConcurrentHashMap<>();

    static void init() {
        MinecraftForge.EVENT_BUS.addListener(BossDrops::defeated);
    }

    /** Sets the drops for a boss spec id (its data file's {@code drops} wins). */
    public static void register(ResourceLocation bossSpec, Spec spec) {
        CODE.put(bossSpec, spec);
    }

    /** The drops for a boss, or null. */
    public static @Nullable Spec spec(Boss boss) {
        JsonObject json = boss.spec().drops();
        if (json != null) return Spec.fromJson(json);
        return CODE.get(boss.spec().id());
    }

    private static void defeated(BossEvents.Defeated event) {
        Spec spec = spec(event.boss());
        if (spec == null || !(event.entity().level() instanceof ServerLevel level)) return;
        List<ServerPlayer> players = new ArrayList<>();
        for (UUID u : event.fighters()) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(u);
            if (p != null) players.add(p);
        }
        dropFor(event.boss(), spec, players, event.firstKill());
    }

    /**
     * Rolls and hands out the drops: per player (each their own roll, plus the first-kill roll for those in
     * {@code firstKill}), or once for everyone. Returns what each player got (the shared roll under a null-free key of
     * the boss's own UUID when not per player).
     */
    public static Map<UUID, List<ItemStack>> dropFor(Boss boss, Spec spec, Collection<ServerPlayer> players, Set<UUID> firstKill) {
        Map<UUID, List<ItemStack>> out = new LinkedHashMap<>();
        if (!(boss.entity().level() instanceof ServerLevel level)) return out;
        Vec3 at = boss.entity().position();
        if (spec.perPlayer) {
            for (ServerPlayer p : players) {
                List<ItemStack> got = new ArrayList<>();
                roll(level, at, p, spec.pool, spec.table, got);
                if (firstKill.contains(p.getUUID())) roll(level, at, p, spec.firstKillPool, spec.firstKillTable, got);
                for (ItemStack s : got) give(level, at, p, s.copy(), spec.toInventory);
                out.put(p.getUUID(), got);
                FEvents.post(new Events.BossLootDropped(p, FId.from(boss.spec().id()), got.size()));
            }
        } else {
            List<ItemStack> got = new ArrayList<>();
            ServerPlayer any = players.isEmpty() ? null : players.iterator().next();
            roll(level, at, any, spec.pool, spec.table, got);
            if (!firstKill.isEmpty()) roll(level, at, any, spec.firstKillPool, spec.firstKillTable, got);
            for (ItemStack s : got) give(level, at, null, s.copy(), false);
            out.put(boss.entity().getUUID(), got);
            FEvents.post(new Events.BossLootDropped(null, FId.from(boss.spec().id()), got.size()));
        }
        return out;
    }

    private static void roll(ServerLevel level, Vec3 at, @Nullable ServerPlayer player, @Nullable ResourceLocation pool,
            @Nullable ResourceLocation table, List<ItemStack> out) {
        if (pool != null) out.addAll(LootPools.roll(pool, level.getRandom()));
        if (table != null && player != null) {
            LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, at)
                .withParameter(LootContextParams.THIS_ENTITY, player).create(LootContextParamSets.GIFT);
            level.getServer().getLootData().getLootTable(table)
                .getRandomItems(params, out::add);
        }
    }

    private static void give(ServerLevel level, Vec3 at, @Nullable ServerPlayer player, ItemStack stack, boolean toInventory) {
        if (player != null && toInventory && player.getInventory().add(stack) && stack.isEmpty()) return;
        if (stack.isEmpty()) return;
        ItemEntity e = new ItemEntity(level, at.x, at.y + 0.5, at.z, stack);
        if (player != null) e.setTarget(player.getUUID());
        e.setDefaultPickUpDelay();
        level.addFreshEntity(e);
    }
}
