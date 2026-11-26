package com.fundamentum.survival;

import com.fundamentum.event.Events;
import com.fundamentum.climate.Climate;
import com.fundamentum.condition.Conditions;
import com.fundamentum.config.ConfigValue;
import com.fundamentum.config.Timers;
import com.fundamentum.event.FEvents;
import com.fundamentum.food.FoodConsumed;
import com.fundamentum.port.FCommands;
import com.fundamentum.port.FFood;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStreamCodec;
import com.fundamentum.port.FTags;
import com.fundamentum.port.FVanilla;
import com.fundamentum.stat.Stats;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.context.CommandContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Survival meters (thirst, body temperature, fatigue and the like), defined in data under
 * {@code data/<ns>/fundamentum/survival/} (see {@link Meter}). No meter exists unless a mod or data pack adds one.
 * Each meter's value is a stat, so it saves, syncs, shows in the stats screen and works in {@code stat} conditions.
 * Meters run for every online player in survival or adventure mode. They change in whole seconds, the same as a step every
 * second, but nothing is checked every second: each player keeps a plan (which rules hold right now) that is rebuilt only
 * when something a rule reads changes, and the meters are brought up to date only when a value crosses a whole number or
 * a threshold, a threshold's effect needs renewing or its damage is due. Events rebuild the plan: eating, hurt and heal,
 * effects, sprinting, sneaking, swimming and sleeping, equipment and slots, dimension and section changes, respawn, a
 * meter stat set from outside and a data reload. Rules that read things no event reports (moving, water, rain, fire,
 * lava, climate and general conditions) are checked again every {@code survival_recheck_seconds} (the
 * {@code fundamentum:performance} config, 5 by default); every plan is checked again after 30 seconds as a safety net.
 */
public final class Survival {
    private Survival() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");

    public static final FId DEFS = FId.of("fundamentum", "survival_defs");
    public static final FId DRINK = FId.of("fundamentum", "survival_drink");
    /** How far a player reaches to drink from a block. */
    public static final double DRINK_REACH = 5;
    private static final int DRINK_COOLDOWN = 10;

    /** The meters the server sends at login and after a reload: id to the meter's JSON. */
    public record Defs(Map<String, String> json) {
        public static final FStreamCodec<Defs> CODEC = FStreamCodec.mapOf(FStreamCodec.STRING, FStreamCodec.STRING).map(Defs::new, Defs::json);
    }

    /** Sent by a client when its player tries to drink from the block they look at with an empty hand. */
    public record DrinkRequest() {
        public static final FStreamCodec<DrinkRequest> CODEC = FStreamCodec.unit(new DrinkRequest());
    }

    /** Per player state that is not saved: the plan, the schedule, last position, damage timers, drink cooldown. */
    private static final class Track {
        double x, z;
        boolean placed;
        boolean moving;
        long sampled;
        long lastDrink = Long.MIN_VALUE;
        final Map<String, Integer> timers = new HashMap<>();
        final java.util.Set<String> active = new java.util.HashSet<>();
        /** The entity last seen, to notice a respawn (a new ServerPlayer object). */
        ServerPlayer entity;
        /** Clock tick the meters were last brought up to date; whole seconds are counted from it. */
        long last;
        /** Clock tick of the next scheduled update, and of the next plan check. */
        long wake;
        long recheck;
        boolean dirty = true;
        int epoch = -1;
        /** Per meter, the steps that hold now: {0, amount} or {1, climate target, rate}. A meter left out is exempt. */
        Map<FId, double[][]> plan = Map.of();
    }

    /** Activities some event reports when they change; any other input to a rule is checked on the slow recheck. */
    private static final java.util.Set<String> EVENTED = java.util.Set.of("sprinting", "swimming", "sneaking", "sleeping");
    /** A plan is checked again after this long even when nothing said it changed. */
    private static final int SAFETY_SECONDS = 30;

