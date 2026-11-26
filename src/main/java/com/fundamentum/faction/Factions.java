package com.fundamentum.faction;

import com.fundamentum.condition.Conditions;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.mobgoal.FactionHook;
import com.fundamentum.mobgoal.GoalParams;
import com.fundamentum.mobgoal.MobGoals;
import com.fundamentum.mobgoal.goal.FactionTargetGoal;
import com.fundamentum.port.FAttach;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLevelData;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FMobs;
import com.fundamentum.port.FTags;
import com.fundamentum.port.FVanilla;
import com.fundamentum.unlock.UnlockHolder;
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
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/**
 * Factions, reputation, ranks, membership and alignment, all from data:
 * {@code data/<ns>/fundamentum/factions/*.json} (see {@link FactionDef}) and {@code data/<ns>/fundamentum/alignments/*.json}
 * ({@code {"name": "Order", "min": -100, "max": 100, "start": 0}}).
 * <p>
 * Rep lives on the player (kept through death), or on the team for a {@code team_shared} faction. A rank change grants
 * the rank's unlock ({@link FactionDef#rankUnlock}) and gives its rewards once. Member mobs treat the player by the rank's
 * attitude, member villagers price by its {@code price}, and mobs of enemy factions fight each other. Acts listed under
 * {@code actions} (kill_member, kill_enemy, trade, break_block, or any key a mod fires with {@link #act}) change rep.
 */
public final class Factions {
    private Factions() { }

    /** One axis of alignment. */
    public record Axis(FId id, String name, double min, double max, double start) { }

