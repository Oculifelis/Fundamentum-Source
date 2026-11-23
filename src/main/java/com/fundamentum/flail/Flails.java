package com.fundamentum.flail;

import com.fundamentum.compat.BetterCombat;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.fundamentum.trigger.Triggers;
import com.fundamentum.whip.WhipTargets;
import com.fundamentum.whip.WhipVec;
import com.fundamentum.whip.Whips;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flails: a head (or several) on a chain, built on the whip cord's maths ({@link FlailSim}). A flail is data: a file in
 * {@code data/<ns>/fundamentum/flails/} naming its item, physics, material and kind (see {@link FlailSpec} and the
 * README). Any item can be one.
 *
 * <p>Controls. Left click swings the flail across the front (alternating sides); with Better Combat loaded, left click is
 * Better Combat's attack from the item's {@code weapon_attributes} instead, and the chain only follows it on screen.
 * Holding use spins it overhead (the spin hits too); letting go smashes it down ahead, a ground slam when it has
 * {@code ground_slam} and was spun up enough, or throws the head out for launch and hybrid flails. A tap of use throws a
 * launch flail's head at once.
 *
 * <p>Every hit with a flail in hand, also Better Combat's and vanilla's, gets the flail's {@code armor_pierce}, its
 * material's {@code impact} and its {@code wraps_target}.
 */
