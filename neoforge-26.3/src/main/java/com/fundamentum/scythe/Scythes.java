package com.fundamentum.scythe;

import com.fundamentum.compat.BetterCombat;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.fundamentum.flail.Flails;
import com.fundamentum.whip.WhipTargets;
import com.fundamentum.whip.WhipVec;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jspecify.annotations.Nullable;

/**
 * Scythes: an item with a file in {@code data/<ns>/fundamentum/scythes/} and the moves it lists (see {@link ScytheSpec}
 * and the README). Left click stays the item's own attack (Better Combat's when it is loaded); the sweet spot, the
 * backswing and harvested souls change those hits, and the whirl, reap pull and crescent wave go on use or sneak + use.
 * A broken file (two moves on one input or a bad number) is named in the log and left out.
 */
public final class Scythes {
    /** {@code #fundamentum:scythes}. */
    public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("fundamentum", "scythes"));
    /** The scythe type as Better Combat sees it: when it is loaded it plays the left click (the backswing steps aside). */
    public static final BetterCombat.WeaponType BETTER_COMBAT = BetterCombat.register("scythe", TAG,
        Identifier.fromNamespaceAndPath("fundamentum", "scythe"), BetterCombat.LeftClick.BETTER_COMBAT);
    public static final DataTable<ScytheSpec> SCYTHES = DataTables.register(Identifier.fromNamespaceAndPath("fundamentum", "scythes"),
        "fundamentum/scythes", ScytheSpec::parse).keyField("item").synced();
    private static final Identifier WHIRL_SLOW = Identifier.fromNamespaceAndPath("fundamentum", "scythe_whirl");

    private static final class State {
        boolean whirling;
        int whirlTicks;
        final Map<String, Long> ready = new HashMap<>();
        int souls;
        long soulsUntil;
        long lastAttack = Long.MIN_VALUE / 2;
    }

    private record Wave(ServerPlayer player, ItemStack stack, ScytheSpec spec, WhipVec start, WhipVec dir, Set<Integer> hit, int[] tick) {}

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final List<Wave> WAVES = new ArrayList<>();
    /** Set around our own hurt calls, so the sweet spot (a left-click thing) leaves them alone. */
    private static final ThreadLocal<Boolean> OURS = new ThreadLocal<>();

    private Scythes() {}

    public static @Nullable ScytheSpec spec(ItemStack stack) {
        return stack.isEmpty() ? null : SCYTHES.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** Souls the player holds now (harvest souls). */
    public static int souls(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        return s == null ? 0 : s.souls;
    }

    private static State state(ServerPlayer player) {
        return STATES.computeIfAbsent(player.getUUID(), k -> new State());
    }

    // ------------------------------------------------------------------------------------------------ use
    /** Use went down or up with a scythe in hand. Public so a test can drive a fake player. */
    public static void use(ServerPlayer player, boolean down, boolean sneak) {
        ItemStack stack = player.getMainHandItem();
        ScytheSpec spec = spec(stack);
        State s = state(player);
        if (!down) {
            if (s.whirling) {
                stopWhirl(player, s, spec);
            }
            return;
        }
        if (spec == null) {
            return;
        }
        String move = spec.moveOn(sneak ? "sneak_use" : "use");
        if (move == null || !ready(player, s, move)) {
            return;
        }
        switch (move) {
            case "whirl" -> {
                s.whirling = true;
                s.whirlTicks = 0;
                AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
                if (speed != null && spec.whirl.slow() > 0) {
                    speed.removeModifier(WHIRL_SLOW);
                    speed.addTransientModifier(new AttributeModifier(WHIRL_SLOW, -spec.whirl.slow(), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                }
            }
            case "reap_pull" -> reapPull(player, stack, spec, s);
            case "crescent_wave" -> wave(player, stack, spec, s);
            default -> {}
        }
    }

    private static boolean ready(ServerPlayer player, State s, String move) {
        Long at = s.ready.get(move);
        return at == null || player.level().getGameTime() >= at;
    }

    private static void cooldown(ServerPlayer player, State s, String move, int ticks) {
        s.ready.put(move, player.level().getGameTime() + ticks);
    }

    private static void stopWhirl(ServerPlayer player, State s, @Nullable ScytheSpec spec) {
        s.whirling = false;
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(WHIRL_SLOW);
        }
        if (spec != null && spec.whirl != null) {
            cooldown(player, s, "whirl", spec.whirl.cooldown());
        }
    }

    private static void reapPull(ServerPlayer player, ItemStack stack, ScytheSpec spec, State s) {
        ScytheSpec.ReapPull r = spec.reapPull;
        ServerLevel level = player.level();
        WhipVec eye = vec(player.getEyePosition()), look = vec(player.getLookAngle());
        for (LivingEntity t : around(player, r.range())) {
            if (!ScytheMath.inArc(eye, look, vec(t.getBoundingBox().getCenter()), r.range(), r.arc()) || !seen(player, t)) {
                continue;
            }
            double resist = 1.0 - t.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
            WhipVec v = ScytheMath.pull(vec(player.position()), vec(t.position()), r.pull(), r.range()).scale(resist);
            // The cut first: its knockback would push the target back out and undo the pull.
            if (r.damage() > 0) {
                hit(player, stack, spec, t, r.damage());
            }
            t.setDeltaMovement(v.x(), v.y(), v.z());
            t.syncVelocity = true;
        }
        player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 0.6F);
        Vec3 c = player.getEyePosition().add(player.getLookAngle().scale(Math.min(3.0, r.range())));
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y - 0.3, c.z, 1, 0, 0, 0, 0);
        cooldown(player, s, "reap_pull", r.cooldown());
    }

    private static void wave(ServerPlayer player, ItemStack stack, ScytheSpec spec, State s) {
        WhipVec look = vec(player.getLookAngle());
        WhipVec start = vec(player.getEyePosition().add(0, -0.4, 0)).addScaled(look, 0.8);
        WAVES.add(new Wave(player, stack.copy(), spec, start, look, new HashSet<>(), new int[1]));
        player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F,
            1.3F);
        cooldown(player, s, "crescent_wave", spec.crescentWave.cooldown());
    }

    private static void tickWaves() {
        Iterator<Wave> it = WAVES.iterator();
        while (it.hasNext()) {
            Wave w = it.next();
            ScytheSpec.CrescentWave cw = w.spec().crescentWave;
            int tick = ++w.tick()[0];
            WhipVec at = ScytheMath.wave(cw, w.start(), w.dir(), tick);
            ServerPlayer player = w.player();
            if (at == null || player.isRemoved() || cw == null) {
                it.remove();
                continue;
            }
            ServerLevel level = player.level();
            Vec3 c = new Vec3(at.x(), at.y(), at.z());
            BlockPos pos = BlockPos.containing(c);
            if (!level.isLoaded(pos) || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                it.remove();
                continue;
            }
            ParticleOptions p = cw.particle().isEmpty() ? null : Flails.particle(cw.particle());
            level.sendParticles(p == null ? ParticleTypes.SWEEP_ATTACK : p, c.x, c.y, c.z, 2, cw.width() * 0.3, 0.1,
                cw.width() * 0.3, 0.0);
            double half = cw.width() * 0.5;
            boolean stop = false;
            for (Entity e : level.getEntities(player, new AABB(c, c).inflate(half, 1.0, half))) {
                Entity whole = WhipTargets.whole(e);
                if (!(whole instanceof LivingEntity t) || !t.isAlive() || whole == player || WhipTargets.spared(player, whole)
                    || !w.hit().add(t.getId())) {
                    continue;
                }
                hit(player, w.stack(), w.spec(), t, cw.damage());
                if (w.hit().size() >= cw.pierce()) {
                    stop = true;
                    break;
                }
            }
            if (stop) {
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ ticks
    /** One server tick of a player's scythe. Public so a test can drive a fake player, which doesn't tick. */
    public static void tick(ServerPlayer player) {
        State s = STATES.get(player.getUUID());
        if (s == null) {
            return;
        }
        ItemStack stack = player.getMainHandItem();
        ScytheSpec spec = spec(stack);
        long now = player.level().getGameTime();
        if (s.souls > 0 && now >= s.soulsUntil) {
            s.souls--;
            s.soulsUntil = now + (spec != null && spec.harvestSouls != null ? spec.harvestSouls.decayTicks() : 100);
        }
        if (!s.whirling) {
            return;
        }
        if (spec == null || spec.whirl == null) {
            stopWhirl(player, s, null);
            return;
        }
        ScytheSpec.Whirl w = spec.whirl;
        s.whirlTicks++;
        ServerLevel level = player.level();
        if (s.whirlTicks % 4 == 0) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS,
                0.4F + 0.4F * (float) (s.whirlTicks / (double) Math.max(1, w.buildTicks())), 0.8F);
        }
        if (ScytheMath.whirlHits(w, s.whirlTicks)) {
            double mult = ScytheMath.whirl(w, s.whirlTicks);
            for (LivingEntity t : around(player, w.radius())) {
                if (seen(player, t)) {
                    hit(player, stack, spec, t, mult);
                }
            }
            double a = s.whirlTicks * 0.9;
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, player.getX() + Math.cos(a) * w.radius() * 0.6, player.getY(0.5),
                player.getZ() + Math.sin(a) * w.radius() * 0.6, 1, 0, 0, 0, 0);
        }
        if (w.maxTicks() > 0 && s.whirlTicks >= w.maxTicks()) {
            stopWhirl(player, s, spec);
        }
    }

    /** Creatures within {@code r} of the player that a scythe may hit. */
    private static List<LivingEntity> around(ServerPlayer player, double r) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : player.level().getEntities(player, player.getBoundingBox().inflate(r))) {
            Entity whole = WhipTargets.whole(e);
            if (whole instanceof LivingEntity t && t.isAlive() && whole != player && !WhipTargets.spared(player, whole) && !out.contains(t)
                && t.getBoundingBox().getCenter().distanceTo(player.position().add(0, player.getBbHeight() * 0.5, 0)) <= r + t.getBbWidth()) {
                out.add(t);
            }
        }
        return out;
    }

    private static boolean seen(ServerPlayer player, LivingEntity t) {
        Vec3 to = t.getBoundingBox().getCenter();
        HitResult wall = player.level().clip(new ClipContext(player.getEyePosition(), to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return wall.getType() == HitResult.Type.MISS;
    }

    /** One of the scythe's own hits, for {@code mult} times its damage (souls on top). */
    private static void hit(ServerPlayer player, ItemStack stack, ScytheSpec spec, LivingEntity t, double mult) {
        ServerLevel level = player.level();
        double amount = spec.damage * mult;
        if (spec.harvestSouls != null) {
            amount *= ScytheMath.souls(spec.harvestSouls, souls(player));
        }
        DamageSource source = player.damageSources().playerAttack(player);
        float dealt = EnchantmentHelper.modifyDamage(level, stack, t, source, (float) amount);
        OURS.set(Boolean.TRUE);
        boolean hurt;
        try {
            hurt = dealt > 0 && t.hurtServer(level, source, dealt);
        } finally {
            OURS.remove();
        }
        if (hurt) {
            EnchantmentHelper.doPostAttackEffectsWithItemSource(level, t, source, stack);
            player.setLastHurtMob(t);
            if (stack == player.getMainHandItem() && stack.isDamageableItem()) {
                stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ left click
    /** The sweet spot and souls on a left-click hit with a scythe (also Better Combat's). */
    private static void incoming(LivingIncomingDamageEvent event) {
        if (OURS.get() != null || !(event.getSource().getEntity() instanceof ServerPlayer player) || event.getSource().getDirectEntity() != player) {
            return;
        }
        ScytheSpec spec = spec(player.getMainHandItem());
        if (spec == null) {
            return;
        }
        double mult = 1.0;
        if (spec.sweetSpot != null) {
            AABB box = event.getEntity().getBoundingBox();
            Vec3 eye = player.getEyePosition();
            Vec3 near = new Vec3(Math.clamp(eye.x, box.minX, box.maxX), Math.clamp(eye.y, box.minY, box.maxY), Math.clamp(eye.z, box.minZ, box.maxZ));
            double m = ScytheMath.sweetSpot(spec.sweetSpot, near.distanceTo(eye));
            if (m > 1.0) {
                LivingEntity t = event.getEntity();
                player.level().playSound(null, t.getX(), t.getY(), t.getZ(), spec.sweetSpot.sound().isEmpty() ? SoundEvents.PLAYER_ATTACK_CRIT
                    : Flails.sound(spec.sweetSpot.sound()), SoundSource.PLAYERS, 1.0F, 1.2F);
                player.level().sendParticles(ParticleTypes.CRIT, t.getX(), t.getY(0.6), t.getZ(), 10, 0.3, 0.3, 0.3, 0.2);
            }
            mult *= m;
        }
        if (spec.harvestSouls != null) {
            mult *= ScytheMath.souls(spec.harvestSouls, souls(player));
        }
        if (mult != 1.0) {
            event.setAmount((float) (event.getAmount() * mult));
        }
    }

    /** Without Better Combat: a second left click soon after the first is the backswing, a sweep across the arc. */
    private static void attack(AttackEntityEvent event) {
        if (BetterCombat.drivesLeftClick(BETTER_COMBAT) || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = player.getMainHandItem();
        ScytheSpec spec = spec(stack);
        if (spec == null || spec.backswing == null) {
            return;
        }
        State s = state(player);
        if (s.whirling) {
            return;
        }
        long now = player.level().getGameTime();
        if (now - s.lastAttack > spec.backswing.window()) {
            s.lastAttack = now;
            return;
        }
        s.lastAttack = Long.MIN_VALUE / 2;
        event.setCanceled(true);
        backswing(player, stack, spec);
    }

    /** The backswing: everything in its arc within reach takes {@code backswing.damage} times the scythe's damage. */
    public static int backswing(ServerPlayer player, ItemStack stack, ScytheSpec spec) {
        WhipVec eye = vec(player.getEyePosition()), look = vec(player.getLookAngle());
        int n = 0;
        for (LivingEntity t : around(player, spec.reach + 1)) {
            if (ScytheMath.inArc(eye, look, vec(t.getBoundingBox().getCenter()), spec.reach + t.getBbWidth() * 0.5, spec.backswing.arc())
                && seen(player, t)) {
                hit(player, stack, spec, t, spec.backswing.damage());
                n++;
            }
        }
        player.swing(InteractionHand.OFF_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
        ServerLevel level = player.level();
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 0.8F);
        Vec3 c = player.getEyePosition().add(player.getLookAngle().scale(1.5));
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y - 0.3, c.z, 1, 0, 0, 0, 0);
        return n;
    }

    /** Harvest souls: a kill with the scythe in hand gathers a soul and heals a little. */
    private static void death(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ScytheSpec spec = spec(player.getMainHandItem());
        if (spec == null || spec.harvestSouls == null) {
            return;
        }
        ScytheSpec.HarvestSouls h = spec.harvestSouls;
        State s = state(player);
        s.souls = Math.min(h.maxSouls(), s.souls + 1);
        s.soulsUntil = player.level().getGameTime() + h.decayTicks();
        if (h.healPerSoul() > 0) {
            player.heal((float) h.healPerSoul());
        }
        LivingEntity dead = event.getEntity();
        player.level().sendParticles(ParticleTypes.SOUL, dead.getX(), dead.getY(0.5), dead.getZ(), 8, 0.3, 0.4, 0.3, 0.04);
        player.level().playSound(null, dead.getX(), dead.getY(), dead.getZ(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    // ------------------------------------------------------------------------------------------------ setup
    public static void init(IEventBus modBus) {
        modBus.addListener(Scythes::payloads);
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                tick(p);
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> tickWaves());
        NeoForge.EVENT_BUS.addListener(Scythes::incoming);
        NeoForge.EVENT_BUS.addListener(Scythes::attack);
        NeoForge.EVENT_BUS.addListener(Scythes::death);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                stopWhirl(p, state(p), null);
            }
            STATES.remove(e.getEntity().getUUID());
            WAVES.removeIf(w -> w.player() == e.getEntity());
        });
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(UsePayload.TYPE, UsePayload.STREAM_CODEC, UsePayload::handle);
    }

    /** Client to server: use went down or up with a scythe in hand, sneaking or not. */
    public record UsePayload(boolean down, boolean sneak) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UsePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("fundamentum", "scythe_use"));
        public static final StreamCodec<ByteBuf, UsePayload> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, UsePayload::down,
            ByteBufCodecs.BOOL, UsePayload::sneak, UsePayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(UsePayload payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                use(player, payload.down(), payload.sneak());
            }
        }
    }

    static WhipVec vec(Vec3 v) {
        return new WhipVec(v.x, v.y, v.z);
    }
}