    private static volatile Map<FId, Meter> meters = Map.of();
    private static volatile Map<FId, Meter> clientMeters = Map.of();
    private static final java.util.concurrent.atomic.AtomicInteger CLIENT_VERSION = new java.util.concurrent.atomic.AtomicInteger();
    private static final Map<UUID, Track> TRACK = new ConcurrentHashMap<>();
    private static volatile boolean defsChanged;
    /** Meters with a rule that reads something no event reports. */
    private static volatile java.util.Set<FId> polled = java.util.Set.of();
    private static volatile int epoch;
    private static long clock;
    private static boolean initialised;
    private static boolean writing;
    private static ConfigValue<Integer> recheck;

    /** Called once while Fundamentum loads. */
    public static void init() {
        if (initialised) return;
        initialised = true;
        FNet.register(DEFS, Defs.class, Defs.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) -> {
            clientMeters = parseAll(msg.json());
            CLIENT_VERSION.incrementAndGet();
        });
        FNet.register(DRINK, DrinkRequest.class, DrinkRequest.CODEC, FNet.Side.TO_SERVER, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer p) drinkFromBlock(p);
        });
        FLife.jsonData(FId.of("fundamentum", "survival"), "fundamentum/survival", Survival::load);
        FLife.serverTick(Survival::tick);
        recheck = Timers.seconds("survival_recheck_seconds", 5);
        FLife.serverStopped(s -> {
            clock = 0;
            TRACK.clear();
        });
        FLife.playerLoggedIn(p -> {
            FNet.toPlayer(p, defsMessage());
            TRACK.remove(p.getUUID());
        });
        FEvents.listen(FoodConsumed.class, (FoodConsumed e) -> {
            consumed(e.player(), e.stack());
            touch(e.player());
        });
        FLife.playerStateChanged(Survival::touch);
        FLife.sectionEntered(Survival::touch);
        FLife.inventoryChanged(Survival::touch);
        FLife.handsChanged(Survival::touch);
        FLife.playerChangedDimension((p, from, to) -> touch(p));
        FLife.effectsChanged(e -> {
            if (e instanceof ServerPlayer p) touch(p);
        });
        FEvents.listen(Events.SlotEquipped.class, (Events.SlotEquipped e) -> {
            if (e.entity() instanceof ServerPlayer p) touch(p);
        });
        FEvents.listen(Events.SlotUnequipped.class, (Events.SlotUnequipped e) -> {
            if (e.entity() instanceof ServerPlayer p) touch(p);
        });
        FEvents.listen(Events.StatChanged.class, (Events.StatChanged e) -> {
            if (!writing && e.entity() instanceof ServerPlayer p && meterStat(e.stat())) touch(p);
        });
        Conditions.register("activity", (json, ctx) -> {
            if (!(ctx.entity() instanceof ServerPlayer p) || !json.has("is")) return false;
            JsonElement is = json.get("is");
            if (is.isJsonArray()) {
                for (JsonElement e : is.getAsJsonArray()) if (!activity(p, e.getAsString())) return false;
                return true;
            }
            return activity(p, is.getAsString());
        });
        Conditions.register("climate", (json, ctx) -> {
            if (ctx.entity() == null) return false;
            String value = json.has("value") ? json.get("value").getAsString() : "temperature";
            return Conditions.inRange(climate(ctx.entity().level(), ctx.entity().blockPosition(), value), json);
        });
        FCommands.listen(d -> d.register(Commands.literal("fundamentum").requires(FCommands.op())
            .then(Commands.literal("survival").executes(Survival::command))));
    }

    // ---- data

    /** A meter's optional {@code requires} (every mod listed must be loaded) and {@code unless} (none may be). */
    private static boolean modsAllow(JsonObject o) {
        if (o.has("requires")) for (JsonElement m : o.getAsJsonArray("requires")) if (!com.fundamentum.port.FClimate.modLoaded(m.getAsString())) return false;
        if (o.has("unless")) for (JsonElement m : o.getAsJsonArray("unless")) if (com.fundamentum.port.FClimate.modLoaded(m.getAsString())) return false;
        return true;
    }

    private static void load(Map<FId, List<JsonElement>> files) {
        Map<FId, Meter> out = new TreeMap<>();
        files.forEach((id, list) -> {
            JsonElement last = list.get(list.size() - 1);
            try {
                JsonObject o = last.getAsJsonObject();
                if (o.has("disabled") && o.get("disabled").getAsBoolean()) return;
                if (!modsAllow(o)) return;
                out.put(id, Meter.parse(id, o));
            } catch (RuntimeException e) {
                LOG.error("Survival meter {} is broken: {}", id, e.toString());
            }
        });
        meters = Collections.unmodifiableMap(out);
        java.util.Set<FId> poll = new java.util.HashSet<>();
        for (Meter m : out.values()) {
            for (Meter.Rule r : m.rules()) if (r.climate() != null || !evented(r.when())) poll.add(m.id());
        }
        polled = java.util.Set.copyOf(poll);
        epoch++;
        defsChanged = true;
        if (!out.isEmpty()) LOG.info("Loaded {} survival meter(s)", out.size());
    }

    /** Parses the meters a {@link Defs} message carries; broken ones are skipped. */
    public static Map<FId, Meter> parseAll(Map<String, String> json) {
        Map<FId, Meter> out = new TreeMap<>();
        json.forEach((k, v) -> {
            FId id = FId.tryOf(k);
            if (id == null) return;
            try {
                out.put(id, Meter.parse(id, JsonParser.parseString(v).getAsJsonObject()));
            } catch (RuntimeException e) {
                LOG.warn("Survival meter {} from the server could not be read: {}", id, e.toString());
            }
        });
        return Collections.unmodifiableMap(out);
    }

    public static Defs defsMessage() {
        Map<String, String> json = new HashMap<>();
        meters.forEach((id, m) -> json.put(id.toString(), m.json()));
        return new Defs(json);
    }

    /** Every loaded meter, by id (server side). */
    public static Map<FId, Meter> meters() {
        return meters;
    }

    public static Meter meter(FId id) {
        return meters.get(id);
    }

    /** Goes up each time the client's meter declarations change. */
    public static int clientVersion() {
        return CLIENT_VERSION.get();
    }

    /** The meters the server last sent (client side). */
    public static Map<FId, Meter> clientMeters() {
        return clientMeters;
    }

    // ---- values

    /** The player's value for a meter (its start value when the player has none yet). */
    public static double get(Player player, Meter meter) {
        if (Stats.def(meter.stat()).stored() && !Stats.ENTITY.get(player).containsKey(meter.stat().toString())) return meter.start();
        return Stats.base(player, meter.stat());
    }

    /** Sets the player's value, kept inside the meter's range. */
    public static void set(Player player, Meter meter, double value) {
        write(player, meter, value);
        if (player instanceof ServerPlayer p) touch(p);
    }

    private static void write(Player player, Meter meter, double value) {
        boolean was = writing;
        writing = true;
        try {
            Stats.set(player, meter.stat(), meter.clamp(value));
        } finally {
            writing = was;
        }
    }

    private static boolean meterStat(FId stat) {
        for (Meter m : meters.values()) if (m.stat().equals(stat)) return true;
        return false;
    }

    public static void add(Player player, Meter meter, double amount) {
        set(player, meter, get(player, meter) + amount);
    }

    // ---- ticking

    /**
     * Something a rule may read changed for the player: the plan is rebuilt on the next tick, after the seconds that
     * passed are counted with the old one.
     */
    public static void touch(ServerPlayer p) {
        Track t = TRACK.get(p.getUUID());
        if (t != null && t.entity == p) t.dirty = true;
    }

    private static void tick(MinecraftServer server) {
        if (defsChanged) {
            defsChanged = false;
            Defs msg = defsMessage();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) FNet.toPlayer(p, msg);
        }
        clock++;
        if (meters.isEmpty()) return;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer p = players.get(i);
            Track t = TRACK.get(p.getUUID());
            if (t == null || t.entity != p) t = track(p);
            if (t.dirty || t.epoch != epoch || clock >= t.wake) update(p, t);
        }
    }

    /** The player's track; a new entity (login or respawn) starts its clock now, resetting meters after a death. */
    private static Track track(ServerPlayer p) {
        Track t = TRACK.computeIfAbsent(p.getUUID(), u -> new Track());
        if (t.entity == p) return t;
        boolean respawned = t.entity != null && t.entity.getHealth() <= 0;
        t.entity = p;
        t.last = clock;
        t.dirty = true;
        t.placed = false;
        if (respawned) {
            for (Meter m : meters.values()) {
                if (!m.resetOnDeath()) continue;
                write(p, m, m.respawn());
                t.timers.keySet().removeIf(k -> k.startsWith(m.id() + "#"));
            }
        }
        return t;
    }

    /** Counts the whole seconds since the last update with the current plan, rebuilds the plan if due, schedules. */
    private static void update(ServerPlayer p, Track t) {
        if (p.isDeadOrDying()) {
            t.last = clock;
            t.wake = clock + 20;
            return;
        }
        int secs = (int) Math.min(Integer.MAX_VALUE, (clock - t.last) / 20);
        if (secs > 0) {
            advance(p, t, secs);
            t.last += secs * 20L;
        }
        if (t.dirty || t.epoch != epoch || clock >= t.recheck) {
            if (clock - t.sampled >= 20) sample(p, t, (clock - t.sampled) / 20.0);
            plan(p, t);
        }
        schedule(p, t);
    }

    /**
     * One second of every meter for the player: death resets, rules, then thresholds. Online players update by
     * themselves when something changes; call it for others (it does nothing for dead players).
     */
    public static void evaluate(ServerPlayer p) {
        evaluate(p, 1);
    }

    /** {@link #evaluate(ServerPlayer)} for several seconds at once: amounts and damage timers scale with them. */
    public static void evaluate(ServerPlayer p, int seconds) {
        if (meters.isEmpty()) return;
        int secs = Math.max(1, seconds);
        Track t = track(p);
        if (p.isDeadOrDying()) return;
        sample(p, t, 1);
        plan(p, t);
        advance(p, t, secs);
        t.last = clock;
        schedule(p, t);
    }

    /** Whether the player moved since the last sample (more than half a block a second). */
    private static void sample(ServerPlayer p, Track t, double secs) {
        t.moving = t.placed && (Math.abs(p.getX() - t.x) + Math.abs(p.getZ() - t.z)) > 0.5 * Math.max(1, secs);
        t.x = p.getX();
        t.z = p.getZ();
        t.placed = true;
        t.sampled = clock;
    }

    /** Works out which rules hold now. */
    private static void plan(ServerPlayer p, Track t) {
        t.dirty = false;
        t.epoch = epoch;
        boolean exempt = p.isCreative() || p.isSpectator();
        boolean poll = false;
        Map<FId, double[][]> plan = new HashMap<>();
        for (Meter m : meters.values()) {
            if (exempt && !m.creative()) continue;
            if (polled.contains(m.id())) poll = true;
            List<double[]> steps = new java.util.ArrayList<>();
            for (Meter.Rule r : m.rules()) {
                if (!when(p, r.when())) continue;
                if (r.climate() != null) {
                    double target = climate(p.level(), p.blockPosition(), r.climate()) * r.scale() + r.offset();
                    steps.add(new double[] {1, target, Meter.clamp(r.rate(), 0, 1)});
                } else if (r.amount() != 0) {
                    steps.add(new double[] {0, r.amount()});
                }
            }
            plan.put(m.id(), steps.toArray(new double[0][]));
        }
        t.plan = plan;
        int slow = poll ? Math.max(1, recheck == null ? 5 : recheck.getOr(5)) : SAFETY_SECONDS;
        t.recheck = clock + slow * 20L;
    }

    /** One second of a plan's steps on a value: amounts add, climate steps move part of the way to their target. */
    private static double second(Meter m, double[][] steps, double v) {
        for (double[] st : steps) v += st[0] == 0 ? st[1] : (st[1] - v) * st[2];
        return m.clamp(v);
    }

    /** Runs the plan for whole seconds, then the thresholds. */
    private static void advance(ServerPlayer p, Track t, int secs) {
        for (Meter m : meters.values()) {
            double[][] steps = t.plan.get(m.id());
            if (steps == null) continue;
            double before = get(p, m);
            double v = before;
            int n = Math.min(secs, 3600);
            for (int i = 0; i < n && steps.length > 0; i++) {
                double next = second(m, steps, v);
                if (next == v) break;
                v = next;
            }
            if (v != before || !Stats.ENTITY.get(p).containsKey(m.stat().toString())) write(p, m, v);
            thresholds(p, m, v, t, secs);
        }
    }

    /**
     * The next update: when a value crosses a whole number or a threshold, a threshold effect needs renewing or its
     * damage is due, or the plan is due a check, whichever comes first.
     */
    private static void schedule(ServerPlayer p, Track t) {
        int next = SAFETY_SECONDS;
        for (Meter m : meters.values()) {
            double[][] steps = t.plan.get(m.id());
            if (steps == null) continue;
            double v = get(p, m);
            List<Meter.Threshold> ths = m.thresholds();
            for (int i = 0; i < ths.size(); i++) {
                Meter.Threshold th = ths.get(i);
                if (!th.active(v)) continue;
                if (th.effect() != null) next = Math.min(next, Math.max(1, th.duration() / 20 - 1));
                if (th.damage() > 0) next = Math.min(next, Math.max(1, th.interval() - t.timers.getOrDefault(m.id() + "#" + i, 0)));
            }
            if (steps.length == 0) continue;
            double w = v;
            for (int s = 1; s < next; s++) {
                double after = second(m, steps, w);
                if (after == w) break;
                w = after;
                if (Math.floor(w) != Math.floor(v) || crossed(ths, v, w)) {
                    next = s;
                    break;
                }
            }
        }
        t.wake = Math.min(t.last + next * 20L, t.recheck);
    }

    private static boolean crossed(List<Meter.Threshold> ths, double a, double b) {
        for (Meter.Threshold th : ths) if (th.active(a) != th.active(b)) return true;
        return false;
    }

    /** Whether a rule's {@code when} reads only things some event reports. */
    static boolean evented(JsonElement when) {
        if (when == null || when.isJsonNull()) return true;
        if (when.isJsonPrimitive()) return EVENTED.contains(when.getAsString().toLowerCase(Locale.ROOT));
        if (when.isJsonArray()) {
            for (JsonElement e : when.getAsJsonArray()) if (!evented(e)) return false;
            return true;
        }
        JsonObject o = when.getAsJsonObject();
        if (!o.has("type") || !"activity".equals(o.get("type").getAsString()) || !o.has("is")) return false;
        return evented(o.get("is"));
    }

    /** Ticks until the player's meters next update by themselves (for tests); -1 when they have no track. */
    public static long nextUpdate(ServerPlayer p) {
        Track t = TRACK.get(p.getUUID());
        return t == null || t.entity != p ? -1 : t.wake - clock;
    }

    /** Whether the player's plan waits to be rebuilt (for tests). */
    public static boolean pending(ServerPlayer p) {
        Track t = TRACK.get(p.getUUID());
        return t != null && t.entity == p && t.dirty;
    }

    /** The generic damage type for a meter that has none of its own. */
    public static final FId METER_DAMAGE = FId.of("fundamentum", "meter");

    /**
     * The damage a meter threshold deals: the threshold's {@code damage_type} when it names one, else a damage type with
     * the meter's own id, else {@code fundamentum:<the meter's name>} (Fundamentum ships thirst, overheating,
     * hypothermia and exhaustion), else {@code fundamentum:meter}. Each has its own death message.
     */
    public static net.minecraft.world.damagesource.DamageSource meterDamage(ServerPlayer p, Meter m, FId named) {
        List<FId> tries = named != null ? List.of(named)
            : List.of(m.id(), FId.of("fundamentum", m.id().path()), METER_DAMAGE);
        RuntimeException last = null;
        for (FId type : tries) {
            try {
                return FVanilla.damage(p.level(), type, null);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    private static void thresholds(ServerPlayer p, Meter m, double v, Track t, int secs) {
        for (int i = 0; i < m.thresholds().size(); i++) {
            Meter.Threshold th = m.thresholds().get(i);
            String key = m.id() + "#" + i;
            if (!th.active(v)) {
                t.timers.remove(key);
                if (t.active.remove(key)) FEvents.post(new Events.MeterThreshold(p, m.id(), i, false));
                continue;
            }
            if (t.active.add(key)) FEvents.post(new Events.MeterThreshold(p, m.id(), i, true));
            if (th.effect() != null) {
                MobEffectInstance e = FFood.effect(th.effect(), th.duration(), th.amplifier());
                if (e != null) p.addEffect(e);
            }
            if (th.damage() > 0) {
                int n = t.timers.merge(key, secs, Integer::sum);
                if (n >= th.interval()) {
                    int hits = Math.max(1, n / Math.max(1, th.interval()));
                    t.timers.put(key, n - hits * Math.max(1, th.interval()));
                    try {
                        FVanilla.hurt(p, meterDamage(p, m, th.damageType()), th.damage() * hits);
                    } catch (RuntimeException ex) {
                        LOG.warn("Survival meter {} damage type {} failed: {}", m.id(), th.damageType(), ex.toString());
                    }
                }
            }
        }
    }

    /** A rule's {@code when}: null is true, a string is an activity, an object a condition, a list must all hold. */
    public static boolean when(ServerPlayer p, JsonElement when) {
        if (when == null || when.isJsonNull()) return true;
        if (when.isJsonPrimitive()) return activity(p, when.getAsString());
        if (when.isJsonArray()) {
            for (JsonElement e : when.getAsJsonArray()) if (!when(p, e)) return false;
            return true;
        }
        return Conditions.test(when, p);
    }

    /** True when the player is doing the named activity. Unknown names are false. */
    public static boolean activity(ServerPlayer p, String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "sprinting" -> p.isSprinting();
            case "swimming" -> p.isSwimming();
            case "sneaking" -> p.isShiftKeyDown();
            case "moving" -> moving(p);
            case "still" -> !moving(p);
            case "in_water" -> p.isInWater();
            case "underwater" -> p.isUnderWater();
            case "in_rain" -> p.level().isRainingAt(p.blockPosition()) || p.level().isRainingAt(p.blockPosition().above());
            case "on_fire" -> p.isOnFire();
            case "in_lava" -> p.isInLava();
            case "sleeping" -> p.isSleeping();
            default -> false;
        };
    }

    private static boolean moving(ServerPlayer p) {
        Track t = TRACK.get(p.getUUID());
        return t != null && t.moving;
    }

    /** The climate reading a meter uses: temperature or humidity at the spot. */
    public static double climate(Level level, BlockPos pos, String value) {
        return "humidity".equals(value) ? Climate.humidity(level, pos) : Climate.temperature(level, pos);
    }

    // ---- eating and drinking

    private static void consumed(ServerPlayer p, ItemStack stack) {
        for (Meter m : meters.values()) {
            for (Meter.Consume c : m.consume()) {
                if (matchesItem(c.items(), stack)) add(p, m, c.amount());
            }
        }
    }

    public static boolean matchesItem(List<String> entries, ItemStack stack) {
        for (String s : entries) {
            if (s.startsWith("#")) {
                FId tag = FId.tryOf(s.substring(1));
                if (tag != null && stack.is(FTags.item(tag))) return true;
            } else {
                FId id = FId.tryOf(s);
                if (id != null && FVanilla.idOf(stack.getItem()).equals(id)) return true;
            }
        }
        return false;
    }

    public static boolean matchesBlock(List<String> entries, BlockState state) {
        for (String s : entries) {
            if (s.startsWith("#")) {
                FId tag = FId.tryOf(s.substring(1));
                if (tag != null && state.is(FTags.block(tag))) return true;
            } else {
                FId id = FId.tryOf(s);
                if (id != null && FVanilla.idOf(state.getBlock()).equals(id)) return true;
            }
        }
        return false;
    }

    /**
     * The block state a player would drink from: the fluid they look at (as its block, like minecraft:water), else the
     * block itself; null when they look at nothing within {@link #DRINK_REACH}. Works on both sides.
     */
    public static BlockState drinkTarget(Player p) {
        HitResult hit = p.pick(DRINK_REACH, 1.0F, true);
        if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = bh.getBlockPos();
        BlockState fluid = p.level().getFluidState(pos).createLegacyBlock();
        return fluid.isAir() ? p.level().getBlockState(pos) : fluid;
    }

    /** True when any of these meters lets a player drink from the state. */
    public static boolean drinkable(Map<FId, Meter> from, BlockState state) {
        if (state == null) return false;
        for (Meter m : from.values()) {
            for (Meter.DrinkBlock d : m.drinkBlocks()) if (matchesBlock(d.blocks(), state)) return true;
        }
        return false;
    }

    /**
     * The player drinks from the block they look at: every meter that allows it, and is not full, goes up. Returns
     * true when any did. Called for a client's {@link DrinkRequest}; also callable directly.
     */
    public static boolean drinkFromBlock(ServerPlayer p) {
        if (meters.isEmpty() || p.isDeadOrDying() || p.isSpectator() || !p.getMainHandItem().isEmpty()) return false;
        Track t = TRACK.computeIfAbsent(p.getUUID(), u -> new Track());
        long now = p.level().getGameTime();
        if (t.lastDrink != Long.MIN_VALUE && now - t.lastDrink < DRINK_COOLDOWN && now >= t.lastDrink) return false;
        BlockState state = drinkTarget(p);
        if (state == null) return false;
        boolean any = false;
        for (Meter m : meters.values()) {
            for (Meter.DrinkBlock d : m.drinkBlocks()) {
                if (!matchesBlock(d.blocks(), state)) continue;
                if (get(p, m) >= m.max()) continue;
                add(p, m, d.amount());
                any = true;
            }
        }
        if (any) {
            t.lastDrink = now;
            FVanilla.playSound(p.level(), p.getX(), p.getY(), p.getZ(), SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 0.5F,
                p.level().getRandom().nextFloat() * 0.1F + 0.9F);
        }
        return any;
    }

    /** Forgets the drink cooldown (for tests). */
    public static void resetCooldown(ServerPlayer p) {
        Track t = TRACK.get(p.getUUID());
        if (t != null) t.lastDrink = Long.MIN_VALUE;
    }

    // ---- command

    /** {@code /fundamentum survival}: the player's value for every meter. */
    private static int command(CommandContext<CommandSourceStack> c) {
        if (!(c.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        if (meters.isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal("No survival meters are loaded."), false);
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        meters.forEach((id, m) -> sb.append(sb.length() == 0 ? "" : ", ").append(id).append(' ')
            .append(String.format(Locale.ROOT, "%.2f", get(p, m))).append('/').append(String.format(Locale.ROOT, "%.0f", m.max())));
        c.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return meters.size();
    }
}