    /** What a player has: rep by faction, joined factions, alignment by axis, and ranks already rewarded. */
    public record State(Map<String, Integer> rep, List<String> member, Map<String, Double> align, List<String> rewarded) {
        static final State EMPTY = new State(Map.of(), List.of(), Map.of(), List.of());
        static final Codec<State> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("rep", Map.of()).forGetter(State::rep),
            Codec.STRING.listOf().optionalFieldOf("member", List.of()).forGetter(State::member),
            Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("align", Map.of()).forGetter(State::align),
            Codec.STRING.listOf().optionalFieldOf("rewarded", List.of()).forGetter(State::rewarded)
        ).apply(i, State::new));

        State withRep(String f, int v) {
            Map<String, Integer> m = new HashMap<>(rep);
            m.put(f, v);
            return new State(m, member, align, rewarded);
        }

        State withMember(String f, boolean in) {
            List<String> l = new ArrayList<>(member);
            l.remove(f);
            if (in) l.add(f);
            return new State(rep, l, align, rewarded);
        }

        State withAlign(String a, double v) {
            Map<String, Double> m = new HashMap<>(align);
            m.put(a, v);
            return new State(rep, member, m, rewarded);
        }

        State withRewarded(String key) {
            List<String> l = new ArrayList<>(rewarded);
            l.add(key);
            return new State(rep, member, align, l);
        }
    }

    public static final FAttach<State> PLAYER = FAttach.builder(FId.of("fundamentum", "factions"), () -> State.EMPTY)
        .codec(State.CODEC).copyOnDeath().build();

    /** Team-shared rep: team key, then faction, then rep. */
    static final FLevelData<Map<String, Map<String, Integer>>> TEAMS = FLevelData.of(FId.of("fundamentum", "team_factions"),
        Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.INT)), HashMap::new);

    private static volatile Map<FId, FactionDef> defs = Map.of();
    private static volatile Map<FId, Axis> axes = Map.of();
    private static final Map<EntityType<?>, List<FactionDef>> BY_TYPE = new ConcurrentHashMap<>();
    private static final Map<String, Double> DRIFT_CARRY = new ConcurrentHashMap<>();
    private static boolean initialised;

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        FLife.jsonData(FId.of("fundamentum", "factions"), "fundamentum/factions", Factions::load);
        FLife.jsonData(FId.of("fundamentum", "alignments"), "fundamentum/alignments", Factions::loadAxes);
        FLife.serverTick(Factions::tick);
        FLife.playerLoggedIn(Factions::refresh);
        FMobs.joined(Factions::onJoin);
        MobGoals.setFactions(new Hook(MobGoals.factions()));
        NeoForge.EVENT_BUS.addListener(Factions::onDeath);
        NeoForge.EVENT_BUS.addListener(Factions::onTarget);
        NeoForge.EVENT_BUS.addListener(Factions::onHurt);
        NeoForge.EVENT_BUS.addListener(Factions::onTrade);
        NeoForge.EVENT_BUS.addListener(Factions::onBreak);
        registerConditions();
        FactionCommand.init();
    }

    // ---- data

    private static void load(Map<FId, List<JsonElement>> map) {
        Map<FId, FactionDef> out = new LinkedHashMap<>();
        map.forEach((id, list) -> {
            JsonElement top = list.get(list.size() - 1);
            if (!top.isJsonObject()) return;
            FactionDef d = FactionDef.parse(id, top.getAsJsonObject());
            if (d.enabled()) out.put(id, d);
        });
        defs = Map.copyOf(out);
        BY_TYPE.clear();
    }

    private static void loadAxes(Map<FId, List<JsonElement>> map) {
        Map<FId, Axis> out = new LinkedHashMap<>();
        map.forEach((id, list) -> {
            JsonElement top = list.get(list.size() - 1);
            if (!top.isJsonObject()) return;
            JsonObject o = top.getAsJsonObject();
            double min = FactionDef.d(o, "min", -100);
            double max = Math.max(min, FactionDef.d(o, "max", 100));
            out.put(id, new Axis(id, FactionDef.s(o, "name", id.toString()), min, max,
                Math.max(min, Math.min(max, FactionDef.d(o, "start", 0)))));
        });
        axes = Map.copyOf(out);
    }

    public static Map<FId, FactionDef> all() {
        return defs;
    }

    public static FactionDef get(FId id) {
        return defs.get(id);
    }

    public static Map<FId, Axis> axes() {
        return axes;
    }

    // ---- who belongs where

    /** The factions an entity belongs to by type, tag or (for villagers) profession. Players belong to none here. */
    public static List<FactionDef> of(Entity e) {
        if (e == null || e instanceof ServerPlayer) return List.of();
        List<FactionDef> byType = BY_TYPE.computeIfAbsent(e.getType(), Factions::forType);
        if (!(e instanceof Villager v)) return byType;
        FId prof = v.getVillagerData().profession().unwrapKey().map(k -> FId.from(k.identifier())).orElse(null);
        if (prof == null) return byType;
        List<FactionDef> out = null;
        for (FactionDef d : defs.values()) {
            if (d.professions().contains(prof) && !byType.contains(d)) {
                if (out == null) out = new ArrayList<>(byType);
                out.add(d);
            }
        }
        return out == null ? byType : out;
    }

    private static List<FactionDef> forType(EntityType<?> type) {
        List<FactionDef> out = new ArrayList<>();
        FId id = FVanilla.idOf(type);
        for (FactionDef d : defs.values()) {
            for (String m : d.members()) {
                boolean hit = m.startsWith("#") ? FTags.is(type, FTags.entity(FId.of(m.substring(1)))) : id.toString().equals(m);
                if (hit) {
                    out.add(d);
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    public static boolean isMember(Entity e, FId faction) {
        for (FactionDef d : of(e)) {
            if (d.id().equals(faction)) return true;
        }
        return false;
    }

    // ---- rep

    private static UnlockHolder team(ServerPlayer p, FactionDef d) {
        return d.teamShared() ? Unlocks.team(p) : null;
    }

    public static int rep(ServerPlayer player, FId faction) {
        FactionDef d = defs.get(faction);
        int start = d == null ? 0 : d.start();
        UnlockHolder t = d == null ? null : team(player, d);
        if (t != null) {
            Map<String, Integer> m = TEAMS.get(server(player)).get(t.key());
            Integer v = m == null ? null : m.get(faction.toString());
            return v == null ? start : v;
        }
        Integer v = PLAYER.get(player).rep().get(faction.toString());
        return v == null ? start : v;
    }

    public static FactionDef.Rank rank(ServerPlayer player, FId faction) {
        FactionDef d = defs.get(faction);
        return d == null ? null : d.rankAt(rep(player, faction));
    }

    /** Sets rep (clamped). Returns false when the faction is unknown or a listener cancelled the change. */
    public static boolean setRep(ServerPlayer player, FId faction, int value) {
        FactionDef d = defs.get(faction);
        if (d == null) return false;
        int from = rep(player, faction);
        int to = Math.max(d.min(), Math.min(d.max(), value));
        if (from == to) return true;
        if (FEvents.post(new Events.RepChanged(player, faction, from, to))) return false;
        if (d.deity() && FEvents.post(new Events.DevotionChanged(player, faction, from, to))) return false;
        UnlockHolder t = team(player, d);
        if (t != null) {
            TEAMS.update(server(player), all -> {
                Map<String, Map<String, Integer>> copy = new HashMap<>(all);
                Map<String, Integer> m = new HashMap<>(copy.getOrDefault(t.key(), Map.of()));
                m.put(faction.toString(), to);
                copy.put(t.key(), m);
                return copy;
            });
        } else {
            PLAYER.update(player, s -> s.withRep(faction.toString(), to));
        }
        FactionDef.Rank a = d.rankAt(from);
        FactionDef.Rank b = d.rankAt(to);
        if (a != b) rankChanged(player, d, a, b);
        return true;
    }

    /** Adds rep and passes the change on to related factions by their {@code spill} (one step, no chains). */
    public static void addRep(ServerPlayer player, FId faction, int delta) {
        FactionDef d = defs.get(faction);
        if (d == null || delta == 0) return;
        setRep(player, faction, rep(player, faction) + delta);
        for (Map.Entry<FId, FactionDef.Relation> e : d.relations().entrySet()) {
            FactionDef.Relation r = e.getValue();
            if (r.spill() == 0 || !defs.containsKey(e.getKey())) continue;
            int pass = (int) Math.round(delta * r.spill() * (r.enemy() ? -1 : 1));
            if (pass != 0) setRep(player, e.getKey(), rep(player, e.getKey()) + pass);
        }
    }

    private static void rankChanged(ServerPlayer player, FactionDef d, FactionDef.Rank from, FactionDef.Rank to) {
        FEvents.post(new Events.RankChanged(player, d.id(), from == null ? "" : from.id(), to == null ? "" : to.id()));
        if (from != null) Unlocks.revoke(player, d.rankUnlock(from.id()));
        if (to == null) return;
        Unlocks.grant(player, d.rankUnlock(to.id()));
        String key = d.id() + "|" + to.id();
        boolean up = from == null || to.min() > from.min();
        if (up && to.rewards() != null && !PLAYER.get(player).rewarded().contains(key)) {
            PLAYER.update(player, s -> s.withRewarded(key));
            Rewards.give(player, to.rewards());
        }
    }

    /** Puts rank unlocks right (on login, and for team-shared rep changed by teammates). */
    public static void refresh(ServerPlayer player) {
        for (FactionDef d : defs.values()) {
            FactionDef.Rank now = rank(player, d.id());
            for (FactionDef.Rank r : d.ranks()) {
                FId u = d.rankUnlock(r.id());
                if (r == now) {
                    if (!Unlocks.has(player, u)) Unlocks.grant(player, u);
                } else if (Unlocks.has(player, u)) {
                    Unlocks.revoke(player, u);
                }
            }
        }
    }

    // ---- acts

    /** Applies an act (an {@code actions} key) for every faction that lists it; returns how many did. */
    public static int act(ServerPlayer player, String action) {
        int n = 0;
        for (FactionDef d : defs.values()) {
            if (act(player, d, action)) n++;
        }
        com.fundamentum.quest.Quests.signal(player, "act", action, 1);
        return n;
    }

    /** Applies one faction's act, if it lists it. No spill. */
    public static boolean act(ServerPlayer player, FactionDef d, String action) {
        FactionDef.Action a = d.actions().get(action);
        if (a == null) return false;
        if (a.rep() != 0) setRep(player, d.id(), rep(player, d.id()) + a.rep());
        a.alignment().forEach((axis, v) -> addAlignment(player, axis, v));
        return true;
    }

    private static void onDeath(LivingDeathEvent e) {
        if (!(e.getSource().getEntity() instanceof ServerPlayer p) || e.getEntity() instanceof ServerPlayer) return;
        List<FactionDef> victim = of(e.getEntity());
        if (victim.isEmpty()) return;
        for (FactionDef d : defs.values()) {
            if (victim.contains(d)) {
                act(p, d, "kill_member");
            } else {
                for (FactionDef v : victim) {
                    FactionDef.Relation r = d.relation(v.id());
                    if (r != null && r.enemy()) {
                        act(p, d, "kill_enemy");
                        break;
                    }
                }
            }
        }
    }

    private static void onTrade(TradeWithVillagerEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        AbstractVillager v = e.getAbstractVillager();
        for (FactionDef d : of(v)) act(p, d, "trade");
    }

    private static void onBreak(BreakBlockEvent e) {
        if (!(e.getPlayer() instanceof ServerPlayer p)) return;
        BlockState state = e.getState();
        FId id = FVanilla.idOf(state.getBlock());
        for (FactionDef d : defs.values()) {
            FactionDef.Action a = d.actions().get("break_block");
            if (a == null) continue;
            boolean hit = a.blocks().isEmpty();
            for (String b : a.blocks()) {
                if (b.startsWith("#") ? state.is(FTags.block(FId.of(b.substring(1)))) : id.toString().equals(b)) {
                    hit = true;
                    break;
                }
            }
            if (hit) act(p, d, "break_block");
        }
    }

    // ---- membership

    public static boolean member(ServerPlayer player, FId faction) {
        return PLAYER.get(player).member().contains(faction.toString());
    }

    public static List<FId> memberships(ServerPlayer player) {
        List<FId> out = new ArrayList<>();
        for (String s : PLAYER.get(player).member()) {
            FId f = FId.tryOf(s);
            if (f != null) out.add(f);
        }
        return out;
    }

    /** Why the player may not join, or null when they may. */
    public static String cannotJoin(ServerPlayer player, FId faction) {
        FactionDef d = defs.get(faction);
        if (d == null) return "unknown faction";
        FactionDef.Membership m = d.membership();
        if (!m.joinable()) return "not joinable";
        if (member(player, faction)) return "already a member";
        if (m.minRank() != null) {
            FactionDef.Rank need = d.rank(m.minRank());
            if (need != null && rep(player, faction) < need.min()) return "rank too low";
        }
        if (m.requires() != null && !Conditions.all(m.requires(), Conditions.Context.of(player))) return "requirements not met";
        if (m.group() != null) {
            for (FId f : memberships(player)) {
                FactionDef o = defs.get(f);
                if (o != null && m.group().equals(o.membership().group())) return "already in " + o.displayName();
            }
        }
        return null;
    }

    /** Joins when allowed; returns null on success or the reason it failed. */
    public static String join(ServerPlayer player, FId faction) {
        String why = cannotJoin(player, faction);
        if (why != null) return why;
        if (FEvents.post(new Events.FactionJoined(player, faction))) return "cancelled";
        PLAYER.update(player, s -> s.withMember(faction.toString(), true));
        Unlocks.grant(player, defs.get(faction).memberUnlock());
        return null;
    }

    public static boolean leave(ServerPlayer player, FId faction) {
        if (!member(player, faction)) return false;
        FactionDef d = defs.get(faction);
        PLAYER.update(player, s -> s.withMember(faction.toString(), false));
        FEvents.post(new Events.FactionLeft(player, faction));
        if (d != null) {
            Unlocks.revoke(player, d.memberUnlock());
            if (d.membership().leaveRep() != 0) setRep(player, faction, rep(player, faction) + d.membership().leaveRep());
        }
        return true;
    }

    // ---- alignment

    public static double alignment(ServerPlayer player, FId axis) {
        Double v = PLAYER.get(player).align().get(axis.toString());
        if (v != null) return v;
        Axis a = axes.get(axis);
        return a == null ? 0 : a.start();
    }

    public static void setAlignment(ServerPlayer player, FId axis, double value) {
        Axis a = axes.get(axis);
        double v = a == null ? value : Math.max(a.min(), Math.min(a.max(), value));
        PLAYER.update(player, s -> s.withAlign(axis.toString(), v));
    }

    public static void addAlignment(ServerPlayer player, FId axis, double delta) {
        if (delta != 0) setAlignment(player, axis, alignment(player, axis) + delta);
    }

    // ---- attitudes

    /** How a member mob treats the player: the most hostile attitude across its factions, else the friendliest. */
    public static String attitude(Entity mob, ServerPlayer player) {
        String out = FactionDef.NEUTRAL;
        for (FactionDef d : of(mob)) {
            FactionDef.Rank r = d.rankAt(rep(player, d.id()));
            if (r == null) continue;
            String a = r.attitude();
            if (FactionDef.HOSTILE.equals(a)) return a;
            if (FactionDef.DEFEND.equals(a) || FactionDef.FRIENDLY.equals(a) && !FactionDef.DEFEND.equals(out)) out = a;
        }
        return out;
    }

    private static boolean kind(Entity a, Entity b, boolean enemy) {
        for (FactionDef x : of(a)) {
            for (FactionDef y : of(b)) {
                if (x == y) {
                    if (!enemy) return true;
                    continue;
                }
                FactionDef.Relation r = x.relation(y.id());
                if (r != null && (enemy ? r.enemy() : r.ally())) return true;
            }
        }
        return false;
    }

    /**
     * Faction data decides for any mob that belongs to a faction. Between two mobs with none (players belong to none
     * here), the hook that was set before keeps deciding, so a plain pack of one mob type still counts as allies.
     */
    private static final class Hook implements FactionHook {
        private final FactionHook prev;

        Hook(FactionHook prev) {
            this.prev = prev;
        }

        private static boolean unaffiliated(Mob self, LivingEntity other) {
            return of(self).isEmpty() && of(other).isEmpty();
        }

        @Override
        public boolean hostile(Mob self, LivingEntity other) {
            if (unaffiliated(self, other)) return prev.hostile(self, other);
            if (other instanceof ServerPlayer p) return !p.isCreative() && !p.isSpectator() && FactionDef.HOSTILE.equals(attitude(self, p));
            return kind(self, other, true);
        }

        @Override
        public boolean allied(Mob self, LivingEntity other) {
            if (unaffiliated(self, other)) return prev.allied(self, other);
            if (other instanceof ServerPlayer p) {
                String a = attitude(self, p);
                return FactionDef.FRIENDLY.equals(a) || FactionDef.DEFEND.equals(a);
            }
            return kind(self, other, false);
        }
    }

    private static void onJoin(Mob mob) {
        if (FMobs.brainDriven(mob)) return;
        for (FactionDef d : of(mob)) {
            boolean fights = !d.relations().isEmpty();
            for (FactionDef.Rank r : d.ranks()) fights |= FactionDef.HOSTILE.equals(r.attitude());
            if (fights) {
                mob.targetSelector.addGoal(2, new FactionTargetGoal(mob, GoalParams.EMPTY));
                return;
            }
        }
    }

    private static void onTarget(LivingChangeTargetEvent e) {
        if (!(e.getNewAboutToBeSetTarget() instanceof ServerPlayer p)) return;
        LivingEntity mob = e.getEntity();
        if (mob.getLastHurtByMob() == p) return; // fights back when struck
        String a = attitude(mob, p);
        if (FactionDef.FRIENDLY.equals(a) || FactionDef.DEFEND.equals(a)) e.setCanceled(true);
    }

    private static void onHurt(LivingDamageEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getSource().getEntity() instanceof LivingEntity attacker) || attacker == p) return;
        for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(16),
            m -> m != attacker && m.getTarget() == null && !of(m).isEmpty())) {
            if (FactionDef.DEFEND.equals(attitude(m, p)) && !kind(m, attacker, false)) m.setTarget(attacker);
        }
    }

    // ---- prices

    /** The product of the price factors of every faction the villager belongs to, for this player. */
    public static double priceFactor(Entity villager, ServerPlayer player) {
        double k = 1;
        for (FactionDef d : of(villager)) {
            FactionDef.Rank r = d.rankAt(rep(player, d.id()));
            if (r != null) k *= r.price();
        }
        return k;
    }

    /** Called after vanilla works out special prices; adds the faction factor on top. */
    public static void applyPrices(Villager villager, ServerPlayer player) {
        double k = priceFactor(villager, player);
        if (k == 1) return;
        for (MerchantOffer o : villager.getOffers()) {
            int base = o.getBaseCostA().getCount();
            int diff = (int) Math.round(base * (k - 1));
            if (diff != 0) o.addToSpecialPriceDiff(diff);
        }
    }

    // ---- drift

    private static void tick(MinecraftServer s) {
        if (s.getTickCount() % 1200 != 0) return;
        for (ServerPlayer p : s.getPlayerList().getPlayers()) drift(p);
    }

    /** One drift step (a minute of game time) for one player. */
    static void drift(ServerPlayer p) {
        for (FactionDef d : defs.values()) {
            if (d.driftPerDay() <= 0) continue;
            int now = rep(p, d.id());
            if (now == d.driftToward()) continue;
            String key = p.getUUID() + "|" + d.id();
            double carry = DRIFT_CARRY.getOrDefault(key, 0.0) + d.driftPerDay() / 20.0;
            int step = (int) carry;
            DRIFT_CARRY.put(key, carry - step);
            if (step == 0) continue;
            int to = now < d.driftToward() ? Math.min(d.driftToward(), now + step) : Math.max(d.driftToward(), now - step);
            setRep(p, d.id(), to);
        }
    }

    // ---- conditions

    private static void registerConditions() {
        Conditions.register("faction_rep", (j, c) -> {
            ServerPlayer p = c.player();
            FId f = Conditions.id(j, "faction");
            return p != null && f != null && Conditions.inRange(rep(p, f), j);
        });
        Conditions.register("faction_rank", (j, c) -> {
            ServerPlayer p = c.player();
            FId f = Conditions.id(j, "faction");
            FactionDef d = f == null ? null : defs.get(f);
            if (p == null || d == null || !j.has("rank")) return false;
            FactionDef.Rank want = d.rank(j.get("rank").getAsString());
            FactionDef.Rank now = d.rankAt(rep(p, f));
            if (want == null || now == null) return false;
            return j.has("at_least") && j.get("at_least").getAsBoolean() ? now.min() >= want.min() : now == want;
        });
        Conditions.register("faction_member", (j, c) -> {
            ServerPlayer p = c.player();
            FId f = Conditions.id(j, "faction");
            return p != null && f != null && member(p, f);
        });
        Conditions.register("alignment", (j, c) -> {
            ServerPlayer p = c.player();
            FId a = Conditions.id(j, "axis");
            return p != null && a != null && Conditions.inRange(alignment(p, a), j);
        });
    }

    private static MinecraftServer server(ServerPlayer p) {
        return ((ServerLevel) p.level()).getServer();
    }
}
