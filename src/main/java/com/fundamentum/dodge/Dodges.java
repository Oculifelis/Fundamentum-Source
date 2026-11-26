package com.fundamentum.dodge;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.config.ConfigValue;
import com.fundamentum.config.FConfig;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStreamCodec;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Dodging: a quick burst of movement that ignores hits for a few ticks, for boss fights. A player dodges only with at
 * least one dodge charge, from the {@code fundamentum:dodge_charges} attribute (0 by default) plus the
 * {@code base_charges} config. Gear, effects and other mods give charges and improve every part of a dodge through the
 * other {@code fundamentum:dodge_*} attributes.
 * <p>How a dodge moves comes from its style ({@link DodgeStyle}, in {@code data/<ns>/fundamentum/dodge_style/}). The
 * style is picked by worn or held gear ({@code data/<ns>/fundamentum/dodge_gear/}: {@code {"item": "...", "style": "...",
 * "priority": 0}}), then a per player override set by scripts ({@link #setStyle}), then the {@code default_style} config.
 * <p>The server decides everything. The client only sends which way the movement keys point; the server checks the
 * charges, moves the player each tick and ignores hits while the dodge's invulnerable window lasts (hits that bypass
 * invulnerability, like {@code /kill}, still land). {@code fundamentum:dodge_started} can cancel a dodge before any
 * charge is spent, and {@code fundamentum:attack_dodged} reports each hit that missed.
 */
public final class Dodges {
    private Dodges() { }

    public static final FId CONFIG_ID = FId.of("fundamentum", "dodge");
    public static final FId REQUEST = FId.of("fundamentum", "dodge");
    public static final FId STATE = FId.of("fundamentum", "dodge_state");
    public static final FId DASH = FId.of("fundamentum", "dash");

    /** Client to server: the movement keys (forward and strafe from -1 to 1, strafe positive to the left) and jump. */
    public record DodgeRequest(float forward, float strafe, boolean up) {
        public static final FStreamCodec<DodgeRequest> CODEC = FStreamCodec.composite(FStreamCodec.FLOAT, DodgeRequest::forward,
            FStreamCodec.FLOAT, DodgeRequest::strafe, FStreamCodec.BOOL, DodgeRequest::up, DodgeRequest::new);
    }

    /**
     * Server to client: charges ready, the most the player can hold, ticks one charge takes to come back (0 = never),
     * ticks already spent on it, and whether the player is in a low dodge pose right now.
     */
    public record DodgeState(int charges, int max, int need, int progress, boolean low) {
        public static final FStreamCodec<DodgeState> CODEC = FStreamCodec.composite(FStreamCodec.INT, DodgeState::charges,
            FStreamCodec.INT, DodgeState::max, FStreamCodec.INT, DodgeState::need, FStreamCodec.INT, DodgeState::progress,
            FStreamCodec.BOOL, DodgeState::low, DodgeState::new);
        public static final DodgeState NONE = new DodgeState(0, 0, 0, 0, false);
    }

    /** Where the client input setting comes from: the Dodge key, a double tap of a movement key, or both. */
    public enum Input { KEY, DOUBLE_TAP, BOTH }

    /** One gear entry: wearing or holding {@code item} picks {@code style}; the highest priority wins. */
    public record Gear(FId item, FId style, int priority) { }

    /** Per player dodge state; not saved, so a relog refills every charge. */
    private static final class Track {
        ServerPlayer player;
        int spent;
        int progress;
        /** Ticks left of the current dodge's movement, and the motion per tick. */
        int moving;
        double vx, vy, vz;
        boolean gravity;
        boolean low;
        long iframeFrom, iframeTo, chainUntil, lockUntil;
        int airUsed;
        DodgeStyle style = DodgeStyle.DEFAULT;
        final Set<Integer> bumped = new HashSet<>();
        DodgeState sent = DodgeState.NONE;
    }

    private static ConfigValue<Integer> baseCharges;
    private static ConfigValue<FId> defaultStyle;
    private static ConfigValue<Boolean> airDodges;
    private static ConfigValue<Input> input;
    private static ConfigValue<Boolean> doubleTapForward;
    private static ConfigValue<Integer> doubleTapTicks;
    private static ConfigValue<Boolean> hud;

    private static volatile Map<FId, DodgeStyle> styles = Map.of();
    private static volatile Map<FId, Gear> gear = Map.of();
    private static final Map<UUID, Track> TRACK = new ConcurrentHashMap<>();
    private static final Map<UUID, FId> OVERRIDE = new ConcurrentHashMap<>();
    private static volatile DodgeState client = DodgeState.NONE;
    private static volatile long clientAt;
    private static long clock;
    private static boolean initialised;

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        FConfig c = FConfig.create(CONFIG_ID);
        baseCharges = c.integer("base_charges", 0, 0, 20);
        defaultStyle = c.id("default_style", DASH);
        airDodges = c.bool("air_dodges", true);
        input = c.enumValue("input", Input.class, Input.BOTH).side(FConfig.Side.CLIENT);
        doubleTapForward = c.bool("double_tap_forward", false).side(FConfig.Side.CLIENT);
        doubleTapTicks = c.integer("double_tap_ticks", 7, 2, 20).side(FConfig.Side.CLIENT);
        hud = c.bool("hud", true).side(FConfig.Side.CLIENT);
        FNet.register(REQUEST, DodgeRequest.class, DodgeRequest.CODEC, FNet.Side.TO_SERVER, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer p) tryDodge(p, msg.forward(), msg.strafe(), msg.up());
        });
        FNet.register(STATE, DodgeState.class, DodgeState.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) -> {
            client = msg;
            clientAt = System.currentTimeMillis();
            if (ctx.player() != null) DodgeHooks.setLow(ctx.player(), msg.low());
        });
        FLife.jsonData(FId.of("fundamentum", "dodge_style"), "fundamentum/dodge_style", Dodges::loadStyles);
        FLife.jsonData(FId.of("fundamentum", "dodge_gear"), "fundamentum/dodge_gear", Dodges::loadGear);
        FLife.serverTick(Dodges::tick);
        FLife.serverStopped(s -> {
            TRACK.clear();
            OVERRIDE.clear();
        });
        DodgeHooks.init();
    }

    // ---- data ----

    private static void loadStyles(Map<FId, List<JsonElement>> files) {
        Map<FId, DodgeStyle> m = new HashMap<>();
        files.forEach((id, list) -> {
            JsonElement e = list.get(list.size() - 1);
            if (e.isJsonObject()) m.put(id, DodgeStyle.fromJson(id, e.getAsJsonObject()));
        });
        styles = Map.copyOf(m);
    }

    private static void loadGear(Map<FId, List<JsonElement>> files) {
        Map<FId, Gear> m = new HashMap<>();
        files.forEach((id, list) -> {
            JsonElement e = list.get(list.size() - 1);
            if (!e.isJsonObject()) return;
            var o = e.getAsJsonObject();
            FId item = o.has("item") ? FId.tryOf(o.get("item").getAsString()) : null;
            FId style = o.has("style") ? FId.tryOf(o.get("style").getAsString()) : null;
            if (item == null || style == null) return;
            Gear g = new Gear(item, style, o.has("priority") ? o.get("priority").getAsInt() : 0);
            Gear old = m.get(item);
            if (old == null || old.priority() <= g.priority()) m.put(item, g);
        });
        gear = Map.copyOf(m);
    }

    /** Every loaded dodge style, by id. */
    public static Map<FId, DodgeStyle> styles() {
        return styles;
    }

    /** A style by id, or null. */
    public static DodgeStyle style(FId id) {
        return id == null ? null : styles.get(id);
    }

    // ---- reading a player ----

    private static double attr(LivingEntity e, java.util.function.Supplier<net.minecraft.world.entity.ai.attributes.Attribute> a) {
        return FVanilla.attributeValue(e, a.get());
    }

    /** The most dodge charges the player can hold: the attribute plus the {@code base_charges} config. */
    public static int maxCharges(ServerPlayer p) {
        int base = baseCharges == null ? 0 : baseCharges.getOr(0);
        return Math.max(0, (int) Math.floor(attr(p, StatAttributes.DODGE_CHARGES) + base + 1e-6));
    }

    /** Charges ready now. */
    public static int charges(ServerPlayer p) {
        Track t = TRACK.get(p.getUUID());
        return Math.max(0, maxCharges(p) - (t == null ? 0 : t.spent));
    }

    /** True while the player's dodge ignores hits. */
    public static boolean invulnerable(LivingEntity e) {
        Track t = TRACK.get(e.getUUID());
        return t != null && clock >= t.iframeFrom && clock < t.iframeTo;
    }

    /** True while the player is moving in a dodge. */
    public static boolean dodging(LivingEntity e) {
        Track t = TRACK.get(e.getUUID());
        return t != null && t.moving > 0;
    }

    /** True while the player cannot attack after a dodge. */
    public static boolean locked(LivingEntity e) {
        Track t = TRACK.get(e.getUUID());
        return t != null && clock < t.lockUntil;
    }

    /** The style the player dodges with now: script override, then gear, then the config default. */
    public static DodgeStyle styleOf(ServerPlayer p) {
        FId o = OVERRIDE.get(p.getUUID());
        DodgeStyle s = style(o);
        if (s != null) return s;
        Gear best = null;
        Map<FId, Gear> g = gear;
        if (!g.isEmpty()) {
            List<ItemStack> worn = new ArrayList<>();
            for (EquipmentSlot slot : EquipmentSlot.values()) worn.add(p.getItemBySlot(slot));
            try {
                worn.addAll(com.fundamentum.slots.Slots.allWorn(p));
            } catch (RuntimeException ignored) {
                // slot data not ready yet; armour and hands are enough
            }
            for (ItemStack st : worn) {
                if (st.isEmpty()) continue;
                Gear x = g.get(FVanilla.idOf(st.getItem()));
                if (x != null && styles.containsKey(x.style()) && (best == null || x.priority() > best.priority())) best = x;
            }
        }
        if (best != null) return styles.get(best.style());
        s = style(defaultStyle == null ? DASH : defaultStyle.getOr(DASH));
        if (s == null) s = style(DASH);
        return s == null ? DodgeStyle.DEFAULT : s;
    }

    /** Makes the player dodge with this style whatever they wear, or clears that with null. For scripts. */
    public static void setStyle(ServerPlayer p, FId style) {
        if (style == null) OVERRIDE.remove(p.getUUID());
        else OVERRIDE.put(p.getUUID(), style);
    }

    /** Gives back spent charges (all of them with a large number). */
    public static void refill(ServerPlayer p, int count) {
        Track t = TRACK.get(p.getUUID());
        if (t == null) return;
        t.spent = Math.max(0, t.spent - Math.max(0, count));
        if (t.spent == 0) t.progress = 0;
    }

    // ---- dodging ----

    /**
     * Tries a dodge toward the movement keys. Returns true when the player dodged. Scripts may call it too; then
     * {@code forward} and {@code strafe} are the same -1 to 1 key values a client would send.
     */
    public static boolean tryDodge(ServerPlayer p, float forward, float strafe, boolean up) {
        if (!Float.isFinite(forward) || !Float.isFinite(strafe)) return false;
        if (p.isSpectator() || p.isSleeping() || p.isPassenger() || p.isFallFlying() || !p.isAlive()) return false;
        int max = maxCharges(p);
        if (max <= 0) return false;
        Track t = TRACK.computeIfAbsent(p.getUUID(), u -> new Track());
        t.player = p;
        if (max - t.spent <= 0 || t.moving > 0 || clock < t.chainUntil) return false;
        DodgeStyle s = styleOf(p);
        boolean ground = p.onGround() || p.isInWater();
        if (ground && !s.ground()) return false;
        if (!ground) {
            int allowed = s.airDodges() + (int) Math.floor(attr(p, StatAttributes.DODGE_AIR) + 1e-6);
            if (!s.air() || !airDodges.getOr(true) || t.airUsed >= allowed) return false;
        }
        double f = Math.max(-1, Math.min(1, forward));
        double st = Math.max(-1, Math.min(1, strafe));
        double dx, dy = 0, dz;
        if (up && s.allowUp()) {
            dx = 0;
            dz = 0;
            dy = 1;
        } else {
            if (Math.abs(f) < 1e-3 && Math.abs(st) < 1e-3) {
                switch (s.neutral()) {
                    case "forward" -> f = 1;
                    case "none" -> {
                        return false;
                    }
                    default -> f = -1;
                }
            }
            if (s.fourWay()) {
                if (Math.abs(f) >= Math.abs(st)) st = 0;
                else f = 0;
            }
            double len = Math.sqrt(f * f + st * st);
            f /= len;
            st /= len;
            double yaw = Math.toRadians(p.getYRot());
            double sin = Math.sin(yaw), cos = Math.cos(yaw);
            dx = st * cos - f * sin;
            dz = f * cos + st * sin;
        }
        int duration = Math.max(1, (int) Math.round(s.ticks() / Math.max(0.1, attr(p, StatAttributes.DODGE_SPEED))));
        com.fundamentum.weight.Weights.LoadStep load = com.fundamentum.weight.Weights.loadStep(p);
        double speed = s.distance() * attr(p, StatAttributes.DODGE_DISTANCE) * load.distance() / duration;
        double vx = dx * speed, vy = dy * speed, vz = dz * speed;
        if (FEvents.post(new Events.DodgeStarted(p, s.id(), vx, vy, vz))) return false;
        t.spent++;
        t.style = s;
        t.moving = duration;
        t.vx = vx;
        t.vy = vy;
        t.vz = vz;
        t.gravity = s.gravity() && dy == 0;
        t.bumped.clear();
        if (!ground) t.airUsed++;
        double ifr = attr(p, StatAttributes.DODGE_IFRAMES) * load.iframes();
        t.iframeFrom = clock + s.iframeStart();
        t.iframeTo = clock + s.iframeStart() + Math.round(Math.max(0, s.iframeEnd() - s.iframeStart()) * ifr);
        t.chainUntil = clock + duration + Math.round(s.chainDelay() * attr(p, StatAttributes.DODGE_CHAIN_DELAY));
        t.lockUntil = s.lockTicks() > 0 ? clock + duration + Math.round(s.lockTicks() * attr(p, StatAttributes.DODGE_RECOVERY)) : 0;
        float cost = (float) (s.cost() * attr(p, StatAttributes.DODGE_COST) * load.cost());
        if (cost > 0 && !p.isCreative()) p.causeFoodExhaustion(cost);
        if (s.low()) {
            t.low = true;
            DodgeHooks.setLow(p, true);
        }
        if (s.sound() != null) {
            FVanilla.playSound(p.level(), p.getX(), p.getY(), p.getZ(), FVanilla.sound(s.sound()), SoundSource.PLAYERS, s.volume(), s.pitch());
        }
        if (s.particle() != null && s.particleCount() > 0 && p.level() instanceof ServerLevel sl) {
            var po = com.fundamentum.flail.Flails.particle(s.particle().toString());
            if (po != null) FVanilla.particles(sl, po, p.getX(), p.getY() + 0.2, p.getZ(), s.particleCount(), s.particleSpread(), s.particleSpread() / 3, s.particleSpread(), s.particleSpeed());
        }
        step(t);
        sync(t, true);
        return true;
    }

    /** Applies one tick of a dodge's movement. */
    private static void step(Track t) {
        ServerPlayer p = t.player;
        double vy = t.gravity ? p.getDeltaMovement().y : t.vy;
        Vec3 v = new Vec3(t.vx, vy, t.vz);
        p.setDeltaMovement(v);
        p.hurtMarked = true;
        p.resetFallDistance();
        if (p.getClass() != ServerPlayer.class) p.move(MoverType.SELF, v);
        bump(t);
        t.moving--;
        if (t.moving <= 0) {
            p.setDeltaMovement(new Vec3(t.vx * t.style.exitSpeed(), t.gravity ? p.getDeltaMovement().y : 0, t.vz * t.style.exitSpeed()));
            p.hurtMarked = true;
            if (t.low) {
                t.low = false;
                DodgeHooks.setLow(p, false);
            }
        }
    }

    /** Hurts and pushes each mob the dodge passes through, once per dodge, when the player has impact attributes. */
    private static void bump(Track t) {
        ServerPlayer p = t.player;
        double power = attr(p, StatAttributes.DODGE_POWER);
        double push = attr(p, StatAttributes.DODGE_KNOCKBACK);
        if (power <= 0 && push <= 0) return;
        AABB box = p.getBoundingBox().expandTowards(t.vx, t.vy, t.vz).inflate(t.style.impactReach());
        for (Entity e : p.level().getEntities(p, box, x -> x instanceof LivingEntity le && le.isAlive() && !x.isSpectator())) {
            if (!t.bumped.add(e.getId())) continue;
            LivingEntity le = (LivingEntity) e;
            if (power > 0) FVanilla.hurt(le, p.damageSources().playerAttack(p), (float) power);
            double h = Math.sqrt(t.vx * t.vx + t.vz * t.vz);
            if (push > 0 && h > 1e-4) le.knockback(push * t.style.impactKnockback(), -t.vx / h, -t.vz / h);
        }
    }

    /** Called by the loader hooks when a hit reaches a player; returns true when the dodge makes it miss. */
    public static boolean dodgeHit(LivingEntity target, Entity attacker, FId damageType, float amount) {
        if (!(target instanceof ServerPlayer p) || !invulnerable(p)) return false;
        FEvents.post(new Events.AttackDodged(p, attacker, damageType, amount));
        double refund = attr(p, StatAttributes.DODGE_REFUND);
        Track t = TRACK.get(p.getUUID());
        if (t != null && refund > 0 && t.spent > 0 && p.getRandom().nextDouble() < refund) {
            t.spent--;
            // one refund per dodge
            t.iframeTo = Math.min(t.iframeTo, clock + 1);
            sync(t, true);
        }
        return true;
    }

    private static void tick(MinecraftServer server) {
        clock++;
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            seen.add(p.getUUID());
            Track t = TRACK.get(p.getUUID());
            if (t == null) {
                int max = maxCharges(p);
                if (max == 0) continue;
                t = TRACK.computeIfAbsent(p.getUUID(), u -> new Track());
            }
            t.player = p;
            update(t);
        }
        // players outside the player list (fake players of other mods, tests)
        for (var it = TRACK.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (seen.contains(en.getKey())) continue;
            Track t = en.getValue();
            if (t.player == null || (t.spent == 0 && t.moving == 0 && clock >= t.iframeTo && clock >= t.lockUntil)) {
                it.remove();
                continue;
            }
            update(t);
        }
    }

    private static void update(Track t) {
        ServerPlayer p = t.player;
        if (t.moving > 0) step(t);
        if (p.onGround() || p.isInWater()) t.airUsed = 0;
        int max = maxCharges(p);
        if (t.spent > max) t.spent = max;
        if (t.spent > 0) {
            int need = need(p, t);
            if (need > 0 && ++t.progress >= need) {
                t.spent--;
                t.progress = 0;
            }
        } else {
            t.progress = 0;
        }
        sync(t, false);
    }

    /** Ticks for one charge to come back, or 0 when it never does (recharge attribute at 0). */
    private static int need(ServerPlayer p, Track t) {
        double r = attr(p, StatAttributes.DODGE_RECHARGE);
        if (r <= 0) return 0;
        DodgeStyle s = t.moving > 0 ? t.style : styleOf(p);
        return Math.max(1, (int) Math.round(s.rechargeTicks() / r));
    }

    private static void sync(Track t, boolean force) {
        ServerPlayer p = t.player;
        if (p.getClass() != ServerPlayer.class || p.connection == null) return;
        int max = maxCharges(p);
        DodgeState now = new DodgeState(Math.max(0, max - t.spent), max, t.spent > 0 ? need(p, t) : 0, t.progress, t.low);
        DodgeState old = t.sent;
        boolean changed = now.charges() != old.charges() || now.max() != old.max() || now.need() != old.need() || now.low() != old.low()
            || (now.progress() < old.progress());
        if (force || changed) {
            t.sent = now;
            FNet.toPlayer(p, now);
        }
    }

    // ---- client side values ----

    /** The local player's last dodge state from the server. */
    public static DodgeState client() {
        return client;
    }

    /** When {@link #client()} last arrived, in milliseconds. */
    public static long clientAt() {
        return clientAt;
    }

    public static Input input() {
        return input == null ? Input.BOTH : input.getOr(Input.BOTH);
    }

    public static boolean doubleTapForward() {
        return doubleTapForward != null && doubleTapForward.getOr(false);
    }

    public static int doubleTapTicks() {
        return doubleTapTicks == null ? 7 : doubleTapTicks.getOr(7);
    }

    public static boolean hud() {
        return hud == null || hud.getOr(true);
    }

    /** Clears the client copy when leaving a world. */
    public static void clientReset() {
        client = DodgeState.NONE;
    }

    /** Test hook: the server clock. */
    static long clock() {
        return clock;
    }
}