public final class Flails {
    static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    /** {@code #fundamentum:flails}: every flail item (the data files add theirs here for other mods to find). */
    public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("fundamentum", "flails"));
    /** The flail type as Better Combat sees it: when it is loaded it plays the left click (the chain follows Better Combat's swing and its hits are Better Combat's). */
    public static final BetterCombat.WeaponType BETTER_COMBAT = BetterCombat.register("flail", TAG,
        ResourceLocation.fromNamespaceAndPath("fundamentum", "flail"), BetterCombat.LeftClick.BETTER_COMBAT);

    public static final DataTable<JsonObject> FLAILS = DataTables.registerJson(ResourceLocation.fromNamespaceAndPath("fundamentum", "flails"),
        "fundamentum/flails").keyField("item").synced();
    public static final DataTable<JsonObject> MATERIALS = DataTables.registerJson(
        ResourceLocation.fromNamespaceAndPath("fundamentum", "flail_materials"), "fundamentum/flail_materials").synced();
    public static final DataTable<JsonObject> KINDS = DataTables.registerJson(ResourceLocation.fromNamespaceAndPath("fundamentum", "flail_kinds"),
        "fundamentum/flail_kinds").synced();

    private static volatile Map<ResourceLocation, FlailSpec> specs;
    private static volatile Map<ResourceLocation, String> errors = Map.of();
    private static final Map<ResourceLocation, FlailHooks> ITEM_HOOKS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, FlailHooks> NAMED_HOOKS = new ConcurrentHashMap<>();
    private static final List<Runnable> RELOADS = new CopyOnWriteArrayList<>();

    /** A player's flail in hand. */
    static final class State {
        final ResourceLocation item;
        final FlailSpec spec;
        final FlailSim sim;
        boolean useDown;
        int useTicks;
        int side = 1;
        boolean swinging;
        /** The current swing only follows Better Combat's attack: drawn, no hits of its own. */
        boolean visualOnly;
        boolean slammed;
        final Map<Integer, Long> lastHit = new HashMap<>();
        @Nullable LivingEntity hooked;

        State(ResourceLocation item, FlailSpec spec, FlailSim sim) {
            this.item = item;
            this.spec = spec;
            this.sim = sim;
        }
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    /** The flail a hit in progress is dealt with (set around our own hurt calls). */
    private static final ThreadLocal<FlailSpec> DEALING = new ThreadLocal<>();

    /** Below this head speed (blocks a tick) a head brushes past without hitting. */
    static final double MIN_HIT_SPEED = 0.15;
    /** Ticks before the same head can hit the same creature again. */
    static final int REHIT_TICKS = 10;
    /** Holding use this many ticks is a spin, not a tap. */
    static final int HOLD_TICKS = 4;

    private Flails() {}

    // ------------------------------------------------------------------------------------------------ the data
    /** The resolved flail for this item, or null when it isn't one (or its file is broken: see the log). */
    public static @Nullable FlailSpec spec(ItemStack stack) {
        return stack.isEmpty() ? null : spec(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    public static @Nullable FlailSpec spec(ResourceLocation item) {
        return specs().get(item);
    }

    /** Every resolved flail, by item. */
    public static Map<ResourceLocation, FlailSpec> specs() {
        Map<ResourceLocation, FlailSpec> s = specs;
        if (s == null) {
            s = resolveAll();
            specs = s;
        }
        return s;
    }

    /** The flails whose files failed to resolve, with the reason (also in the log). */
    public static Map<ResourceLocation, String> errors() {
        specs();
        return errors;
    }

    private static synchronized Map<ResourceLocation, FlailSpec> resolveAll() {
        Map<String, JsonObject> materials = new HashMap<>(), kinds = new HashMap<>();
        Map<ResourceLocation, String> bad = new HashMap<>();
        MATERIALS.entries().forEach((id, json) -> {
            try {
                FlailSpec.checkPreset("material " + id, json, true);
                materials.put(id.toString(), json);
            } catch (IllegalArgumentException e) {
                bad.put(id, e.getMessage());
                LOG.error("Fundamentum: flail material {} is broken: {}", id, e.getMessage());
            }
        });
        KINDS.entries().forEach((id, json) -> {
            try {
                FlailSpec.checkPreset("kind " + id, json, false);
                kinds.put(id.toString(), json);
            } catch (IllegalArgumentException e) {
                bad.put(id, e.getMessage());
                LOG.error("Fundamentum: flail kind {} is broken: {}", id, e.getMessage());
            }
        });
        Map<ResourceLocation, FlailSpec> out = new HashMap<>();
        FLAILS.entries().forEach((item, json) -> {
            try {
                out.put(item, FlailSpec.resolve(json, materials, kinds));
            } catch (IllegalArgumentException e) {
                bad.put(item, e.getMessage());
                LOG.error("Fundamentum: flail {} is broken: {}", item, e.getMessage());
            }
        });
        errors = Map.copyOf(bad);
        return Map.copyOf(out);
    }

    private static void invalidate() {
        specs = null;
        STATES.clear();
        RELOADS.forEach(Runnable::run);
    }

    /** Runs after the flail data reloads (the client uses it to drop its chains). */
    public static void onReload(Runnable r) {
        RELOADS.add(r);
    }

    // ------------------------------------------------------------------------------------------------ the API
    /** Code {@code item}'s flail runs on hits (on top of any named in its {@code on_hit}). */
    public static void hooks(ResourceLocation item, FlailHooks hooks) {
        ITEM_HOOKS.put(item, hooks);
    }

    /** A named hook a flail file can list in {@code "on_hit"}. */
    public static void hook(ResourceLocation name, FlailHooks hooks) {
        NAMED_HOOKS.put(name, hooks);
    }

    private static List<FlailHooks> hooksFor(ResourceLocation item, FlailSpec spec) {
        List<FlailHooks> out = new ArrayList<>(2);
        FlailHooks own = ITEM_HOOKS.get(item);
        if (own != null) {
            out.add(own);
        }
        for (String name : spec.onHit) {
            FlailHooks h = NAMED_HOOKS.get(ResourceLocation.tryParse(name));
            if (h != null) {
                out.add(h);
            }
        }
        return out;
    }

    /** The player's flail now (its chain points, mode), or null when none is in hand. */
    public static @Nullable FlailSim sim(Player player) {
        State s = STATES.get(player.getUUID());
        return s == null ? null : s.sim;
    }

    // ------------------------------------------------------------------------------------------------ the hand
    /** A left-click swing (the fallback when Better Combat isn't loaded). */
    public static boolean swing(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        State s = state(player, stack);
        if (s == null || player.getCooldowns().isOnCooldown(stack.getItem()) || s.sim.mode == FlailSim.Mode.SPIN
            || s.sim.mode == FlailSim.Mode.LAUNCH || s.sim.mode == FlailSim.Mode.REEL) {
            return false;
        }
        startSwing(player, s, false);
        player.getCooldowns().addCooldown(stack.getItem(), s.spec.cooldown);
        return true;
    }

    private static void startSwing(ServerPlayer player, State s, boolean visualOnly) {
        s.side = -s.side;
        s.visualOnly = visualOnly;
        s.lastHit.clear();
        s.sim.swing(s.side);
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(), sound(s.spec.swingSound), SoundSource.PLAYERS, 0.8F,
            (float) (1.1 / Math.sqrt(s.spec.inertia)));
        send(player, s, StatePayload.SWING, s.side);
    }

    /** Use went down or up with a flail in hand. */
    public static void use(ServerPlayer player, boolean down) {
        ItemStack stack = player.getMainHandItem();
        State s = state(player, stack);
        if (s == null) {
            return;
        }
        if (down) {
            s.useDown = !player.getCooldowns().isOnCooldown(stack.getItem()) && s.sim.mode != FlailSim.Mode.LAUNCH && s.sim.mode != FlailSim.Mode.REEL;
            s.useTicks = 0;
            return;
        }
        if (!s.useDown) {
            return;
        }
        s.useDown = false;
        s.lastHit.clear();
        s.slammed = false;
        if (s.sim.mode == FlailSim.Mode.SPIN) {
            s.sim.release();
            send(player, s, StatePayload.RELEASE, (float) s.sim.charge);
            player.getCooldowns().addCooldown(stack.getItem(), s.spec.cooldown);
            player.swing(InteractionHand.MAIN_HAND, true);
        } else if (s.spec.launch != null) {
            s.sim.launch(0);
            send(player, s, StatePayload.LAUNCH, 0);
            player.getCooldowns().addCooldown(stack.getItem(), s.spec.cooldown);
            player.swing(InteractionHand.MAIN_HAND, true);
            player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(), sound(s.spec.swingSound), SoundSource.PLAYERS, 0.8F, 0.8F);
        }
    }

    private static @Nullable State state(ServerPlayer player, ItemStack stack) {
        FlailSpec spec = spec(stack);
        if (spec == null) {
            STATES.remove(player.getUUID());
            return null;
        }
        ResourceLocation item = BuiltInRegistries.ITEM.getKey(stack.getItem());
        State s = STATES.get(player.getUUID());
        if (s == null || !s.item.equals(item) || s.spec != spec) {
            s = new State(item, spec, new FlailSim(spec, vec(Whips.hand(player, 1.0F))));
            STATES.put(player.getUUID(), s);
        }
        return s;
    }

    /** One server tick of a player's flail. Public so a test can drive a fake player, which doesn't tick. */
    public static void tick(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        State s = state(player, stack);
        if (s == null) {
            return;
        }
        FlailSim sim = s.sim;
        if (s.useDown) {
            s.useTicks++;
            if (s.useTicks == HOLD_TICKS) {
                sim.startSpin();
                s.lastHit.clear();
                send(player, s, StatePayload.SPIN, 0);
            }
        }
        // Better Combat's attack: the chain follows it on screen, its hits are Better Combat's.
        boolean swingingNow = player.swinging;
        if (BetterCombat.drivesLeftClick(BETTER_COMBAT) && swingingNow && !s.swinging && (sim.mode == FlailSim.Mode.IDLE || sim.mode == FlailSim.Mode.SWING)) {
            startSwing(player, s, true);
        }
        s.swinging = swingingNow;
        ServerLevel level = player.serverLevel();
        FlailSim.Mode before = sim.mode;
        sim.step(vec(Whips.hand(player, 1.0F)), vec(player.getLookAngle()), Whips.solids(level));
        if (sim.mode == FlailSim.Mode.SPIN && sim.tick % 8 == 0) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS,
                0.25F + 0.35F * (float) sim.chargeNow(), 0.7F + 0.6F * (float) sim.chargeNow());
        }
        if (before.ordinal() != sim.mode.ordinal() && sim.mode == FlailSim.Mode.REEL) {
            s.lastHit.clear();
        }
        if (sim.active() && !(s.visualOnly && sim.mode == FlailSim.Mode.SWING)) {
            hits(player, stack, s);
        }
        blocks(player, stack, s);
        if (s.hooked != null) {
            pullHooked(player, s);
        }
    }

    private static void hits(ServerPlayer player, ItemStack stack, State s) {
        FlailSim sim = s.sim;
        FlailSpec spec = s.spec;
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        for (int h = 0; h < sim.points.length; h++) {
            int n = sim.points[h].length;
            for (int i = spec.chainHits ? 1 : n - 1; i < n; i++) {
                boolean chain = i < n - 1;
                double speed = sim.speed(h, i);
                if (speed < MIN_HIT_SPEED) {
                    continue;
                }
                WhipVec at = sim.points[h][i];
                double r = chain ? Math.min(0.25, spec.impactRadius) : spec.impactRadius;
                Vec3 c = vec(at);
                for (Entity e : level.getEntities(player, new AABB(c, c).inflate(r + 0.5))) {
                    Entity whole = WhipTargets.whole(e);
                    if (!(whole instanceof LivingEntity living) || !living.isAlive() || whole == player || WhipTargets.spared(player, whole)) {
                        continue;
                    }
                    AABB box = e.getBoundingBox();
                    if (!FlailSim.touches(at, r, new double[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ})) {
                        continue;
                    }
                    int key = living.getId() * 8 + h;
                    Long last = s.lastHit.get(key);
                    if (last != null && now - last < REHIT_TICKS) {
                        continue;
                    }
                    s.lastHit.put(key, now);
                    hit(player, stack, s, living, c, h, chain, speed, vec(sim.points[h][i].sub(sim.points[h][Math.max(0, i - 1)])));
                }
            }
        }
    }

    private static void hit(ServerPlayer player, ItemStack stack, State s, LivingEntity living, Vec3 contact, int head, boolean chain, double speed,
                            Vec3 along) {
        ServerLevel level = player.serverLevel();
        FlailSpec spec = s.spec;
        HitResult wall = level.clip(new ClipContext(player.getEyePosition(), contact, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (wall.getType() != HitResult.Type.MISS && wall.getLocation().distanceToSqr(contact) > 0.25) {
            return;
        }
        double mult = spec.speedFactor(speed) * (chain ? 0.5 : 1.0);
        if (s.sim.mode == FlailSim.Mode.SMASH || s.sim.mode == FlailSim.Mode.LAUNCH) {
            mult *= 0.75 + 0.5 * s.sim.charge;
        }
        FlailHit fh = new FlailHit(player, stack, spec, living, contact, head, chain, speed, s.sim.mode, (float) (spec.damage * mult));
        List<FlailHooks> hooks = hooksFor(s.item, spec);
        for (FlailHooks h : hooks) {
            h.hit(fh);
        }
        if (fh.cancelled() || fh.damage <= 0) {
            return;
        }
        DamageSource source = player.damageSources().playerAttack(player);
        float dealt = EnchantmentHelper.modifyDamage(level, stack, living, source, fh.damage);
        DEALING.set(spec);
        boolean hurt;
        try {
            hurt = dealt > 0 && living.hurt(source, dealt);
        } finally {
            DEALING.remove();
        }
        if (!hurt) {
            return;
        }
        EnchantmentHelper.doPostAttackEffectsWithItemSource(level, living, source, stack);
        player.setLastHurtMob(living);
        Vec3 v = s.sim.mode == FlailSim.Mode.LAUNCH ? living.position().subtract(player.position()) : vec(s.sim.headVelocity(head));
        if (v.lengthSqr() < 1.0E-6) {
            v = along;
        }
        double kb = spec.knockback * Math.min(1.25, spec.speedFactor(speed)) * (chain ? 0.5 : 1.0);
        if (kb > 0 && v.horizontalDistanceSqr() > 1.0E-6) {
            living.knockback(kb, -v.x, -v.z);
        }
        if (s.spec.launch != null && s.spec.launch.pull() > 0 && s.sim.mode == FlailSim.Mode.LAUNCH && !chain) {
            s.hooked = living;
        }
        if (stack.isDamageableItem()) {
            stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        }
        level.playSound(null, contact.x, contact.y, contact.z, sound(spec.sound), SoundSource.PLAYERS, chain ? 0.6F : 1.0F,
            (float) (1.2 / Math.sqrt(spec.headMass)));
        level.sendParticles(ParticleTypes.CRIT, contact.x, contact.y, contact.z, 6, 0.15, 0.15, 0.15, 0.2);
    }

    /** Every flail hit (ours, Better Combat's, vanilla's): the material's impact, the wrap and the hooks. */
    private static void landed(LivingDamageEvent.Post event) {
        LivingEntity target = event.getEntity();
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || event.getSource().getDirectEntity() != player
            || event.getNewDamage() <= 0) {
            return;
        }
        ItemStack stack = player.getMainHandItem();
        FlailSpec spec = DEALING.get() != null ? DEALING.get() : spec(stack);
        if (spec == null) {
            return;
        }
        FlailSpec.Impact impact = spec.impact;
        for (FlailSpec.Effect e : impact.effects()) {
            BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.tryParse(e.id())).ifPresent(h ->
                target.addEffect(new MobEffectInstance(h, e.seconds() * 20, e.amplifier()), player));
        }
        if (impact.fireSeconds() > 0) {
            target.igniteForSeconds(impact.fireSeconds());
        }
        if (impact.freezeTicks() > 0 && target.canFreeze()) {
            target.setTicksFrozen(Math.max(target.getTicksFrozen(), target.getTicksRequiredToFreeze() + impact.freezeTicks()));
        }
        if (spec.wrapsTarget > 0) {
            // The chain wraps round it: held in place for a moment.
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, spec.wrapsTarget, 3), player);
        }
        if (!impact.particle().isEmpty() && target.level() instanceof ServerLevel level) {
            ParticleOptions p = particle(impact.particle());
            if (p != null) {
                level.sendParticles(p, target.getX(), target.getY(0.6), target.getZ(), 10, target.getBbWidth() * 0.4, 0.3,
                    target.getBbWidth() * 0.4, 0.05);
            }
        }
        ResourceLocation item = BuiltInRegistries.ITEM.getKey(stack.getItem());
        for (FlailHooks h : hooksFor(item, spec)) {
            h.landed(player, stack, spec, target, event.getNewDamage());
        }
    }

    /** Armor pierce on every flail hit. */
    private static void incoming(LivingIncomingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player) || event.getSource().getDirectEntity() != player) {
            return;
        }
        FlailSpec spec = DEALING.get() != null ? DEALING.get() : spec(player.getMainHandItem());
        if (spec != null && spec.armorPierce > 0) {
            float keep = (float) (1.0 - spec.armorPierce);
            event.addReductionModifier(DamageContainer.Reduction.ARMOR, (container, reduction) -> reduction * keep);
        }
    }

    private static void blocks(ServerPlayer player, ItemStack stack, State s) {
        FlailSim sim = s.sim;
        ServerLevel level = player.serverLevel();
        for (int h = 0; h < sim.points.length; h++) {
            double speed = sim.impact[h];
            if (speed < 0.12) {
                continue;
            }
            BlockPos pos = new BlockPos(sim.impactBlock[h][0], sim.impactBlock[h][1], sim.impactBlock[h][2]);
            BlockState state = level.getBlockState(pos);
            Vec3 at = vec(sim.head(h));
            if (!state.isAir()) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, at.y, at.z, 4 + (int) (speed * 8), 0.1, 0.1, 0.1,
                    0.15);
                level.playSound(null, pos, state.getSoundType().getHitSound(), SoundSource.PLAYERS, (float) Math.min(1.0, speed), 0.8F);
            }
            for (FlailHooks hk : hooksFor(s.item, s.spec)) {
                hk.block(player, stack, s.spec, pos, speed);
            }
            FlailSpec.Slam slam = s.spec.groundSlam;
            if (slam != null && !s.slammed && sim.mode == FlailSim.Mode.SMASH && sim.charge >= slam.minCharge() && pos.getY() < player.getEyeY()) {
                s.slammed = true;
                slam(player, stack, s, new Vec3(at.x, pos.getY() + 1.0, at.z));
            }
        }
    }

    /** A ground slam at {@code at}: everything within the radius is hurt (less toward the edge) and thrown up. */
    private static List<LivingEntity> slam(ServerPlayer player, ItemStack stack, State s, Vec3 at) {
        FlailSpec spec = s.spec;
        FlailSpec.Slam slam = spec.groundSlam;
        ServerLevel level = player.serverLevel();
        double radius = slam.radius() * (0.6 + 0.4 * s.sim.charge);
        float strength = (float) (0.5 + 0.5 * s.sim.charge);
        List<LivingEntity> hit = new ArrayList<>();
        DamageSource source = player.damageSources().playerAttack(player);
        for (Entity e : level.getEntities(player, new AABB(at, at).inflate(radius, 2.0, radius))) {
            Entity whole = WhipTargets.whole(e);
            if (!(whole instanceof LivingEntity living) || hit.contains(living) || whole == player || !living.isAlive()
                || WhipTargets.spared(player, whole)) {
                continue;
            }
            Vec3 off = living.position().subtract(at);
            double dist = off.horizontalDistance();
            if (dist > radius || Math.abs(off.y) > 2.5) {
                continue;
            }
            float amount = (float) (slam.damage() * strength * (1.0 - 0.5 * dist / radius));
            float dealt = EnchantmentHelper.modifyDamage(level, stack, living, source, amount);
            DEALING.set(spec);
            try {
                if (dealt > 0 && living.hurt(source, dealt)) {
                    hit.add(living);
                    double resist = 1.0 - living.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
                    Vec3 out = dist < 1.0E-3 ? Vec3.ZERO : new Vec3(off.x / dist, 0, off.z / dist);
                    living.push(out.x * 0.6 * strength * resist, 0.45 * strength * resist, out.z * 0.6 * strength * resist);
                    living.hurtMarked = true;
                }
            } finally {
                DEALING.remove();
            }
        }
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24;
            double x = at.x + Math.cos(a) * radius * 0.7, z = at.z + Math.sin(a) * radius * 0.7;
            BlockState ground = level.getBlockState(BlockPos.containing(x, at.y - 0.5, z));
            if (!ground.isAir()) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), x, at.y + 0.1, z, 3, 0.2, 0.1, 0.2, 0.2);
            }
        }
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3, at.z, 1, 0, 0, 0, 0);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.MACE_SMASH_GROUND, SoundSource.PLAYERS, 0.6F + 0.4F * strength, 0.9F);
        if (!hit.isEmpty() && stack.isDamageableItem()) {
            stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        }
        for (FlailHooks h : hooksFor(s.item, spec)) {
            h.slam(player, stack, spec, at, hit);
        }
        return hit;
    }

    /** A thrown head that caught something drags it in as the chain reels back. */
    private static void pullHooked(ServerPlayer player, State s) {
        LivingEntity t = s.hooked;
        if (t == null || !t.isAlive() || t.level() != player.serverLevel() || s.sim.mode == FlailSim.Mode.IDLE || s.spec.launch == null) {
            s.hooked = null;
            return;
        }
        if (s.sim.mode != FlailSim.Mode.REEL) {
            return;
        }
        Vec3 d = player.position().subtract(t.position());
        double dist = d.horizontalDistance();
        if (dist < 1.5) {
            s.hooked = null;
            return;
        }
        double resist = 1.0 - t.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
        double k = Math.min(s.spec.launch.pull(), dist * 0.25) * resist;
        t.setDeltaMovement(d.x / dist * k, Math.max(t.getDeltaMovement().y, 0.08), d.z / dist * k);
        t.hurtMarked = true;
    }

    // ------------------------------------------------------------------------------------------------ setup
    public static void init(IEventBus modBus) {
        FLAILS.onLoad(t -> invalidate());
        MATERIALS.onLoad(t -> invalidate());
        KINDS.onLoad(t -> invalidate());
        Triggers.register(stack -> !BetterCombat.drivesLeftClick(BETTER_COMBAT) && spec(stack) != null, new Triggers.Handler() {
            @Override
            public void press(ServerPlayer player, ItemStack stack) {
                swing(player);
            }

            @Override
            public void hold(ServerPlayer player, ItemStack stack, int ticks) {
                swing(player);
            }
        });
        modBus.addListener(Flails::payloads);
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                tick(p);
            }
        });
        NeoForge.EVENT_BUS.addListener(Flails::incoming);
        NeoForge.EVENT_BUS.addListener(Flails::landed);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> STATES.remove(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> STATES.remove(e.getEntity().getUUID()));
        com.fundamentum.scythe.Scythes.init(modBus);
        // The example flails and scythe: FlailExamples is in src/flailtest, built only with -Pflailtest.
        // It never ships: Fundamentum is a library and registers no items of its own.
        try {
            Class.forName("com.fundamentum.flail.FlailExamples").getMethod("init", IEventBus.class).invoke(null, modBus);
        } catch (ClassNotFoundException absent) {
            // a normal build
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        if (!FMLEnvironment.production) {
            FlailSelfTest.init();
        }
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToServer(UsePayload.TYPE, UsePayload.STREAM_CODEC, UsePayload::handle);
        r.playToClient(StatePayload.TYPE, StatePayload.STREAM_CODEC, StatePayload::handle);
    }

    private static void send(ServerPlayer player, State s, int action, float value) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new StatePayload(player.getId(), s.item, action, value));
    }

    /** Client to server: use went down or up with a flail in hand. */
    public record UsePayload(boolean down) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UsePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("fundamentum", "flail_use"));
        public static final StreamCodec<ByteBuf, UsePayload> STREAM_CODEC = ByteBufCodecs.BOOL.map(UsePayload::new, UsePayload::down);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(UsePayload payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                use(player, payload.down());
            }
        }
    }

    /** Server to clients: a player's flail swung (value: side), spun, was let go (value: charge) or thrown (value: charge). */
    public record StatePayload(int entity, ResourceLocation item, int action, float value) implements CustomPacketPayload {
        public static final int SWING = 0, SPIN = 1, RELEASE = 2, LAUNCH = 3;
        public static final CustomPacketPayload.Type<StatePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("fundamentum", "flail_state"));
        public static final StreamCodec<ByteBuf, StatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StatePayload::entity, ResourceLocation.STREAM_CODEC, StatePayload::item, ByteBufCodecs.VAR_INT, StatePayload::action,
            ByteBufCodecs.FLOAT, StatePayload::value, StatePayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(StatePayload payload, IPayloadContext context) {
            FlailsClient.state(payload.entity(), payload.item(), payload.action(), payload.value());
        }
    }

    // ------------------------------------------------------------------------------------------------ small helpers
    static WhipVec vec(Vec3 v) {
        return new WhipVec(v.x, v.y, v.z);
    }

    static Vec3 vec(WhipVec v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    public static SoundEvent sound(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return SoundEvents.PLAYER_ATTACK_STRONG;
        }
        return BuiltInRegistries.SOUND_EVENT.getOptional(key).orElseGet(() -> SoundEvent.createVariableRangeEvent(key));
    }

    /** A plain particle by id, or null (particles that need options can't be named in data). */
    public static @Nullable ParticleOptions particle(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return null;
        }
        return BuiltInRegistries.PARTICLE_TYPE.getOptional(key).filter(t -> t instanceof SimpleParticleType)
            .map(t -> (ParticleOptions) (SimpleParticleType) t).orElse(null);
    }
}
