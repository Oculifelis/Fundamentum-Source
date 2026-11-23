package com.fundamentum.unlock;

import com.fundamentum.condition.Conditions;
import com.fundamentum.config.ConfigValue;
import com.fundamentum.config.Timers;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FAttach;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLevelData;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStreamCodec;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unlocks: "this player (or team) has unlocked X", with a level and the time it happened. Any id works; declaring one in
 * {@code data/<ns>/fundamentum/unlocks/} adds a name, icon, parents, team sharing and grant sources ({@link UnlockDef}).
 *
 * <p>The calls that take a player look at the player and their team together ({@link #has(ServerPlayer, FId)} is true
 * when either holds it); the calls that take an {@link UnlockHolder} look at that holder alone. A grant checks parents,
 * posts {@link UnlockEvent.Pre} and {@link Events.UnlockGranted} (either can cancel), stores it, posts
 * {@link UnlockEvent.Granted}, syncs the players concerned and fires the {@code fundamentum:unlocked} advancement
 * trigger. Grant sources are checked for online players once a second.
 *
 * <p>Player unlocks are saved on the player (kept through death); team unlocks with the world.
 */
public final class Unlocks {
    private Unlocks() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");

    /** A held unlock: its level (1 or more) and when it was granted (milliseconds since 1970). */
    public record Entry(int level, long time) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("level").forGetter(Entry::level),
            Codec.LONG.fieldOf("time").forGetter(Entry::time)
        ).apply(i, Entry::new));
    }

    /** Which team a player is on, for team unlocks. The scoreboard team is built in; other team mods add theirs. */
    public interface TeamProvider {
        /** Short id, the first part of the team key ({@code vanilla} for scoreboard teams). */
        String id();

        /** The player's team name under this provider, or null when they have none. */
        String teamOf(ServerPlayer player);
    }

    /** The client's view of its own unlocks (player and team together), sent by the server. */
    public record Sync(Map<String, Integer> levels) {
        public static final FStreamCodec<Sync> CODEC = FStreamCodec.mapOf(FStreamCodec.STRING, FStreamCodec.INT).map(Sync::new, Sync::levels);
    }

    static final Codec<Map<String, Entry>> MAP_CODEC = Codec.unboundedMap(Codec.STRING, Entry.CODEC);

    public static final FAttach<Map<String, Entry>> PLAYER = FAttach.<Map<String, Entry>>builder(FId.of("fundamentum", "unlocks"), HashMap::new)
        .codec(MAP_CODEC).copyOnDeath().build();

    static final FLevelData<Map<String, Map<String, Entry>>> TEAMS = FLevelData.of(FId.of("fundamentum", "team_unlocks"),
        Codec.unboundedMap(Codec.STRING, MAP_CODEC), HashMap::new);

    public static final FId SYNC = FId.of("fundamentum", "unlocks");

    private static final List<TeamProvider> TEAM_PROVIDERS = new CopyOnWriteArrayList<>();
    private static volatile Map<FId, UnlockDef> defs = Map.of();
    private static volatile Map<FId, Integer> client = Map.of();
    private static MinecraftServer server;
    private static int ticks;
    private static boolean initialised;

    // What can change each definition's grant sources, so an event only rechecks the definitions it can affect.
    static final String ITEM = "item";
    static final String ADVANCEMENT = "advancement";
    static final String STAT = "stat";
    static final String UNLOCKED = "unlocked";
    static final String OTHER = "other";
    private static final Set<String> EVERYTHING = Set.of(ITEM, ADVANCEMENT, STAT, UNLOCKED, OTHER);
    private static volatile Map<FId, Set<String>> kinds = Map.of();
    private static final Map<UUID, Set<String>> DIRTY = new HashMap<>();
    private static ConfigValue<Integer> safetyNet;

    /** Called once while Fundamentum loads. */
    public static void init() {
        if (initialised) return;
        initialised = true;
        FEvents.register(FId.of("fundamentum", "unlock/pre"), UnlockEvent.Pre.class, true);
        FEvents.register(FId.of("fundamentum", "unlock/granted"), UnlockEvent.Granted.class, false);
        FEvents.register(FId.of("fundamentum", "unlock/revoked"), UnlockEvent.Revoked.class, false);
        registerTeams(new TeamProvider() {
            @Override
            public String id() {
                return "vanilla";
            }

            @Override
            public String teamOf(ServerPlayer player) {
                PlayerTeam t = serverOf(player).getScoreboard().getPlayersTeam(player.getScoreboardName());
                return t == null ? null : t.getName();
            }
        });
        FNet.register(SYNC, Sync.class, Sync.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) -> {
            Map<FId, Integer> m = new TreeMap<>();
            msg.levels().forEach((k, v) -> {
                FId id = FId.tryOf(k);
                if (id != null) m.put(id, v);
            });
            client = Collections.unmodifiableMap(m);
        });
        UnlockTrigger.init();
        FLife.jsonData(FId.of("fundamentum", "unlocks"), "fundamentum/unlocks", Unlocks::load);
        FLife.serverAboutToStart(s -> server = s);
        FLife.serverStopped(s -> {
            server = null;
            ticks = 0;
        });
        FLife.serverTick(Unlocks::tick);
        FLife.playerLoggedIn(Unlocks::loggedIn);
        safetyNet = Timers.seconds("unlocks_recheck_seconds", 60);
        FLife.inventoryChanged(p -> mark(p, ITEM));
        FLife.advancementEarned((p, id) -> mark(p, ADVANCEMENT));
        FLife.playerChangedDimension((p, from, to) -> mark(p, OTHER));
        FEvents.listen(Events.StatChanged.class, e -> {
            if (e.entity() instanceof ServerPlayer p) mark(p, STAT);
        });
        FLife.serverStopped(s -> {
            synchronized (DIRTY) {
                DIRTY.clear();
            }
        });
        Conditions.register("unlocked", (json, ctx) -> {
            ServerPlayer p = ctx.player();
            FId id = Conditions.id(json, "id");
            if (p == null || id == null) return false;
            int level = level(p, id);
            return level >= (json.has("min") ? json.get("min").getAsInt() : 1) && (!json.has("max") || level <= json.get("max").getAsInt());
        });
        UnlockCommand.init();
    }

    /** Adds a team source (checked before the ones added earlier, so a team mod wins over scoreboard teams). */
    public static void registerTeams(TeamProvider provider) {
        TEAM_PROVIDERS.add(0, provider);
    }

    // ---- definitions

    private static void load(Map<FId, List<JsonElement>> files) {
        Map<FId, UnlockDef> out = new TreeMap<>();
        files.forEach((id, copies) -> {
            JsonElement top = copies.get(copies.size() - 1);
            try {
                out.put(id, UnlockDef.parse(id, top.getAsJsonObject()));
            } catch (RuntimeException e) {
                LOG.error("Unlock {} could not be read: {}", id, e.toString());
            }
        });
        Map<FId, Set<String>> k = new HashMap<>();
        out.forEach((id, d) -> {
            Set<String> found = new HashSet<>();
            kindsOf(d.grant(), found);
            if (!d.parents().isEmpty()) found.add(UNLOCKED);
            k.put(id, Set.copyOf(found));
        });
        kinds = Map.copyOf(k);
        defs = Collections.unmodifiableMap(out);
        LOG.info("Loaded {} unlock definitions", out.size());
        MinecraftServer running = server;
        if (running != null) {
            for (ServerPlayer p : running.getPlayerList().getPlayers()) mark(p, OTHER);
        }
    }

    /** The declared unlock, or null for an undeclared id (which still works, with no parents or sharing). */
    public static UnlockDef def(FId id) {
        return defs.get(id);
    }

    public static Map<FId, UnlockDef> defs() {
        return defs;
    }

    // ---- holders

    /** The player's team as a holder, or null when they have none. */
    public static UnlockHolder team(ServerPlayer player) {
        for (TeamProvider p : TEAM_PROVIDERS) {
            String t = p.teamOf(player);
            if (t != null) return UnlockHolder.team(p.id() + "/" + t);
        }
        return null;
    }

    /** Where a grant of this unlock to the player goes: their team for a team-shared unlock when they have one. */
    public static UnlockHolder holderFor(ServerPlayer player, FId id) {
        UnlockDef d = def(id);
        if (d != null && d.teamShared()) {
            UnlockHolder t = team(player);
            if (t != null) return t;
        }
        return UnlockHolder.of(player);
    }

    // ---- reading

    /** The holder's own unlocks. */
    public static Map<FId, Entry> all(UnlockHolder holder) {
        Map<String, Entry> raw = raw(holder);
        Map<FId, Entry> out = new TreeMap<>();
        raw.forEach((k, v) -> {
            FId id = FId.tryOf(k);
            if (id != null) out.put(id, v);
        });
        return out;
    }

    /** The player's unlocks and their team's together (the higher level and the earlier time where both hold one). */
    public static Map<FId, Entry> all(ServerPlayer player) {
        Map<FId, Entry> out = all(UnlockHolder.of(player));
        UnlockHolder t = team(player);
        if (t != null) {
            all(t).forEach((id, e) -> out.merge(id, e, (a, b) -> new Entry(Math.max(a.level(), b.level()), Math.min(a.time(), b.time()))));
        }
        return out;
    }

    public static int level(UnlockHolder holder, FId id) {
        Entry e = raw(holder).get(id.toString());
        return e == null ? 0 : e.level();
    }

    public static int level(ServerPlayer player, FId id) {
        int own = level(UnlockHolder.of(player), id);
        UnlockHolder t = team(player);
        return t == null ? own : Math.max(own, level(t, id));
    }

    public static boolean has(UnlockHolder holder, FId id) {
        return level(holder, id) > 0;
    }

    public static boolean has(ServerPlayer player, FId id) {
        return level(player, id) > 0;
    }

    /** When the holder got the unlock (milliseconds since 1970), or 0. */
    public static long time(UnlockHolder holder, FId id) {
        Entry e = raw(holder).get(id.toString());
        return e == null ? 0 : e.time();
    }

    /** On the client: the local player's unlocks (with their team's) as the server last sent them. */
    public static Map<FId, Integer> client() {
        return client;
    }

    public static boolean clientHas(FId id) {
        return client.getOrDefault(id, 0) > 0;
    }

    // ---- granting

    /** Grants level 1 to the player (or their team for a team-shared unlock), parents checked. */
    public static boolean grant(ServerPlayer player, FId id) {
        return grant(player, id, 1);
    }

    public static boolean grant(ServerPlayer player, FId id, int level) {
        return grant(holderFor(player, id), id, level, true, player);
    }

    /** Grants to the holder, parents checked. */
    public static boolean grant(UnlockHolder holder, FId id) {
        return grant(holder, id, 1, true, null);
    }

    /**
     * Grants the unlock at this level (or raises it to this level). False when the holder already has that level or
     * more, a parent is missing (when checked), or a listener cancelled. {@code cause} is the player whose action led to
     * it, for a team grant (may be null).
     */
    public static boolean grant(UnlockHolder holder, FId id, int level, boolean checkParents, ServerPlayer cause) {
        MinecraftServer s = serverOf(holder, cause);
        if (s == null) return false;
        UnlockDef d = def(id);
        int lvl = Math.max(1, level);
        if (d != null && d.maxLevel() > 0) lvl = Math.min(lvl, d.maxLevel());
        if (level(holder, id) >= lvl) return false;
        if (checkParents && !parentsMet(holder, cause, id)) return false;
        ServerPlayer player = holder.player() != null ? holder.player() : cause;
        if (FEvents.post(new UnlockEvent.Pre(player, holder.toString(), id, lvl))) return false;
        if (player != null && FEvents.post(new Events.UnlockGranted(player, id))) return false;
        Entry entry = new Entry(lvl, System.currentTimeMillis());
        write(holder, s, m -> m.put(id.toString(), entry));
        FEvents.post(new UnlockEvent.Granted(player, holder.toString(), id, lvl));
        for (ServerPlayer p : affected(holder, s, cause)) {
            sync(p);
            UnlockTrigger.trigger(p, id, level(p, id));
            mark(p, UNLOCKED);
        }
        return true;
    }

    /** True when the holder (or, for a team, the causing player) has every parent of the unlock. */
    public static boolean parentsMet(UnlockHolder holder, ServerPlayer cause, FId id) {
        UnlockDef d = def(id);
        if (d == null) return true;
        for (FId parent : d.parents()) {
            boolean ok = holder.player() != null ? has(holder.player(), parent) : has(holder, parent) || cause != null && has(cause, parent);
            if (!ok) return false;
        }
        return true;
    }

    /** Takes the unlock from the player (or their team for a team-shared unlock). */
    public static boolean revoke(ServerPlayer player, FId id) {
        boolean own = revoke(UnlockHolder.of(player), id, player);
        UnlockHolder t = team(player);
        return t != null && revoke(t, id, player) || own;
    }

    public static boolean revoke(UnlockHolder holder, FId id) {
        return revoke(holder, id, null);
    }

    public static boolean revoke(UnlockHolder holder, FId id, ServerPlayer cause) {
        MinecraftServer s = serverOf(holder, cause);
        if (s == null) return false;
        int had = level(holder, id);
        if (had == 0) return false;
        write(holder, s, m -> m.remove(id.toString()));
        ServerPlayer player = holder.player() != null ? holder.player() : cause;
        FEvents.post(new UnlockEvent.Revoked(player, holder.toString(), id, had));
        for (ServerPlayer p : affected(holder, s, cause)) sync(p);
        return true;
    }

    // ---- grant sources

    /**
     * Checks every declared unlock's grant sources for the player and grants those that now hold (to their team when
     * team-shared). Online players are checked in full on login. After that only the definitions a change can affect
     * are checked (inventory, advancement, stat, unlock or dimension change), with a slow full check as a safety net
     * (the {@code fundamentum:performance} config's {@code unlocks_recheck_seconds}). Call it yourself for others.
     * Returns how many were granted.
     */
    public static int checkSources(ServerPlayer player) {
        int granted = 0;
        for (int pass = 0; pass < 4; pass++) {
            int before = granted;
            for (UnlockDef d : defs.values()) {
                if (d.grant().isEmpty() || has(player, d.id())) continue;
                if (Conditions.any(d.grant(), Conditions.Context.of(player)) && grant(holderFor(player, d.id()), d.id(), 1, true, player)) granted++;
            }
            if (granted == before) break;
        }
        return granted;
    }

    /** Which events can make a grant list hold: the condition types it uses, walking into all, any and not. */
    private static void kindsOf(JsonElement json, Set<String> out) {
        if (json == null || json.isJsonNull()) return;
        if (json.isJsonArray()) {
            for (JsonElement e : json.getAsJsonArray()) kindsOf(e, out);
            return;
        }
        if (!json.isJsonObject()) {
            out.add(OTHER);
            return;
        }
        JsonObject o = json.getAsJsonObject();
        String type = o.has("type") ? o.get("type").getAsString() : "";
        switch (type) {
            case "all", "any", "not" -> kindsOf(o.get("of"), out);
            case "item" -> out.add(ITEM);
            case "advancement" -> out.add(ADVANCEMENT);
            case "stat" -> out.add(STAT);
            case "unlocked" -> out.add(UNLOCKED);
            default -> out.add(OTHER);
        }
    }

    /**
     * Notes that something of this kind changed for the player. The definitions it can affect are checked at the end
     * of the tick, once, however many changes came in.
     */
    static void mark(ServerPlayer player, String kind) {
        synchronized (DIRTY) {
            DIRTY.computeIfAbsent(player.getUUID(), u -> new HashSet<>()).add(kind);
        }
    }

    /** Like {@link #checkSources(ServerPlayer)} but only for the definitions a change of these kinds can affect. */
    static int checkSources(ServerPlayer player, Set<String> changed) {
        Map<FId, Set<String>> k = kinds;
        Set<String> look = new HashSet<>(changed);
        int granted = 0;
        for (int pass = 0; pass < 4; pass++) {
            int before = granted;
            for (UnlockDef d : defs.values()) {
                if (d.grant().isEmpty()) continue;
                boolean relevant = false;
                for (String kind : k.getOrDefault(d.id(), EVERYTHING)) {
                    if (look.contains(kind)) {
                        relevant = true;
                        break;
                    }
                }
                if (!relevant || has(player, d.id())) continue;
                if (Conditions.any(d.grant(), Conditions.Context.of(player)) && grant(holderFor(player, d.id()), d.id(), 1, true, player)) granted++;
            }
            if (granted == before) break;
            look.add(UNLOCKED);
        }
        return granted;
    }

    private static void tick(MinecraftServer s) {
        ticks++;
        Map<UUID, Set<String>> work = null;
        synchronized (DIRTY) {
            if (!DIRTY.isEmpty()) {
                work = new HashMap<>(DIRTY);
                DIRTY.clear();
            }
        }
        if (work != null) {
            for (Map.Entry<UUID, Set<String>> e : work.entrySet()) {
                ServerPlayer p = s.getPlayerList().getPlayer(e.getKey());
                if (p != null) checkSources(p, e.getValue());
            }
        }
        // the safety net: a full check of each player now and then, spread over the interval
        int period = Timers.ticks(safetyNet, 60);
        if (period <= 0) return;
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (Timers.due(period, s, p.getUUID())) checkSources(p);
        }
    }

    private static void loggedIn(ServerPlayer player) {
        sync(player);
        all(player).forEach((id, e) -> UnlockTrigger.trigger(player, id, e.level()));
        checkSources(player);
    }

    // ---- storage

    private static Map<String, Entry> raw(UnlockHolder holder) {
        if (holder.isTeam()) {
            MinecraftServer s = server;
            if (s == null) return Map.of();
            return TEAMS.get(s).getOrDefault(holder.key(), Map.of());
        }
        return holder.player() == null ? Map.of() : PLAYER.get(holder.player());
    }

    private static void write(UnlockHolder holder, MinecraftServer s, java.util.function.Consumer<Map<String, Entry>> change) {
        if (holder.isTeam()) {
            TEAMS.update(s, all -> {
                Map<String, Map<String, Entry>> copy = new HashMap<>(all);
                Map<String, Entry> m = new LinkedHashMap<>(copy.getOrDefault(holder.key(), Map.of()));
                change.accept(m);
                if (m.isEmpty()) copy.remove(holder.key());
                else copy.put(holder.key(), m);
                return copy;
            });
        } else {
            PLAYER.update(holder.player(), old -> {
                Map<String, Entry> m = new LinkedHashMap<>(old);
                change.accept(m);
                return m;
            });
        }
    }

    private static MinecraftServer serverOf(UnlockHolder holder, ServerPlayer cause) {
        if (holder.player() != null) return serverOf(holder.player());
        if (cause != null) return serverOf(cause);
        return server;
    }

    static MinecraftServer serverOf(ServerPlayer player) {
        return ((ServerLevel) player.level()).getServer();
    }

    /** The players whose view changes: the holder, or the team's online members and the causing player. */
    private static Collection<ServerPlayer> affected(UnlockHolder holder, MinecraftServer s, ServerPlayer cause) {
        List<ServerPlayer> out = new ArrayList<>();
        if (holder.player() != null) {
            out.add(holder.player());
            return out;
        }
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (holder.equals(team(p))) out.add(p);
        }
        if (cause != null && !out.contains(cause) && holder.equals(team(cause))) out.add(cause);
        return out;
    }

    /** Sends the player their current unlocks (only to players who are connected). */
    public static void sync(ServerPlayer player) {
        MinecraftServer s = serverOf(player);
        if (s.getPlayerList().getPlayer(player.getUUID()) != player) return;
        Map<String, Integer> levels = new HashMap<>();
        all(player).forEach((id, e) -> levels.put(id.toString(), e.level()));
        FNet.toPlayer(player, new Sync(levels));
    }
}
