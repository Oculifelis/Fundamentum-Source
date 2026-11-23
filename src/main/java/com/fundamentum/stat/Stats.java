package com.fundamentum.stat;

import com.fundamentum.condition.Conditions;
import com.fundamentum.config.ConfigValue;
import com.fundamentum.config.Timers;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.mana.Mana;
import com.fundamentum.port.FAttach;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStackData;
import com.fundamentum.port.FStreamCodec;
import com.fundamentum.port.FVanilla;
import com.fundamentum.unlock.Unlocks;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stats: named numbers on players, entities and item stacks, declared in {@code data/<ns>/fundamentum/stats/} or used by
 * id alone (an undeclared id is a stored number with no limits). A declared stat says where its value comes from:
 * <pre>
 * {"type": "stored"}                                       saved on the entity or stack (the default)
 * {"type": "attribute", "id": "minecraft:max_health"}      an attribute's current value
 * {"type": "mana", "pool": "mymod:mana", "value": "max"}   the mana pool (pool optional; value current or max)
 * {"type": "unlock", "id": "mymod:smelting"}               the player's unlock level
 * {"type": "vanilla_stat", "stat": "minecraft:custom", "value": "minecraft:jump"}
 * {"type": "scoreboard", "objective": "kills"}             the entity's score (can be set)
 * </pre>
 * Mods add their own with {@link #registerSource}. Stored and scoreboard stats can be set; every write is clamped to
 * the stat's min and max and posts {@link Events.StatChanged}. Computed player stats post it too: attribute stats are
 * checked again when the player's inventory, equipment or effects change, unlock stats when an unlock is granted, and
 * the stats nothing announces (mana, vanilla_stat, scoreboard and mod sources) on the {@code fundamentum:performance}
 * config's {@code stats_volatile_seconds} timer, only when such a stat is declared. A full check of every player runs on
 * {@code stats_recheck_seconds} as a safety net. Players get their values and the declarations synced, for the stats
 * screen, the HUD layer and tooltips.
 */
public final class Stats {
    private Stats() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");

    /** Where a stat's value comes from. {@code entity} is null for an item stat read off a stack alone. */
    public interface Source {
        double read(StatDef def, LivingEntity entity, ItemStack stack);

        /** Writes the value; false when this source cannot be written. */
        default boolean write(StatDef def, LivingEntity entity, ItemStack stack, double value) {
            return false;
        }
    }

    /** The declarations, as JSON text by id, for the client. */
    public record Defs(Map<String, String> json) {
        public static final FStreamCodec<Defs> CODEC = FStreamCodec.mapOf(FStreamCodec.STRING, FStreamCodec.STRING).map(Defs::new, Defs::json);
    }

    /** The local player's stat values, for the client. */
    public record Sync(Map<String, Double> values) {
        public static final FStreamCodec<Sync> CODEC = FStreamCodec.mapOf(FStreamCodec.STRING, FStreamCodec.DOUBLE).map(Sync::new, Sync::values);
    }

    static final Codec<Map<String, Double>> MAP_CODEC = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE);

    /** Stored values on players and other living entities (kept through death). */
    public static final FAttach<Map<String, Double>> ENTITY = FAttach.<Map<String, Double>>builder(FId.of("fundamentum", "stats"), HashMap::new)
        .codec(MAP_CODEC).copyOnDeath().build();

    /** Stored values on item stacks. */
    public static final FStackData<Map<String, Double>> ITEM = FStackData.of(FId.of("fundamentum", "stats"), MAP_CODEC,
        FStreamCodec.mapOf(FStreamCodec.STRING, FStreamCodec.DOUBLE));

    public static final FId DEFS = FId.of("fundamentum", "stat_defs");
    public static final FId SYNC = FId.of("fundamentum", "stats");

    private static final Map<String, Source> SOURCES = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, Double>> LAST = new ConcurrentHashMap<>();
    private static volatile Map<FId, StatDef> defs = Map.of();
    private static volatile Map<FId, StatDef> clientDefs = Map.of();
    private static volatile Map<FId, Double> clientValues = Map.of();
    private static final java.util.concurrent.atomic.AtomicInteger CLIENT_VERSION = new java.util.concurrent.atomic.AtomicInteger();
    private static MinecraftServer server;
    private static volatile boolean defsChanged;
    private static int ticks;
    private static boolean initialised;
    /** Sources whose value changes with no event to say so; their stats are polled on the slow volatile timer. */
    private static final Set<String> ANNOUNCED = Set.of("stored", "attribute", "unlock");
    private static volatile boolean anyVolatile;
    private static final Set<UUID> DIRTY = ConcurrentHashMap.newKeySet();
    private static ConfigValue<Integer> safetyNet;
    private static ConfigValue<Integer> volatileTimer;

    /** Called once while Fundamentum loads. */
    public static void init() {
        if (initialised) return;
        initialised = true;
        registerSource("stored", new Source() {
            @Override
            public double read(StatDef def, LivingEntity entity, ItemStack stack) {
                Map<String, Double> m = def.scope() == StatDef.Scope.ITEM ? ITEM.get(stack) : entity == null ? null : ENTITY.get(entity);
                Double v = m == null ? null : m.get(def.id().toString());
                return v == null ? def.defaultValue() : v;
            }

            @Override
            public boolean write(StatDef def, LivingEntity entity, ItemStack stack, double value) {
                String key = def.id().toString();
                if (def.scope() == StatDef.Scope.ITEM) {
                    ITEM.update(stack, Map.of(), old -> put(old, key, value));
                } else {
                    if (entity == null) return false;
                    ENTITY.update(entity, old -> put(old, key, value));
                }
                return true;
            }
        });
        registerSource("attribute", (def, e, s) -> {
            FId id = Conditions.id(def.source(), "id");
            return e == null || id == null ? def.defaultValue() : FVanilla.attributeValue(e, id);
        });
        registerSource("mana", (def, e, s) -> {
            if (e == null) return def.defaultValue();
            FId pool = Conditions.id(def.source(), "pool");
            boolean max = def.source().has("value") && "max".equals(def.source().get("value").getAsString());
            if (pool == null) return max ? Mana.max(e) : Mana.get(e);
            return Mana.source(e, pool.vanilla()).map(m -> max ? m.max(e) : m.get(e)).orElse(def.defaultValue());
        });
        registerSource("unlock", (def, e, s) -> {
            FId id = Conditions.id(def.source(), "id");
            return e instanceof ServerPlayer p && id != null ? Unlocks.level(p, id) : def.defaultValue();
        });
        registerSource("vanilla_stat", (def, e, s) -> {
            FId type = Conditions.id(def.source(), "stat");
            FId value = Conditions.id(def.source(), "value");
            return e instanceof ServerPlayer p && type != null && value != null ? FVanilla.statValue(p, type, value) : def.defaultValue();
        });
        registerSource("scoreboard", new Source() {
            @Override
            public double read(StatDef def, LivingEntity entity, ItemStack stack) {
                MinecraftServer srv = serverOf(entity);
                if (srv == null || entity == null || !def.source().has("objective")) return def.defaultValue();
                Integer v = FVanilla.score(srv, entity.getScoreboardName(), def.source().get("objective").getAsString());
                return v == null ? def.defaultValue() : v;
            }

            @Override
            public boolean write(StatDef def, LivingEntity entity, ItemStack stack, double value) {
                MinecraftServer srv = serverOf(entity);
                return srv != null && entity != null && def.source().has("objective")
                    && FVanilla.setScore(srv, entity.getScoreboardName(), def.source().get("objective").getAsString(), (int) Math.round(value));
            }
        });

        FNet.register(DEFS, Defs.class, Defs.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) -> {
            clientDefs = parseAll(msg.json());
            CLIENT_VERSION.incrementAndGet();
        });
        FNet.register(SYNC, Sync.class, Sync.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) -> {
            Map<FId, Double> m = new TreeMap<>();
            msg.values().forEach((k, v) -> {
                FId id = FId.tryOf(k);
                if (id != null) m.put(id, v);
            });
            if (!m.equals(clientValues)) {
                clientValues = Collections.unmodifiableMap(m);
                CLIENT_VERSION.incrementAndGet();
            }
        });
        FLife.jsonData(FId.of("fundamentum", "stats"), "fundamentum/stats", Stats::load);
        FLife.serverAboutToStart(s -> server = s);
        FLife.serverStopped(s -> {
            server = null;
            ticks = 0;
            LAST.clear();
            DIRTY.clear();
        });
        FLife.serverTick(Stats::tick);
        safetyNet = Timers.seconds("stats_recheck_seconds", 30);
        volatileTimer = Timers.seconds("stats_volatile_seconds", 2);
        FLife.inventoryChanged(p -> DIRTY.add(p.getUUID()));
        FLife.effectsChanged(e -> {
            if (e instanceof ServerPlayer p) DIRTY.add(p.getUUID());
        });
        FLife.playerChangedDimension((p, from, to) -> DIRTY.add(p.getUUID()));
        FEvents.listen(Events.UnlockGranted.class, e -> DIRTY.add(e.player().getUUID()));
        FEvents.listen(Events.SlotEquipped.class, e -> {
            if (e.entity() instanceof ServerPlayer p) DIRTY.add(p.getUUID());
        });
        FEvents.listen(Events.SlotUnequipped.class, e -> {
            if (e.entity() instanceof ServerPlayer p) DIRTY.add(p.getUUID());
        });
        FLife.playerLoggedIn(p -> {
            FNet.toPlayer(p, defsMessage());
            LAST.remove(p.getUUID());
            scan(p);
        });
        Conditions.register("stat", (json, ctx) -> {
            FId id = Conditions.id(json, "id");
            if (id == null) return false;
            double v;
            if (def(id).scope() == StatDef.Scope.ITEM) {
                ItemStack stack = !ctx.stack().isEmpty() ? ctx.stack() : ctx.entity() == null ? ItemStack.EMPTY : ctx.entity().getMainHandItem();
                v = get(stack, id);
            } else {
                if (ctx.entity() == null) return false;
                v = get(ctx.entity(), id);
            }
            return Conditions.inRange(v, json);
        });
        StatCommand.init();
    }

    /** Adds a source type (or replaces one) for the {@code source.type} field. */
    public static void registerSource(String type, Source source) {
        SOURCES.put(type, source);
    }

    // ---- definitions

    private static void load(Map<FId, List<JsonElement>> files) {
        Map<FId, StatDef> out = new TreeMap<>();
        files.forEach((id, copies) -> {
            try {
                out.put(id, StatDef.parse(id, copies.get(copies.size() - 1).getAsJsonObject()));
            } catch (RuntimeException e) {
                LOG.error("Stat {} could not be read: {}", id, e.toString());
            }
        });
        defs = Collections.unmodifiableMap(out);
        anyVolatile = out.values().stream().anyMatch(d -> d.scope() != StatDef.Scope.ITEM && !ANNOUNCED.contains(d.sourceType()));
        defsChanged = true;
        for (UUID id : LAST.keySet()) DIRTY.add(id);
        LOG.info("Loaded {} stat definitions", out.size());
    }

    /** Reads declarations sent as JSON text by id (what the client gets). */
    public static Map<FId, StatDef> parseAll(Map<String, String> json) {
        Map<FId, StatDef> out = new TreeMap<>();
        json.forEach((k, v) -> {
            FId id = FId.tryOf(k);
            if (id == null) return;
            try {
                out.put(id, StatDef.parse(id, JsonParser.parseString(v).getAsJsonObject()));
            } catch (RuntimeException e) {
                LOG.warn("Stat {} from the server could not be read: {}", id, e.toString());
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /** The declared stat, or the stand-in for an undeclared id (stored, no limits). Never null. */
    public static StatDef def(FId id) {
        StatDef d = defs.get(id);
        return d != null ? d : StatDef.undeclared(id);
    }

    /** Whether the id is declared in data. */
    public static boolean declared(FId id) {
        return defs.containsKey(id);
    }

    public static Map<FId, StatDef> defs() {
        return defs;
    }

    // ---- reading and writing

    private static Source source(StatDef def) {
        Source s = SOURCES.get(def.sourceType());
        return s != null ? s : SOURCES.get("stored");
    }

    /** The entity's value of the stat (for an item stat, of the stack in its main hand). */
    public static double get(LivingEntity entity, FId id) {
        StatDef d = def(id);
        if (d.scope() == StatDef.Scope.ITEM) return get(entity.getMainHandItem(), id);
        return source(d).read(d, entity, ItemStack.EMPTY);
    }

    /** The stack's value of the stat (the stat's default when the stack has none). */
    public static double get(ItemStack stack, FId id) {
        StatDef d = asItem(def(id));
        return source(d).read(d, null, stack);
    }

    /**
     * Sets the entity's value, clamped to the stat's range, and posts {@link Events.StatChanged} when it changed.
     * False when the stat's source cannot be written. For an item stat, writes the stack in the main hand.
     */
    public static boolean set(LivingEntity entity, FId id, double value) {
        StatDef d = def(id);
        if (d.scope() == StatDef.Scope.ITEM) return set(entity.getMainHandItem(), id, value);
        Source src = source(d);
        double from = src.read(d, entity, ItemStack.EMPTY);
        if (!src.write(d, entity, ItemStack.EMPTY, d.clamp(value))) return false;
        changed(entity, id, from, src.read(d, entity, ItemStack.EMPTY));
        return true;
    }

    public static boolean add(LivingEntity entity, FId id, double amount) {
        return set(entity, id, get(entity, id) + amount);
    }

    /** Sets the stack's value, clamped. Item writes post no event (a stack has no holder to name). */
    public static boolean set(ItemStack stack, FId id, double value) {
        if (stack.isEmpty()) return false;
        StatDef d = asItem(def(id));
        return source(d).write(d, null, stack, d.clamp(value));
    }

    public static boolean add(ItemStack stack, FId id, double amount) {
        return set(stack, id, get(stack, id) + amount);
    }

    /** The value shown the stat's way (format, percent, time or plain number). */
    public static String format(FId id, double value) {
        StatDef d = defs.containsKey(id) ? defs.get(id) : clientDefs.containsKey(id) ? clientDefs.get(id) : def(id);
        return d.format(value);
    }

    private static StatDef asItem(StatDef d) {
        if (d.scope() == StatDef.Scope.ITEM) return d;
        return new StatDef(d.id(), d.name(), d.icon(), d.type(), StatDef.Scope.ITEM, d.defaultValue(), d.min(), d.max(), d.format(),
            d.hud(), d.tooltip(), d.source(), d.json());
    }

    private static Map<String, Double> put(Map<String, Double> old, String key, double value) {
        Map<String, Double> m = new LinkedHashMap<>(old);
        m.put(key, value);
        return m;
    }

    private static void changed(LivingEntity entity, FId id, double from, double to) {
        if (from == to) return;
        Map<String, Double> last = entity instanceof ServerPlayer p ? LAST.get(p.getUUID()) : null;
        if (last != null) last.put(id.toString(), to);
        FEvents.post(new Events.StatChanged(entity, id, from, to));
        if (entity instanceof ServerPlayer p) sync(p);
    }

    // ---- client

    /** Goes up each time the client's stat values or declarations change, so a HUD can keep its text until then. */
    public static int clientVersion() {
        return CLIENT_VERSION.get();
    }

    /** On the client: the declarations the server sent. */
    public static Map<FId, StatDef> clientDefs() {
        return clientDefs;
    }

    /** On the client: the local player's values as the server last sent them. */
    public static Map<FId, Double> clientValues() {
        return clientValues;
    }

    /** Tooltip lines for the stack's stored item stats whose declaration has {@code tooltip} on. */
    public static List<Component> tooltip(ItemStack stack, Map<FId, StatDef> declared) {
        List<Component> out = new ArrayList<>();
        Map<String, Double> stored = ITEM.get(stack);
        if (stored == null || stored.isEmpty()) return out;
        new TreeMap<>(stored).forEach((k, v) -> {
            FId id = FId.tryOf(k);
            StatDef d = id == null ? null : declared.get(id);
            if (d == null || d.scope() != StatDef.Scope.ITEM || !d.tooltip()) return;
            out.add(line(d, v).withStyle(ChatFormatting.BLUE));
        });
        return out;
    }

    /** "Name: value", with the name translated (falling back to the id's path). */
    public static MutableComponent line(StatDef d, double value) {
        return Component.translatableWithFallback(d.name(), pretty(d.id().path())).append(Component.literal(": " + d.format(value)));
    }

    static String pretty(String path) {
        String p = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ');
        return p.isEmpty() ? path : Character.toUpperCase(p.charAt(0)) + p.substring(1);
    }

    // ---- server upkeep

    private static void tick(MinecraftServer s) {
        if (defsChanged) {
            defsChanged = false;
            Defs msg = defsMessage();
            for (ServerPlayer p : List.copyOf(s.getPlayerList().getPlayers())) FNet.toPlayer(p, msg);
        }
        ticks++;
        if (!DIRTY.isEmpty()) {
            List<UUID> work = List.copyOf(DIRTY);
            DIRTY.removeAll(work);
            for (UUID id : work) {
                ServerPlayer p = s.getPlayerList().getPlayer(id);
                if (p != null) scan(p);
            }
        }
        int full = Timers.ticks(safetyNet, 30);
        int vol = anyVolatile ? Timers.ticks(volatileTimer, 2) : 0;
        if (full <= 0 && vol <= 0) return;
        for (ServerPlayer p : List.copyOf(s.getPlayerList().getPlayers())) {
            if (Timers.due(full, s, p.getUUID())) scan(p);
            else if (Timers.due(vol, s, p.getUUID())) scanVolatile(p);
        }
    }

    /** Checks only the player's stats whose source announces nothing (mana, vanilla_stat, scoreboard, mod sources). */
    private static void scanVolatile(ServerPlayer player) {
        Map<String, Double> last = LAST.get(player.getUUID());
        if (last == null) {
            scan(player);
            return;
        }
        boolean any = false;
        for (StatDef d : defs.values()) {
            if (d.scope() == StatDef.Scope.ITEM || ANNOUNCED.contains(d.sourceType())) continue;
            double v;
            try {
                v = source(d).read(d, player, ItemStack.EMPTY);
            } catch (RuntimeException e) {
                continue;
            }
            String key = d.id().toString();
            Double before = last.put(key, v);
            if (Objects.equals(before, v)) continue;
            any = true;
            FEvents.post(new Events.StatChanged(player, d.id(), before == null ? d.defaultValue() : before, v));
        }
        if (any) sync(player);
    }

    /** Marks the player for a full stat check at the end of the tick. */
    public static void markDirty(ServerPlayer player) {
        DIRTY.add(player.getUUID());
    }

    /**
     * Rechecks the player's stats: posts {@link Events.StatChanged} for each one that changed since the last check and
     * syncs them when any did. Runs when something that can change a computed stat happens, and on the slow safety
     * net; call it yourself for others.
     */
    public static void scan(ServerPlayer player) {
        Map<String, Double> now = values(player);
        Map<String, Double> last = LAST.put(player.getUUID(), new ConcurrentHashMap<>(now));
        if (last == null) {
            sync(player, now);
            return;
        }
        boolean any = last.size() != now.size();
        for (Map.Entry<String, Double> e : now.entrySet()) {
            Double before = last.get(e.getKey());
            if (Objects.equals(before, e.getValue())) continue;
            any = true;
            FId id = FId.tryOf(e.getKey());
            if (id != null) FEvents.post(new Events.StatChanged(player, id, before == null ? def(id).defaultValue() : before, e.getValue()));
        }
        if (any) sync(player, now);
    }

    /** Every declared player or entity stat of the entity, and any undeclared ones stored on it. */
    public static Map<String, Double> values(LivingEntity entity) {
        Map<String, Double> out = new TreeMap<>();
        for (StatDef d : defs.values()) {
            if (d.scope() == StatDef.Scope.ITEM) continue;
            try {
                out.put(d.id().toString(), source(d).read(d, entity, ItemStack.EMPTY));
            } catch (RuntimeException e) {
                LOG.debug("Stat {} could not be read: {}", d.id(), e.toString());
            }
        }
        ENTITY.get(entity).forEach(out::putIfAbsent);
        return out;
    }

    /** Sends the player their current values (only to players who are connected). */
    public static void sync(ServerPlayer player) {
        sync(player, values(player));
    }

    private static void sync(ServerPlayer player, Map<String, Double> values) {
        MinecraftServer s = serverOf(player);
        if (s == null || s.getPlayerList().getPlayer(player.getUUID()) != player) return;
        FNet.toPlayer(player, new Sync(new HashMap<>(values)));
    }

    /** The declarations message the server sends at login and after a reload. */
    public static Defs defsMessage() {
        Map<String, String> json = new HashMap<>();
        defs.forEach((id, d) -> json.put(id.toString(), d.json()));
        return new Defs(json);
    }

    static MinecraftServer serverOf(LivingEntity entity) {
        if (entity != null && entity.level() instanceof ServerLevel l) return l.getServer();
        return server;
    }
}
