package com.fundamentum.boss;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boss plumbing. An entity becomes a boss by its type (code: {@link #register(EntityType, BossSpec)}; data:
 * {@code data/<ns>/fundamentum/bosses/<name>.json}, whose {@code entity} names the type or types, the file id standing in
 * when it is left out) or one by one ({@link #attach}). Data wins over code for the same type. The server keeps the bar,
 * phases, music, arena and records; clients only see the vanilla bar and the boss music packets.
 *
 * <pre>{
 *   "entity": "minecraft:wither_skeleton",
 *   "name": {"translate": "boss.mymod.bone_lord"}, "color": "yellow", "overlay": "notched_10",
 *   "darken": true, "fog": false, "visible": "arena",
 *   "music": "mymod:music.bone_lord", "music_fade_in": 40, "music_fade_out": 60,
 *   "phases": [{"id": "enraged", "below": 0.5, "color": "red", "music": "mymod:music.bone_lord_2"}],
 *   "cap_damage_at_phase": true,
 *   "arena": {"shape": "radius", "radius": 24, "height": 16, "lock_in": true, "leash": true, "reset_when_empty": 200},
 *   "record": "both",
 *   "drops": {"pool": "mymod:bone_lord", "first_kill_pool": "mymod:bone_lord_first"}
 * }</pre>
 */
public final class Bosses {
    private Bosses() { }

    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    static final String TAG_SPEC = "fundamentum_boss";
    static final String TAG_HOME = "fundamentum_boss_home";

    private static final Map<ResourceLocation, BossSpec> CODE_BY_ID = new HashMap<>();
    private static final Map<EntityType<?>, BossSpec> CODE_BY_TYPE = new HashMap<>();
    private static volatile Map<ResourceLocation, BossSpec> dataById = Map.of();
    private static volatile Map<ResourceLocation, BossSpec> dataByType = Map.of();
    private static final Map<LivingEntity, Boss> ACTIVE = new IdentityHashMap<>();

    public static final DataTable<JsonObject> TABLE = DataTables.register(new ResourceLocation("fundamentum", "bosses"),
        "fundamentum/bosses", json -> {
            BossSpec.fromJson(new ResourceLocation("fundamentum", "check"), json);
            return json;
        }).onLoad(Bosses::loaded);

    public static void init(IEventBus modBus) {
        MinecraftForge.EVENT_BUS.addListener(Bosses::joined);
        MinecraftForge.EVENT_BUS.addListener(Bosses::left);
        MinecraftForge.EVENT_BUS.addListener(Bosses::died);
        MinecraftForge.EVENT_BUS.addListener(Bosses::hurt);
        MinecraftForge.EVENT_BUS.addListener(Bosses::tick);
        MinecraftForge.EVENT_BUS.addListener(Bosses::stopped);
    }

    // ------------------------------------------------------------------------------------------------ api
    /** Every entity of this type is a boss with this spec (data for the same type wins). */
    public static synchronized void register(EntityType<?> type, BossSpec spec) {
        CODE_BY_TYPE.put(type, spec);
        CODE_BY_ID.put(spec.id, spec);
    }

    /** Makes the spec known by id, for {@link #attach} and for entities saved with it. */
    public static synchronized void register(BossSpec spec) {
        CODE_BY_ID.put(spec.id, spec);
    }

    /** The spec by id: data first, then code. */
    public static @Nullable BossSpec spec(ResourceLocation id) {
        BossSpec s = dataById.get(id);
        return s != null ? s : CODE_BY_ID.get(id);
    }

    /** The spec an entity type gets: data first, then code. */
    public static @Nullable BossSpec spec(EntityType<?> type) {
        BossSpec s = dataByType.get(BuiltInRegistries.ENTITY_TYPE.getKey(type));
        return s != null ? s : CODE_BY_TYPE.get(type);
    }

    /** Makes this one entity a boss (saved with it, so it stays one after a reload). Returns the live boss. */
    public static Boss attach(LivingEntity entity, BossSpec spec) {
        if (spec(spec.id) == null) register(spec);
        entity.getPersistentData().putString(TAG_SPEC, spec.id.toString());
        Boss old = ACTIVE.remove(entity);
        if (old != null) old.end();
        return start(entity, spec);
    }

    /** The live boss for this entity, or null. */
    public static @Nullable Boss get(LivingEntity entity) {
        return ACTIVE.get(entity);
    }

    /** Every live boss. */
    public static List<Boss> active() {
        return List.copyOf(ACTIVE.values());
    }

    /** The largest share of {@code amount} this boss may take now (its phase-line cap), or {@code amount} for non-bosses. */
    public static float capDamage(LivingEntity entity, float amount) {
        Boss b = ACTIVE.get(entity);
        return b == null ? amount : b.capDamage(amount);
    }

    // ------------------------------------------------------------------------------------------------ data
    private static void loaded(DataTable<JsonObject> table) {
        Map<ResourceLocation, BossSpec> byId = new HashMap<>();
        Map<ResourceLocation, BossSpec> byType = new HashMap<>();
        table.entries().forEach((id, json) -> {
            try {
                BossSpec spec = BossSpec.fromJson(id, json);
                byId.put(id, spec);
                List<ResourceLocation> types = new ArrayList<>();
                JsonElement e = json.get("entity");
                if (e == null) types.add(id);
                else if (e.isJsonArray()) e.getAsJsonArray().forEach(x -> types.add(new ResourceLocation(x.getAsString())));
                else types.add(new ResourceLocation(e.getAsString()));
                types.forEach(t -> byType.put(t, spec));
            } catch (RuntimeException ex) {
                LOG.error("boss {}: invalid, left out", id, ex);
            }
        });
        dataById = Map.copyOf(byId);
        dataByType = Map.copyOf(byType);
    }

    // ------------------------------------------------------------------------------------------------ events
    private static Boss start(LivingEntity entity, BossSpec spec) {
        String home = entity.getPersistentData().getString(TAG_HOME);
        Vec3 pos = entity.position();
        if (!home.isEmpty()) {
            String[] p = home.split(",");
            pos = new Vec3(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
        } else {
            entity.getPersistentData().putString(TAG_HOME, pos.x + "," + pos.y + "," + pos.z);
            FEvents.post(new Events.BossSpawned(entity, FId.from(spec.id())));
        }
        Boss boss = new Boss(entity, spec, pos);
        ACTIVE.put(entity, boss);
        return boss;
    }

    private static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof LivingEntity living) || ACTIVE.containsKey(living)) return;
        String tagged = living.getPersistentData().getString(TAG_SPEC);
        BossSpec spec = tagged.isEmpty() ? null : spec(new ResourceLocation(tagged));
        if (spec == null) spec = spec(living.getType());
        if (spec != null && living.isAlive()) start(living, spec);
    }

    private static void left(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof LivingEntity living) {
            Boss b = ACTIVE.remove(living);
            if (b != null) b.end();
        }
    }

    private static void hurt(LivingHurtEvent event) {
        Boss b = ACTIVE.get(event.getEntity());
        if (b == null) return;
        if (event.getSource().getEntity() instanceof ServerPlayer p) b.addFighter(p);
        event.setAmount(b.capDamage(event.getAmount()));
    }

    private static void died(LivingDeathEvent event) {
        Boss b = ACTIVE.remove(event.getEntity());
        if (b == null || !(event.getEntity().level() instanceof ServerLevel level)) return;
        if (event.getSource().getEntity() instanceof ServerPlayer p) b.addFighter(p);
        defeat(level.getServer(), b, event.getSource().getEntity() instanceof ServerPlayer killer ? killer : null);
    }

    /** Records the defeat, posts {@link BossEvents.Defeated} and ends the bar (called on death; tests may call it). */
    public static void defeat(MinecraftServer server, Boss b) {
        defeat(server, b, null);
    }

    /** {@link #defeat(MinecraftServer, Boss)} naming who landed the killing blow (may be null), for {@code fundamentum:boss_killed}. */
    public static void defeat(MinecraftServer server, Boss b, @Nullable ServerPlayer killer) {
        BossRecords records = BossRecords.get(server);
        ResourceLocation id = b.spec().id();
        Set<UUID> fighters = b.fighters();
        Set<UUID> first = new HashSet<>();
        for (UUID u : fighters) if (!records.defeated(id, u)) first.add(u);
        boolean firstInWorld = !records.defeated(id);
        BossSpec.Record r = b.spec().record();
        if (r == BossSpec.Record.WORLD || r == BossSpec.Record.BOTH) records.addWorld(id);
        if (r == BossSpec.Record.PLAYER || r == BossSpec.Record.BOTH) fighters.forEach(u -> records.addPlayer(id, u));
        b.end();
        ACTIVE.remove(b.entity());
        MinecraftForge.EVENT_BUS.post(new BossEvents.Defeated(b, fighters, first, firstInWorld));
        FEvents.post(new Events.BossKilled(b.entity(), FId.from(id), killer));
    }

    private static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) return;
        for (Boss b : List.copyOf(ACTIVE.values())) {
            if (b.entity().isRemoved() || !(b.entity().level() instanceof ServerLevel level)) continue;
            try {
                b.tick(level.players());
            } catch (RuntimeException e) {
                LOG.error("boss {} tick failed", b.spec().id(), e);
            }
        }
    }

    private static void stopped(ServerStoppedEvent event) {
        ACTIVE.values().forEach(Boss::end);
        ACTIVE.clear();
    }
}
