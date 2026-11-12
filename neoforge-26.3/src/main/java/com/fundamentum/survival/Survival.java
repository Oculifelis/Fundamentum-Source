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
 * Meters run for every online player in survival or adventure mode, one step every {@code survival_step_seconds} (the
 * {@code fundamentum:performance} config, 1 by default), each player on its own tick of that cycle so the work is spread
 * out. A longer step does the same seconds of change in one go.
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

    /** Per player state that is not saved: last position, damage timers, drink cooldown. */
    private static final class Track {
        double x, y, z;
        boolean placed;
        boolean moving;
        long lastDrink = Long.MIN_VALUE;
        final Map<String, Integer> timers = new HashMap<>();
        final java.util.Set<String> active = new java.util.HashSet<>();
    }

    private static volatile Map<FId, Meter> meters = Map.of();
    private static volatile Map<FId, Meter> clientMeters = Map.of();
    private static final java.util.concurrent.atomic.AtomicInteger CLIENT_VERSION = new java.util.concurrent.atomic.AtomicInteger();
    private static final Map<UUID, Track> TRACK = new ConcurrentHashMap<>();
    private static final Map<UUID, ServerPlayer> LAST_ENTITY = new ConcurrentHashMap<>();
    private static volatile boolean defsChanged;
    private static int ticks;
    private static boolean initialised;
    private static ConfigValue<Integer> step;

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
        step = Timers.seconds("survival_step_seconds", 1);
        FLife.serverStopped(s -> {
            ticks = 0;
            TRACK.clear();
            LAST_ENTITY.clear();
        });
        FLife.playerLoggedIn(p -> {
            FNet.toPlayer(p, defsMessage());
            TRACK.remove(p.getUUID());
        });
        FEvents.listen(FoodConsumed.class, (FoodConsumed e) -> consumed(e.player(), e.stack()));
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

    private static void load(Map<FId, List<JsonElement>> files) {
        Map<FId, Meter> out = new TreeMap<>();
        files.forEach((id, list) -> {
            JsonElement last = list.get(list.size() - 1);
            try {
                JsonObject o = last.getAsJsonObject();
                if (o.has("disabled") && o.get("disabled").getAsBoolean()) return;
                out.put(id, Meter.parse(id, o));
            } catch (RuntimeException e) {
                LOG.error("Survival meter {} is broken: {}", id, e.toString());
            }
        });
        meters = Collections.unmodifiableMap(out);
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
        return Stats.get(player, meter.stat());
    }

    /** Sets the player's value, kept inside the meter's range. */
    public static void set(Player player, Meter meter, double value) {
        Stats.set(player, meter.stat(), meter.clamp(value));
    }

    public static void add(Player player, Meter meter, double amount) {
        set(player, meter, get(player, meter) + amount);
    }

    // ---- ticking

    private static void tick(MinecraftServer server) {
        if (defsChanged) {
            defsChanged = false;
            Defs msg = defsMessage();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) FNet.toPlayer(p, msg);
        }
        ticks++;
        if (meters.isEmpty()) return;
        int seconds = step == null ? 1 : Math.max(1, step.getOr(1));
        int period = seconds * 20;
        for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) {
            if (Timers.due(period, server, p.getUUID())) evaluate(p, seconds);
        }
    }

    /**
     * One second of every meter for the player: death resets, rules, then thresholds. Runs by itself for online
     * players; call it for others (it does nothing for dead players).
     */
    public static void evaluate(ServerPlayer p) {
        evaluate(p, 1);
    }

    /** {@link #evaluate(ServerPlayer)} for several seconds at once: amounts and damage timers scale with them. */
    public static void evaluate(ServerPlayer p, int seconds) {
        if (meters.isEmpty()) return;
        int secs = Math.max(1, seconds);
        UUID uuid = p.getUUID();
        ServerPlayer before = LAST_ENTITY.put(uuid, p);
        boolean respawned = before != null && before != p && before.getHealth() <= 0;
        if (p.isDeadOrDying()) return;
        Track t = TRACK.computeIfAbsent(uuid, u -> new Track());
        t.moving = t.placed && (Math.abs(p.getX() - t.x) + Math.abs(p.getZ() - t.z)) > 0.5;
        t.x = p.getX();
        t.y = p.getY();
        t.z = p.getZ();
        t.placed = true;
        boolean exempt = p.isCreative() || p.isSpectator();
        for (Meter m : meters.values()) {
            if (respawned && m.resetOnDeath()) {
                set(p, m, m.respawn());
                t.timers.keySet().removeIf(k -> k.startsWith(m.id() + "#"));
            }
            if (exempt && !m.creative()) continue;
            double v = get(p, m);
            for (Meter.Rule r : m.rules()) {
                if (!when(p, r.when())) continue;
                if (r.climate() != null) {
                    double target = climate(p.level(), p.blockPosition(), r.climate()) * r.scale() + r.offset();
                    double rate = Meter.clamp(r.rate(), 0, 1);
                    // moving part of the way each second, for several seconds
                    v += (target - v) * (secs == 1 ? rate : 1 - Math.pow(1 - rate, secs));
                } else {
                    v += r.amount() * secs;
                }
            }
            v = m.clamp(v);
            set(p, m, v);
            thresholds(p, m, v, t, secs);
        }
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
