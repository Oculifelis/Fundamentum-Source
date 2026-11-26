package com.fundamentum.quest;

import com.fundamentum.condition.Conditions;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.faction.FactionDef;
import com.fundamentum.faction.Factions;
import com.fundamentum.faction.Rewards;
import com.fundamentum.port.FAttach;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLevelData;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FTags;
import com.fundamentum.port.FVanilla;
import com.fundamentum.unlock.Unlocks;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Quests from data ({@code data/<ns>/fundamentum/quests/*.json}, see {@link QuestDef}), offered by mobs and quest-giver
 * NPCs, tracked per player and kept through death.
 * <p>
 * Objectives are a registry ({@link #register}). Built in: {@code kill} (target, faction), {@code break} and
 * {@code place} (block), {@code collect} (item, consume), {@code craft} (item), {@code trade} (target), {@code visit}
 * (biome, structure, dimension or condition), {@code survive_night}, {@code act} (a faction act name), {@code unlock}
 * (id) and {@code condition}. A mod adds a counted type by registering it and calling {@link #signal}.
 */
public final class Quests {
    private Quests() { }

    /** One objective type. */
    public interface Objective {
        /** Progress read from the world once a second, or -1 when {@link #signal} counts it. */
        default int poll(ServerPlayer player, JsonObject objective) {
            return -1;
        }

        /** Whether a signal's context counts for this objective. */
        default boolean matches(ServerPlayer player, JsonObject objective, Object context) {
            return true;
        }

        /** Runs once when the quest completes (collect takes its items here). */
        default void finish(ServerPlayer player, JsonObject objective) { }
    }

    /** One active quest: progress per objective and the game time it was taken. */
    public record Active(List<Integer> progress, long started) {
        static final Codec<Active> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.listOf().fieldOf("progress").forGetter(Active::progress),
            Codec.LONG.fieldOf("started").forGetter(Active::started)
        ).apply(i, Active::new));
    }

    /** A player's quests: active ones, and the game time each finished quest was last completed. */
    public record State(Map<String, Active> active, Map<String, Long> done) {
        static final State EMPTY = new State(Map.of(), Map.of());
        static final Codec<State> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Active.CODEC).optionalFieldOf("active", Map.of()).forGetter(State::active),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("done", Map.of()).forGetter(State::done)
        ).apply(i, State::new));

        State with(String quest, Active a) {
            Map<String, Active> m = new LinkedHashMap<>(active);
            if (a == null) m.remove(quest); else m.put(quest, a);
            return new State(m, done);
        }

        State finished(String quest, long time) {
            Map<String, Active> m = new LinkedHashMap<>(active);
            m.remove(quest);
            Map<String, Long> d = new HashMap<>(done);
            d.put(quest, time);
            return new State(m, d);
        }
    }

    /** The most quests a player can hold at once. */
    public static final int MAX_ACTIVE = 10;
    /** How long an offer from a giver stays open to accept, in ticks. */
    static final int OFFER_TICKS = 1200;

    public static final FAttach<State> PLAYER = FAttach.builder(FId.of("fundamentum", "quests"), () -> State.EMPTY)
        .codec(State.CODEC).copyOnDeath().build();

    private static final Map<String, Objective> TYPES = new ConcurrentHashMap<>();
    private static volatile Map<FId, QuestDef> defs = Map.of();
    /** Open offers: player, then quest, then the game time the offer ends. */
    private static final Map<UUID, Map<FId, Long>> OFFERS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> WAS_NIGHT = new ConcurrentHashMap<>();
    /** Which quest-giver NPC type an entity is, or null; set by the NPC entity. */
    static volatile Function<Entity, FId> npcType = e -> null;

    private static boolean initialised;

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        FLife.jsonData(FId.of("fundamentum", "quests"), "fundamentum/quests", Quests::load);
        FLife.serverTick(Quests::tick);
        NeoForge.EVENT_BUS.addListener(Quests::onDeath);
        NeoForge.EVENT_BUS.addListener(Quests::onBreak);
        NeoForge.EVENT_BUS.addListener(Quests::onPlace);
        NeoForge.EVENT_BUS.addListener(Quests::onCraft);
        NeoForge.EVENT_BUS.addListener(Quests::onTrade);
        NeoForge.EVENT_BUS.addListener(Quests::onEntityUse);
        registerBuiltins();
        registerConditions();
        QuestCommand.init();
        QuestSync.init();
    }

    // ---- data

    private static void load(Map<FId, List<JsonElement>> map) {
        Map<FId, QuestDef> out = new LinkedHashMap<>();
        map.forEach((id, list) -> {
            JsonElement top = list.get(list.size() - 1);
            if (!top.isJsonObject()) return;
            QuestDef d = QuestDef.parse(id, top.getAsJsonObject());
            if (d.enabled() && !d.objectives().isEmpty()) out.put(id, d);
        });
        defs = Map.copyOf(out);
    }

    public static Map<FId, QuestDef> all() {
        return defs;
    }

    public static QuestDef get(FId id) {
        return defs.get(id);
    }

    /** Adds an objective type. */
    public static void register(String type, Objective objective) {
        TYPES.put(type, objective);
    }

    public static Objective objective(String type) {
        return TYPES.get(type);
    }

    // ---- state

    public static State state(ServerPlayer player) {
        return PLAYER.get(player);
    }

    public static boolean active(ServerPlayer player, FId quest) {
        return state(player).active().containsKey(quest.toString());
    }

    /** Whether the quest was ever completed. */
    public static boolean done(ServerPlayer player, FId quest) {
        return state(player).done().containsKey(quest.toString());
    }

    /** Progress on each objective of an active quest, or null when it is not active. */
    public static List<Integer> progress(ServerPlayer player, FId quest) {
        Active a = state(player).active().get(quest.toString());
        return a == null ? null : a.progress();
    }

    /** Whether every objective of an active quest is met. */
    public static boolean ready(ServerPlayer player, FId quest) {
        QuestDef d = defs.get(quest);
        List<Integer> p = progress(player, quest);
        if (d == null || p == null) return false;
        for (int i = 0; i < d.objectives().size(); i++) {
            if (i >= p.size() || p.get(i) < QuestDef.count(d.objectives().get(i))) return false;
        }
        return true;
    }

    private static long now(ServerPlayer p) {
        return p.level().getGameTime();
    }

    /** Why a player cannot take a quest now, or null when they can. */
    public static String cannotAccept(ServerPlayer player, FId quest) {
        QuestDef d = defs.get(quest);
        if (d == null) return "unknown quest";
        State s = state(player);
        if (s.active().containsKey(quest.toString())) return "already taken";
        Long last = s.done().get(quest.toString());
        if (last != null) {
            if (!d.repeatable()) return "already done";
            if (now(player) - last < d.cooldown()) return "not yet";
        }
        if (s.active().size() >= MAX_ACTIVE) return "too many quests";
        if (d.faction() != null && d.minRank() != null) {
            FactionDef f = Factions.get(d.faction());
            FactionDef.Rank want = f == null ? null : f.rank(d.minRank());
            FactionDef.Rank have = Factions.rank(player, d.faction());
            if (want != null && (have == null || have.min() < want.min())) return "rank too low";
        }
        if (d.requires() != null && !Conditions.all(d.requires(), Conditions.Context.of(player))) return "requirements not met";
        return null;
    }

    /** Takes a quest; returns null on success or why it failed. */
    public static String accept(ServerPlayer player, FId quest) {
        String why = cannotAccept(player, quest);
        if (why != null) return why;
        if (FEvents.post(new Events.QuestAccepted(player, quest))) return "refused";
        QuestDef d = defs.get(quest);
        List<Integer> progress = new ArrayList<>();
        for (int i = 0; i < d.objectives().size(); i++) progress.add(0);
        PLAYER.update(player, s -> s.with(quest.toString(), new Active(List.copyOf(progress), now(player))));
        Map<FId, Long> offers = OFFERS.get(player.getUUID());
        if (offers != null) offers.remove(quest);
        update(player, d);
        return null;
    }

    /** Re-reads polled objectives and completes the quest when all are met and it needs no hand-in. */
    static void update(ServerPlayer player, QuestDef d) {
        Active a = state(player).active().get(d.id().toString());
        if (a == null) return;
        List<Integer> p = new ArrayList<>(a.progress());
        while (p.size() < d.objectives().size()) p.add(0);
        boolean changed = false;
        for (int i = 0; i < d.objectives().size(); i++) {
            JsonObject o = d.objectives().get(i);
            Objective t = TYPES.get(o.get("type").getAsString());
            if (t == null) continue;
            int v = t.poll(player, o);
            if (v >= 0) {
                v = Math.min(v, QuestDef.count(o));
                if (v != p.get(i)) {
                    p.set(i, v);
                    changed = true;
                }
            }
        }
        if (changed) PLAYER.update(player, s -> s.with(d.id().toString(), new Active(List.copyOf(p), a.started())));
        if (!d.turnIn() && ready(player, d.id())) complete(player, d.id());
    }

    /**
     * Counts progress for every active objective of {@code type} whose fields match {@code context}. Returns how many
     * objectives moved.
     */
    public static int signal(ServerPlayer player, String type, Object context, int amount) {
        Objective t = TYPES.get(type);
        State s = state(player);
        if (t == null || s.active().isEmpty() || amount <= 0) return 0;
        int moved = 0;
        for (Map.Entry<String, Active> e : s.active().entrySet()) {
            QuestDef d = defs.get(FId.tryOf(e.getKey()));
            if (d == null) continue;
            List<Integer> p = new ArrayList<>(e.getValue().progress());
            while (p.size() < d.objectives().size()) p.add(0);
            boolean changed = false;
            for (int i = 0; i < d.objectives().size(); i++) {
                JsonObject o = d.objectives().get(i);
                if (!type.equals(o.get("type").getAsString())) continue;
                int need = QuestDef.count(o);
                if (p.get(i) >= need || !t.matches(player, o, context)) continue;
                p.set(i, Math.min(need, p.get(i) + amount));
                changed = true;
                moved++;
            }
            if (changed) {
                Active na = new Active(List.copyOf(p), e.getValue().started());
                PLAYER.update(player, st -> st.active().containsKey(e.getKey()) ? st.with(e.getKey(), na) : st);
                if (!d.turnIn() && ready(player, d.id())) complete(player, d.id());
            }
        }
        return moved;
    }

    /** Finishes a quest whose objectives are met: takes collected items, gives rewards. Returns false when not ready. */
    public static boolean complete(ServerPlayer player, FId quest) {
        QuestDef d = defs.get(quest);
        if (d == null || !ready(player, quest)) return false;
        for (JsonObject o : d.objectives()) {
            Objective t = TYPES.get(o.get("type").getAsString());
            if (t != null) t.finish(player, o);
        }
        PLAYER.update(player, s -> s.finished(quest.toString(), now(player)));
        Rewards.give(player, d.rewards());
        if (d.faction() != null) {
            FactionDef f = Factions.get(d.faction());
            if (f != null) Factions.act(player, f, "quest_done");
        }
        FEvents.post(new Events.QuestCompleted(player, quest));
        player.sendSystemMessage(QuestText.completed(d));
        return true;
    }

    /** Fails or abandons an active quest, giving its {@code fail} penalty. */
    public static boolean fail(ServerPlayer player, FId quest, boolean abandoned) {
        QuestDef d = defs.get(quest);
        if (!active(player, quest)) return false;
        PLAYER.update(player, s -> s.with(quest.toString(), null));
        if (d != null) {
            Rewards.give(player, d.fail());
            if (d.faction() != null) {
                FactionDef f = Factions.get(d.faction());
                if (f != null) Factions.act(player, f, "quest_failed");
            }
            player.sendSystemMessage(QuestText.failed(d, abandoned));
        }
        FEvents.post(new Events.QuestFailed(player, quest, abandoned));
        return true;
    }

    /** Clears a quest from a player's history and active list. */
    public static void reset(ServerPlayer player, FId quest) {
        PLAYER.update(player, s -> {
            Map<String, Long> d = new HashMap<>(s.done());
            d.remove(quest.toString());
            return new State(s.with(quest.toString(), null).active(), d);
        });
    }

    // ---- givers

    /** Whether an entity offers a quest: by type or tag in {@code givers}, or as a listed NPC type. */
    public static boolean offers(Entity giver, QuestDef d) {
        FId npc = npcType.apply(giver);
        if (npc != null && d.npcs().contains(npc)) return true;
        if (d.givers().isEmpty()) return false;
        FId id = FVanilla.idOf(giver.getType());
        for (String g : d.givers()) {
            if (g.startsWith("#") ? FTags.is(giver.getType(), FTags.entity(FId.of(g.substring(1)))) : id.toString().equals(g)) return true;
        }
        return false;
    }

    /** The quests an entity offers that the player could take now. */
    public static List<QuestDef> offersFor(ServerPlayer player, Entity giver) {
        List<QuestDef> out = new ArrayList<>();
        for (QuestDef d : defs.values()) {
            if (offers(giver, d) && cannotAccept(player, d.id()) == null) out.add(d);
        }
        return out;
    }

    /**
     * What happens when a player uses a giver: quests it offers that are ready to hand in are completed, then the
     * rest of its offers open in the giver's quest screen (or list in chat). Returns true when the giver had anything for
     * the player.
     */
    public static boolean talk(ServerPlayer player, Entity giver) {
        boolean any = false;
        for (String q : List.copyOf(state(player).active().keySet())) {
            QuestDef d = defs.get(FId.tryOf(q));
            if (d != null && d.turnIn() && offers(giver, d) && complete(player, d.id())) any = true;
        }
        List<QuestDef> list = offersFor(player, giver);
        if (!list.isEmpty()) {
            offer(player, list, screenFor(giver, list));
            any = true;
        }
        return any;
    }

    /** The quest screen a giver's offers open in: the NPC type's {@code screen}, else the first quest's, else the journal. */
    static FId screenFor(Entity giver, List<QuestDef> list) {
        FId npc = npcType.apply(giver);
        NpcDef n = npc == null ? null : QuestNpcs.get(npc);
        if (n != null && n.screen() != null) return n.screen();
        for (QuestDef d : list) if (d.screen() != null) return d.screen();
        return QuestSync.JOURNAL;
    }

    /** Opens offers for the player in the quest screen, or lists them in chat with accept buttons. */
    static void offer(ServerPlayer player, List<QuestDef> list, FId screen) {
        Map<FId, Long> m = OFFERS.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>());
        long until = now(player) + OFFER_TICKS;
        for (QuestDef d : list) m.put(d.id(), until);
        if (!QuestSync.open(player, screen, list.get(0).id())) player.sendSystemMessage(QuestText.offers(list));
    }

    /** Whether the player has an open offer for this quest. */
    public static boolean offered(ServerPlayer player, FId quest) {
        Map<FId, Long> m = OFFERS.get(player.getUUID());
        Long until = m == null ? null : m.get(quest);
        return until != null && until >= now(player);
    }

    private static void onEntityUse(PlayerInteractEvent.EntityInteract e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p)) return;
        Entity target = e.getTarget();
        if (npcType.apply(target) != null) return; // the NPC handles its own use
        if (!p.isShiftKeyDown()) return;
        if (talk(p, target)) {
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    // ---- ticking

    private static void tick(MinecraftServer server) {
        long tick = server.getTickCount();
        if (tick % 20 != 0) return;
        for (ServerLevel level : server.getAllLevels()) {
            long day = Math.floorMod(com.fundamentum.moon.Moons.dayTime(level), 24000L);
            boolean night = day >= 13000 && day < 23000;
            Boolean was = WAS_NIGHT.put(level.dimension().location().toString(), night);
            if (was != null && was && !night) {
                for (ServerPlayer p : level.players()) {
                    if (p.isAlive()) signal(p, "survive_night", level, 1);
                }
            }
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            tickPlayer(p);
            QuestSync.tick(p);
        }
        if (tick % 1200 == 0) {
            long t = server.overworld().getGameTime();
            OFFERS.values().forEach(m -> m.values().removeIf(until -> until < t));
            OFFERS.values().removeIf(Map::isEmpty);
        }
    }

    /** The once-a-second player check: visit objectives, time limits and polled objectives. */
    static void tickPlayer(ServerPlayer p) {
        State s = state(p);
        if (s.active().isEmpty()) return;
        signal(p, "visit", p, 1);
        long t = now(p);
        for (Map.Entry<String, Active> e : List.copyOf(s.active().entrySet())) {
            QuestDef d = defs.get(FId.tryOf(e.getKey()));
            if (d == null || !active(p, d.id())) continue;
            if (d.timeLimit() > 0 && t - e.getValue().started() > d.timeLimit()) {
                fail(p, d.id(), false);
                continue;
            }
            update(p, d);
        }
    }

    // ---- signals from the game

    private static void onDeath(LivingDeathEvent e) {
        if (e.getSource().getEntity() instanceof ServerPlayer p && !(e.getEntity() instanceof ServerPlayer)) signal(p, "kill", e.getEntity(), 1);
    }

    private static void onBreak(BlockEvent.BreakEvent e) {
        if (e.getPlayer() instanceof ServerPlayer p) signal(p, "break", e.getState(), 1);
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) signal(p, "place", e.getPlacedBlock(), 1);
    }

    private static void onCraft(PlayerEvent.ItemCraftedEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && !e.getCrafting().isEmpty()) signal(p, "craft", e.getCrafting(), e.getCrafting().getCount());
    }

    private static void onTrade(TradeWithVillagerEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) signal(p, "trade", e.getAbstractVillager(), 1);
    }

    // ---- built-in objectives

    /** Whether an entity matches a "target" list (ids or #tags; empty matches all) and an optional "faction". */
    static boolean entityMatches(JsonObject o, Object ctx) {
        if (!(ctx instanceof Entity ent)) return false;
        List<String> targets = QuestDef.strings(o.get("target"));
        if (!targets.isEmpty()) {
            FId id = FVanilla.idOf(ent.getType());
            boolean hit = false;
            for (String t : targets) {
                if (t.startsWith("#") ? FTags.is(ent.getType(), FTags.entity(FId.of(t.substring(1)))) : id.toString().equals(t)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) return false;
        }
        if (o.has("faction")) {
            FId f = FId.tryOf(o.get("faction").getAsString());
            return f != null && Factions.isMember(ent, f);
        }
        return true;
    }

    static boolean blockMatches(JsonObject o, Object ctx) {
        if (!(ctx instanceof BlockState state)) return false;
        List<String> blocks = QuestDef.strings(o.get("block"));
        if (blocks.isEmpty()) return true;
        FId id = FVanilla.idOf(state.getBlock());
        for (String b : blocks) {
            if (b.startsWith("#") ? state.is(FTags.block(FId.of(b.substring(1)))) : id.toString().equals(b)) return true;
        }
        return false;
    }

    static boolean itemMatches(JsonObject o, ItemStack stack) {
        if (stack.isEmpty()) return false;
        List<String> items = QuestDef.strings(o.get("item"));
        if (items.isEmpty()) return true;
        FId id = FVanilla.idOf(stack.getItem());
        for (String s : items) {
            if (s.startsWith("#") ? stack.is(FTags.item(FId.of(s.substring(1)))) : id.toString().equals(s)) return true;
        }
        return false;
    }

    static int countItems(ServerPlayer p, JsonObject o) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (itemMatches(o, s)) n += s.getCount();
        }
        return n;
    }

    /** Whether an entity stands where a visit objective (or the matching condition) points. */
    static boolean at(LivingEntity e, JsonObject o) {
        ServerLevel level = (ServerLevel) e.level();
        BlockPos pos = e.blockPosition();
        if (o.has("dimension") && !level.dimension().location().toString().equals(o.get("dimension").getAsString())) return false;
        if (o.has("biome")) {
            String b = o.get("biome").getAsString();
            var biome = level.getBiome(pos);
            boolean hit = b.startsWith("#") ? biome.is(FTags.biome(FId.of(b.substring(1))))
                : biome.unwrapKey().map(k -> k.location().toString().equals(b)).orElse(false);
            if (!hit) return false;
        }
        if (o.has("structure")) {
            String s = o.get("structure").getAsString();
            boolean hit = s.startsWith("#")
                ? level.structureManager().getStructureWithPieceAt(pos, FTags.of(Registries.STRUCTURE, FId.of(s.substring(1)))).isValid()
                : level.structureManager().getStructureWithPieceAt(pos,
                    h -> h.unwrapKey().map(k -> k.location().toString().equals(s)).orElse(false)).isValid();
            if (!hit) return false;
        }
        return !o.has("condition") || Conditions.test(o.get("condition"), Conditions.Context.of(e));
    }

    private static void registerBuiltins() {
        register("kill", new Objective() {
            @Override
            public boolean matches(ServerPlayer p, JsonObject o, Object ctx) {
                return entityMatches(o, ctx);
            }
        });
        register("trade", new Objective() {
            @Override
            public boolean matches(ServerPlayer p, JsonObject o, Object ctx) {
                return entityMatches(o, ctx);
            }
        });
        Objective block = new Objective() {
            @Override
            public boolean matches(ServerPlayer p, JsonObject o, Object ctx) {
                return blockMatches(o, ctx);
            }
        };
        register("break", block);
        register("place", block);
        register("craft", new Objective() {
            @Override
            public boolean matches(ServerPlayer p, JsonObject o, Object ctx) {
                return ctx instanceof ItemStack s && itemMatches(o, s);
            }
        });
        register("collect", new Objective() {
            @Override
            public int poll(ServerPlayer p, JsonObject o) {
                return countItems(p, o);
            }

            @Override
            public void finish(ServerPlayer p, JsonObject o) {
                if (o.has("consume") && !o.get("consume").getAsBoolean()) return;
                int left = QuestDef.count(o);
                for (int i = 0; i < p.getInventory().getContainerSize() && left > 0; i++) {
                    ItemStack s = p.getInventory().getItem(i);
                    if (!itemMatches(o, s)) continue;
                    int take = Math.min(left, s.getCount());
                    s.shrink(take);
                    left -= take;
                }
            }
        });
        register("visit", new Objective() {
            @Override
            public boolean matches(ServerPlayer p, JsonObject o, Object ctx) {
                return at(p, o);
            }
        });
        register("survive_night", new Objective() { });
        register("act", new Objective() {
            @Override
            public boolean matches(ServerPlayer p, JsonObject o, Object ctx) {
                return o.has("act") && o.get("act").getAsString().equals(ctx);
            }
        });
        register("unlock", new Objective() {
            @Override
            public int poll(ServerPlayer p, JsonObject o) {
                FId id = o.has("id") ? FId.tryOf(o.get("id").getAsString()) : null;
                return id != null && Unlocks.has(p, id) ? QuestDef.count(o) : 0;
            }
        });
        register("condition", new Objective() {
            @Override
            public int poll(ServerPlayer p, JsonObject o) {
                return Conditions.test(o.get("condition"), Conditions.Context.of(p)) ? QuestDef.count(o) : 0;
            }
        });
    }

    private static void registerConditions() {
        Conditions.register("quest_done", (j, c) -> {
            ServerPlayer p = c.player();
            FId q = Conditions.id(j, "quest");
            return p != null && q != null && done(p, q);
        });
        Conditions.register("quest_active", (j, c) -> {
            ServerPlayer p = c.player();
            FId q = Conditions.id(j, "quest");
            return p != null && q != null && active(p, q);
        });
        Conditions.register("location", (j, c) -> c.entity() != null && c.entity().level() instanceof ServerLevel && at(c.entity(), j));
    }
}
