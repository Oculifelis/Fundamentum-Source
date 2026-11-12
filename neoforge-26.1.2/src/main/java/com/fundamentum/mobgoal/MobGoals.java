package com.fundamentum.mobgoal;

import com.fundamentum.mobgoal.goal.CallAlliesGoal;
import com.fundamentum.mobgoal.goal.ChargeGoal;
import com.fundamentum.mobgoal.goal.FactionTargetGoal;
import com.fundamentum.mobgoal.goal.FleeLowHealthGoal;
import com.fundamentum.mobgoal.goal.FollowLeaderGoal;
import com.fundamentum.mobgoal.goal.GuardGoal;
import com.fundamentum.mobgoal.goal.KiteGoal;
import com.fundamentum.mobgoal.goal.LeapGoal;
import com.fundamentum.mobgoal.goal.RangedAmmoGoal;
import com.fundamentum.mobgoal.goal.StrafeGoal;
import com.fundamentum.port.FCommands;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FMobs;
import com.fundamentum.port.FTags;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data-driven mob goals. Files in {@code data/<ns>/fundamentum/mob_goals/*.json} add goals to an entity type or an
 * entity type tag; each entry names a goal type, a priority and params:
 *
 * <pre>{"entity": "minecraft:zombie", "goal": "fundamentum:kite", "priority": 2, "params": {"min": 5}}</pre>
 *
 * A file can hold one entry or {@code "goals": [...]}; an entry may give {@code "phases": [...]} to run only during
 * those boss phases. Goals go onto mobs as they join a level, and are swapped for the new set on /reload. Mobs run by
 * a brain (villagers, piglins, axolotls and the like) are skipped, since their behaviour does not use goals. Other mods
 * add goal types with {@link #register}, a faction rule with {@link #setFactions} and shot code with
 * {@link #addShooter}.
 */
public final class MobGoals {
    private MobGoals() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");

    /** One loaded entry. {@code entity} or {@code tag} is set. */
    public record Entry(FId file, FId entity, FId tag, FId goal, int priority, GoalParams params, Set<String> phases) {
        public boolean matches(EntityType<?> type) {
            if (entity != null) return entity.equals(FVanilla.idOf(type));
            return FTags.is(type, FTags.entity(tag));
        }
    }

    /** A goal this system added to a mob. */
    public record Added(Entry entry, Goal goal, boolean target) { }

    private static final Map<FId, MobGoalType> TYPES = new LinkedHashMap<>();
    private static final List<MobShooter> SHOOTERS = new CopyOnWriteArrayList<>();
    private static final Map<Mob, List<Added>> APPLIED = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile List<Entry> entries = List.of();
    private static volatile FactionHook factions = FactionHook.DEFAULT;
    private static MinecraftServer server;
    private static boolean initialised;

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        registerBuiltIns();
        FMobs.joined(MobGoals::apply);
        FLife.serverStarted(s -> server = s);
        FLife.serverStopped(s -> {
            server = null;
            APPLIED.clear();
        });
        FLife.jsonData(FId.of("fundamentum", "mob_goals"), "fundamentum/mob_goals", MobGoals::load);
        FCommands.listen(d -> d.register(Commands.literal("fundamentum").requires(FCommands.op())
            .then(Commands.literal("mobgoals").executes(MobGoals::summary)
                .then(Commands.argument("mob", EntityArgument.entity()).executes(MobGoals::show)))));
    }

    private static void registerBuiltIns() {
        register(FId.of("fundamentum", "kite"), false, (m, p) -> m instanceof PathfinderMob pm ? new KiteGoal(pm, p) : null);
        register(FId.of("fundamentum", "strafe"), false, StrafeGoal::new);
        register(FId.of("fundamentum", "leap"), false, LeapGoal::new);
        register(FId.of("fundamentum", "charge"), false, ChargeGoal::new);
        register(FId.of("fundamentum", "flee_low_health"), false,
            (m, p) -> m instanceof PathfinderMob pm ? new FleeLowHealthGoal(pm, p) : null);
        register(FId.of("fundamentum", "call_allies"), false, CallAlliesGoal::new);
        register(FId.of("fundamentum", "guard"), false, (m, p) -> m instanceof PathfinderMob pm ? new GuardGoal(pm, p) : null);
        register(FId.of("fundamentum", "follow_leader"), false,
            (m, p) -> m instanceof PathfinderMob pm ? new FollowLeaderGoal(pm, p) : null);
        register(FId.of("fundamentum", "ranged_ammo"), false, RangedAmmoGoal::new);
        register(FId.of("fundamentum", "faction_target"), true, FactionTargetGoal::new);
    }

    // ---- extension points ----

    /** Adds a goal type that data can name. {@code target} goals go into the target selector. */
    public static synchronized void register(FId id, boolean target, MobGoalType.Factory factory) {
        TYPES.put(id, new MobGoalType(target, factory));
    }

    public static synchronized MobGoalType type(FId id) {
        return TYPES.get(id);
    }

    public static synchronized Set<FId> types() {
        return Set.copyOf(TYPES.keySet());
    }

    /** Sets who counts as friend or foe for the faction-aware goals; null puts the default back. */
    public static void setFactions(FactionHook hook) {
        factions = hook == null ? FactionHook.DEFAULT : hook;
    }

    public static FactionHook factions() {
        return factions;
    }

    /** Adds shot code for {@code fundamentum:ranged_ammo}; shooters are asked newest first. */
    public static void addShooter(MobShooter shooter) {
        SHOOTERS.add(0, shooter);
    }

    /** Fires one shot through the shooters, then the mob's own ranged attack. False when nothing fired. */
    public static boolean fire(Mob mob, LivingEntity target, ItemStack ammo, float power) {
        for (MobShooter s : SHOOTERS) {
            if (s.fire(mob, target, ammo, power)) return true;
        }
        if (mob instanceof RangedAttackMob r) {
            r.performRangedAttack(target, power);
            return true;
        }
        return false;
    }

    // ---- data ----

    public static List<Entry> entries() {
        return entries;
    }

    private static void load(Map<FId, List<JsonElement>> files) {
        List<Entry> out = new ArrayList<>();
        new TreeMap<>(files).forEach((id, copies) -> {
            try {
                JsonObject o = copies.get(copies.size() - 1).getAsJsonObject();
                List<Entry> file = new ArrayList<>();
                if (o.has("goals")) {
                    JsonArray a = o.getAsJsonArray("goals");
                    for (JsonElement e : a) file.add(parse(id, e.getAsJsonObject(), o));
                } else {
                    file.add(parse(id, o, o));
                }
                out.addAll(file);
            } catch (RuntimeException e) {
                LOG.error("Mob goal file {} could not be read: {}", id, e.toString());
            }
        });
        entries = List.copyOf(out);
        LOG.info("Loaded {} mob goal entries", out.size());
        MinecraftServer s = server;
        if (s != null) reapplyAll(s);
    }

    private static Entry parse(FId file, JsonObject o, JsonObject outer) {
        String who = o.has("entity") ? o.get("entity").getAsString() : outer.has("entity") ? outer.get("entity").getAsString() : null;
        if (who == null) throw new IllegalArgumentException("missing entity");
        FId entity = null;
        FId tag = null;
        if (who.startsWith("#")) tag = FId.of(who.substring(1));
        else entity = FId.of(who);
        FId goal = FId.of(o.get("goal").getAsString());
        if (type(goal) == null) throw new IllegalArgumentException("unknown goal " + goal);
        int priority = o.has("priority") ? o.get("priority").getAsInt() : 3;
        GoalParams params = new GoalParams(o.has("params") ? o.getAsJsonObject("params") : new JsonObject());
        Set<String> phases = new HashSet<>();
        if (o.has("phases")) for (JsonElement e : o.getAsJsonArray("phases")) phases.add(e.getAsString());
        return new Entry(file, entity, tag, goal, priority, params, Set.copyOf(phases));
    }

    // ---- applying ----

    /** True for mobs whose behaviour runs on a brain rather than goals; those are left alone. */
    public static boolean brainDriven(Mob mob) {
        return FMobs.brainDriven(mob);
    }

    /** The goals this system has added to a mob (empty when none). */
    public static List<Added> added(Mob mob) {
        List<Added> l = APPLIED.get(mob);
        return l == null ? List.of() : List.copyOf(l);
    }

    /** Takes off this system's goals from a mob and adds the current set. */
    public static void apply(Mob mob) {
        if (mob.level().isClientSide()) return;
        remove(mob);
        if (brainDriven(mob)) return;
        List<Added> list = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.matches(mob.getType())) continue;
            MobGoalType t = type(e.goal());
            if (t == null) continue;
            Goal g;
            try {
                g = t.factory().create(mob, e.params());
            } catch (RuntimeException ex) {
                LOG.error("Mob goal {} from {} failed on {}: {}", e.goal(), e.file(), FVanilla.idOf(mob.getType()), ex.toString());
                continue;
            }
            if (g == null) continue;
            if (!e.phases().isEmpty()) g = new PhaseGate(mob, g, e.phases());
            if (t.target()) mob.targetSelector.addGoal(e.priority(), g);
            else mob.goalSelector.addGoal(e.priority(), g);
            list.add(new Added(e, g, t.target()));
        }
        if (!list.isEmpty()) APPLIED.put(mob, list);
    }

    /** Takes off every goal this system added to a mob. */
    public static void remove(Mob mob) {
        List<Added> old = APPLIED.remove(mob);
        if (old == null) return;
        for (Added a : old) {
            if (a.target()) mob.targetSelector.removeGoal(a.goal());
            else mob.goalSelector.removeGoal(a.goal());
        }
    }

    private static void reapplyAll(MinecraftServer s) {
        int n = 0;
        for (ServerLevel level : s.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof Mob m && m.isAlive()) {
                    apply(m);
                    n++;
                }
            }
        }
        LOG.info("Mob goals reapplied to {} loaded mobs", n);
    }

    // ---- command ----

    private static int summary(CommandContext<CommandSourceStack> c) {
        int mobs = APPLIED.size();
        String text = entries.size() + " mob goal entries, " + types().size() + " goal types, " + mobs + " mobs with added goals";
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return entries.size();
    }

    private static int show(CommandContext<CommandSourceStack> c) {
        Entity e;
        try {
            e = EntityArgument.getEntity(c, "mob");
        } catch (Exception ex) {
            c.getSource().sendFailure(Component.literal(ex.getMessage()));
            return 0;
        }
        if (!(e instanceof Mob m)) {
            c.getSource().sendFailure(Component.literal("Not a mob"));
            return 0;
        }
        if (brainDriven(m)) {
            c.getSource().sendSuccess(() -> Component.literal(FVanilla.idOf(m.getType()) + " runs on a brain; mob goals skip it"), false);
            return 0;
        }
        List<Added> list = added(m);
        StringBuilder sb = new StringBuilder(FVanilla.idOf(m.getType()).toString()).append(": ");
        if (list.isEmpty()) sb.append("no added goals");
        for (int i = 0; i < list.size(); i++) {
            Entry en = list.get(i).entry();
            if (i > 0) sb.append(", ");
            sb.append(en.goal()).append(" @").append(en.priority());
            if (!en.phases().isEmpty()) sb.append(" phases ").append(new java.util.TreeSet<>(en.phases()));
        }
        String text = sb.toString();
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return list.size();
    }
}
