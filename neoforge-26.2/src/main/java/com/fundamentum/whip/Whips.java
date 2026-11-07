/*
 * The server swing and hit loop is adapted from Better Whips (https://github.com/my2167592261-cell/Better-Whips), whose
 * code is under the MIT License:
 *
 * Copyright (c) 2026 my2167592261-cell
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.fundamentum.whip;

import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.fundamentum.trigger.Triggers;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Cord whips: a physically simulated cord, hit-tested along its whole moving length (not a raycast), so the reach is
 * long but hard to aim; the tip cracks into crits. The cord lies on and slides along blocks, and never hits through one. Ideas and maths from Better Whips (MIT), made into one shared system:
 * a mod registers a {@link WhipDefinition} (numbers, look, hooks) for an item and gets the swing, the hits, the network
 * sync and the drawn cord.
 * <pre>
 * Whips.register(MyItems.THORN_WHIP.get(), WhipDefinition.builder(id("thorn_whip")).tuning(t -> t.length(5.5)).build());
 * </pre>
 * Register while the mod is constructed (the client reads the same list). Left click swings ({@link Triggers}); holding
 * it swings again as soon as the cooldown allows. The server simulates the swing and deals every hit; clients run the
 * same swing for drawing only. Damage is the tuning's, plus the player's attack damage above 1 (Strength, the item's
 * own attack modifiers), so give whip items no attack damage modifiers beyond what they should add.
 */
public final class Whips {
    private static final Map<Identifier, WhipDefinition> REGISTERED = new ConcurrentHashMap<>();

    /** Data overrides and data-only whips, by item id (see {@link WhipDefinition}). */
    public static final DataTable<JsonObject> WHIPS = DataTables
        .registerJson(Identifier.fromNamespaceAndPath("fundamentum", "whips"), "fundamentum/whips")
        .keyField("item")
        .synced();

    /** Resolved definitions, rebuilt when a data entry changes (by identity). */
    private record Resolved(@Nullable JsonObject data, WhipDefinition def) {}
    private static final Map<Identifier, Resolved> RESOLVED = new ConcurrentHashMap<>();

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("fundamentum");
    /** The example whip, registered in development runs only (it shows the API; players never see it). */
    public static final @Nullable DeferredItem<Item> EXAMPLE_WHIP = FMLEnvironment.isProduction() ? null
        : ITEMS.registerSimpleItem("example_whip", p -> p.stacksTo(1));

    /** Makes {@code item} a whip. */
    public static void register(Item item, WhipDefinition whip) {
        register(BuiltInRegistries.ITEM.getKey(item), whip);
    }

    /** Makes the item with this id a whip (usable before items are registered). */
    public static void register(Identifier item, WhipDefinition whip) {
        REGISTERED.put(item, whip);
        RESOLVED.remove(item);
    }

    /** The whip definition of a stack, or null when it is not a whip. */
    public static @Nullable WhipDefinition definition(ItemStack stack) {
        return stack.isEmpty() ? null : definition(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** The whip definition of an item id (code, with its data laid over), or null. */
    public static @Nullable WhipDefinition definition(Identifier item) {
        WhipDefinition code = REGISTERED.get(item);
        JsonObject data = WHIPS.get(item);
        if (code == null && data == null) {
            return null;
        }
        Resolved r = RESOLVED.get(item);
        if (r == null || r.data != data) {
            WhipDefinition base = code != null ? code : WhipDefinition.builder(item).build();
            r = new Resolved(data, data == null ? base : base.withJson(data));
            RESOLVED.put(item, r);
        }
        return r.def;
    }

    // ------------------------------------------------------------------------------------------------ the hand
    /** About where a held whip's handle is: in front of the main-hand shoulder, below the eyes. Both sides use it. */
    public static Vec3 hand(Player player, float partialTicks) {
        float yaw = Mth.lerp(partialTicks, player.yRotO, player.getYRot()) * Mth.DEG_TO_RAD;
        double s = player.getScale();
        double side = (player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1) * 0.38 * s;
        double forward = 0.35 * s;
        return player.getEyePosition(partialTicks)
            .add(-Mth.cos(yaw) * side - Mth.sin(yaw) * forward, -0.45 * s, -Mth.sin(yaw) * side + Mth.cos(yaw) * forward);
    }

    /** No block here, in the {@link #solids} cache. */
    private static final double[] OPEN = new double[0];

    /**
     * The level's blocks for one tick of cord physics: each block's collision bounds, looked up once. Make a new one
     * every tick (blocks change).
     */
    public static WhipCord.Solids solids(BlockGetter level) {
        Map<Long, double[]> seen = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        return (x, y, z) -> {
            double[] box = seen.computeIfAbsent(BlockPos.asLong(x, y, z), k -> {
                pos.set(x, y, z);
                VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
                if (shape.isEmpty()) {
                    return OPEN;
                }
                AABB b = shape.bounds();
                return new double[] {x + b.minX, y + b.minY, z + b.minZ, x + b.maxX, y + b.maxY, z + b.maxZ};
            });
            return box.length == 0 ? null : box;
        };
    }

    static WhipVec vec(Vec3 v) {
        return new WhipVec(v.x, v.y, v.z);
    }

    static Vec3 vec(WhipVec v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    // ------------------------------------------------------------------------------------------------ swinging (server)
    private static final class Active {
        final WhipDefinition whip;
        final ItemStack stack;
        final Identifier item;
        final WhipSwing swing;
        final Set<UUID> hit = new HashSet<>();
        Vec3 hand;
        // Per strand (1.22.1): each strand of a many-stranded whip cracks and strikes on its own.
        final boolean[] cracked, struck;
        boolean swung, stopped, wore;

        Active(WhipDefinition whip, ItemStack stack, WhipSwing swing, Vec3 hand) {
            this.whip = whip;
            this.stack = stack;
            this.item = BuiltInRegistries.ITEM.getKey(stack.getItem());
            this.swing = swing;
            this.hand = hand;
            cracked = new boolean[swing.cords.length];
            struck = new boolean[swing.cords.length];
        }
    }

    private static final Map<UUID, Active> ACTIVE = new ConcurrentHashMap<>();
    /** Each player's next swing side, alternating. */
    private static final Map<UUID, Integer> SIDE = new ConcurrentHashMap<>();

    /** True while the player is mid-swing. */
    public static boolean swinging(Player player) {
        return ACTIVE.containsKey(player.getUUID());
    }

    /**
     * Swings the whip in the player's main hand, as a left click does: false when it is not a whip or is cooling down.
     * Public for scripted use and tests.
     */
    public static boolean swing(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        WhipDefinition whip = definition(stack);
        if (whip == null || player.getCooldowns().isOnCooldown(stack) || !player.isAlive() || player.isSpectator()) {
            return false;
        }
        end(player);
        int side = SIDE.merge(player.getUUID(), -1, (a, b) -> -a);
        Vec3 hand = hand(player, 1.0F);
        Vec3[] aims = aims(player, whip, hand);
        WhipVec[] looks = new WhipVec[aims.length];
        List<Vector3fc> sent = new ArrayList<>();
        for (int k = 0; k < aims.length; k++) {
            looks[k] = vec(aims[k]);
            sent.add(new Vector3f((float) aims[k].x, (float) aims[k].y, (float) aims[k].z));
        }
        WhipSwing swing = new WhipSwing(whip.tuning, looks, side);
        swing.start(vec(hand), solids(player.level()));
        ACTIVE.put(player.getUUID(), new Active(whip, stack, swing, hand));
        player.getCooldowns().addCooldown(stack, whip.tuning.cooldownTicks());
        player.resetAttackStrengthTicker();
        player.swing(InteractionHand.MAIN_HAND, true);
        whip.hooks.swingStart(player, stack, whip);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
            new SwingPayload(player.getId(), BuiltInRegistries.ITEM.getKey(stack.getItem()), sent, side));
        return true;
    }

    /**
     * Each strand's aim at the start of a swing: the look, or with {@link WhipDefinition#homingDegrees} the way from the
     * hand to the hostile nearest the crosshair within that cone and the cord's reach, in view; with
     * {@link WhipDefinition#strandTargets} each strand to its own (nearest first), the rest keeping the look.
     */
    static Vec3[] aims(ServerPlayer player, WhipDefinition whip, Vec3 hand) {
        Vec3 look = player.getLookAngle();
        Vec3 eye = player.getEyePosition();
        double reach = whip.tuning.length() + WhipSwing.HANDLE_REACH;
        int strands = Math.max(1, whip.tuning.strands());
        Vec3[] aims = new Vec3[whip.strandTargets ? strands : 1];
        java.util.Arrays.fill(aims, crosshairAim(player, hand, eye, look, reach));
        if (whip.homingDegrees <= 0) {
            return aims;
        }
        double cos = Math.cos(Math.toRadians(whip.homingDegrees));
        List<Entity> found = new ArrayList<>(player.level().getEntities(player, new AABB(hand, hand).inflate(reach + 1.0), e ->
            e instanceof LivingEntity && WhipTargets.touchable(player, e) && WhipTargets.isHostile(e) && !WhipTargets.spared(player, e)));
        found.removeIf(e -> {
            Vec3 c = e.getBoundingBox().getCenter();
            Vec3 to = c.subtract(eye);
            return e.getBoundingBox().distanceToSqr(hand) > reach * reach || to.lengthSqr() < 1.0E-6
                || to.normalize().dot(look) < cos || !player.hasLineOfSight(e);
        });
        found.sort(java.util.Comparator.comparingDouble(e -> -e.getBoundingBox().getCenter().subtract(eye).normalize().dot(look)));
        for (int k = 0; k < aims.length && k < found.size(); k++) {
            Vec3 to = found.get(k).getBoundingBox().getCenter().subtract(hand);
            aims[k] = to.normalize();
        }
        return aims;
    }

    /**
     * The way from the hand to what the crosshair is on (the first entity or block along the look within the cord's
     * reach, else the point at full reach). The hand is below and beside the eyes, so swinging along the look itself
     * (as before 1.22.3) put the cord's line half a block off the crosshair, and the tilted swing plane pushed it further
     * aside below hand height: a short mob dead ahead was missed on every other swing.
     */
    static Vec3 crosshairAim(ServerPlayer player, Vec3 hand, Vec3 eye, Vec3 look, double reach) {
        Vec3 end = eye.add(look.scale(reach));
        HitResult block = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.MISS) {
            end = block.getLocation();
        }
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(player, eye, end, player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0),
            e -> WhipTargets.touchable(player, e), eye.distanceToSqr(end));
        if (entity != null) {
            end = entity.getLocation();
        }
        Vec3 to = end.subtract(hand);
        // Something right at the hand gives no useful way to swing: keep the look.
        return to.lengthSqr() < 0.25 || to.dot(look) <= 0 ? look : to.normalize();
    }

    /** Ends the player's swing now (no more hits). */
    public static void end(ServerPlayer player) {
        Active a = ACTIVE.remove(player.getUUID());
        if (a != null) {
            a.whip.hooks.swingEnd(player, a.stack, a.whip, a.hit.size());
        }
    }

    private static void playerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            tick(player);
        }
    }

    /** One tick of a player's swing (players tick it themselves; a fake player's caller does). */
    static void tick(ServerPlayer player) {
        Active a = ACTIVE.get(player.getUUID());
        if (a == null) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (!player.isAlive() || held.isEmpty() || !BuiltInRegistries.ITEM.getKey(held.getItem()).equals(a.item)) {
            end(player);
            return;
        }
        ServerLevel level = player.level();
        WhipTuning t = a.whip.tuning;
        Vec3 hand = hand(player, 1.0F);
        // Everything the cord could reach this tick, found once; each step then tests its swept segments against them.
        AABB reach = new AABB(hand, hand).inflate(t.length() + 2.0);
        List<Entity> near = level.getEntities(player, reach, e -> WhipTargets.touchable(player, e));
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        boolean more = a.swing.tick(vec(a.hand), vec(hand), solids(level), (strand, cord, tick, dt) -> {
            if (!a.swung && tick > t.windupTicks()) {
                a.swung = true;
                if (a.whip.swingSound != null) {
                    level.playSound(null, player.getX(), player.getY(), player.getZ(), a.whip.swingSound, SoundSource.PLAYERS, 0.8F, 1.5F);
                }
            }
            double tipSpeed = cord.tipSpeed(dt);
            // The crack is the tip's speed peak once it flies forward (WhipSwing.crack), out in front, not the backswing.
            if (a.swing.crack(strand, cord, tick, dt)) {
                // The hook hears the swing's first crack only; every strand's crack is heard (later ones a little quieter).
                boolean first = !any(a.cracked);
                a.cracked[strand] = true;
                Vec3 tip = vec(cord.tip());
                if (a.whip.crackSound != null) {
                    level.playSound(null, tip.x, tip.y, tip.z, a.whip.crackSound, SoundSource.PLAYERS, first ? 1.0F : 0.6F,
                        1.8F + 0.08F * strand);
                }
                if (first) {
                    a.whip.hooks.crack(player, a.stack, a.whip, tip);
                }
            }
            if (!a.struck[strand] && tick > t.windupTicks() && cord.tipBlock != null && cord.tipImpact / dt >= t.crackSpeed() * WhipCord.STRIKE_SPEED) {
                a.struck[strand] = true;
                BlockPos pos = new BlockPos(cord.tipBlock[0], cord.tipBlock[1], cord.tipBlock[2]);
                Vec3 tip = vec(cord.tip());
                BlockState state = level.getBlockState(pos);
                level.playSound(null, tip.x, tip.y, tip.z, state.getSoundType().getHitSound(), SoundSource.PLAYERS, 0.9F, 1.4F);
                if (a.whip.crackSound != null) {
                    level.playSound(null, tip.x, tip.y, tip.z, a.whip.crackSound, SoundSource.PLAYERS, 0.6F, 2.0F);
                }
                a.whip.hooks.struckBlock(player, a.stack, a.whip, pos, tip);
            }
            // Nothing is hit while the tip is still whipping back from the windup (overhead and behind the hand).
            if (a.stopped || !t.hitting(tick) || !a.swing.forward(strand, cord, tick)) {
                return;
            }
            double[] swept = cord.sweptBounds(t.hitRadius());
            for (Entity e : near) {
                Entity whole = WhipTargets.whole(e);
                AABB box = e.getBoundingBox();
                if (a.stopped || a.hit.contains(whole.getUUID()) || box.maxX < swept[0] || box.minX > swept[3] || box.maxY < swept[1]
                    || box.minY > swept[4] || box.maxZ < swept[2] || box.minZ > swept[5]) {
                    continue;
                }
                CordHit.Contact c = CordHit.test(cord, t.hitRadius(), new double[] {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ});
                if (c != null) {
                    strike(player, a, e, whole, c, strand, tipSpeed, eye, look);
                }
            }
        });
        a.hand = hand;
        if (!more) {
            end(player);
        }
    }

    /** Told why a contact was not hit (spared, rule, behind a block), when set; for {@link WhipSelfTest}. */
    static java.util.function.@Nullable Consumer<String> trace;

    /** One contact: the rules, the hooks, then the damage. */
    private static void strike(ServerPlayer player, Active a, Entity touched, Entity whole, CordHit.Contact c, int strand, double tipSpeed,
                               Vec3 eye, Vec3 look) {
        Vec3 contact = vec(c.point());
        boolean near = CordHit.nearCrosshair(vec(eye), vec(look), c.point(), WhipTargets.CROSSHAIR_DEGREES);
        if (WhipTargets.spared(player, whole) || !a.whip.targets.mayHit(player, whole, near)) {
            if (trace != null) {
                trace.accept(whole.getType().toShortString() + " not hit: " + (WhipTargets.spared(player, whole) ? "spared" : "rule")
                    + (near ? " (near the crosshair)" : ""));
            }
            return;
        }
        // The cord itself passes through blocks, but it never hits through them: the contact must be in view of the hand.
        Vec3 from = hand(player, 1.0F);
        if (player.level().clip(new ClipContext(from, contact, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType()
            != HitResult.Type.MISS) {
            if (trace != null) {
                trace.accept(whole.getType().toShortString() + " not hit: a block between the hand and " + contact);
            }
            return;
        }
        WhipTuning t = a.whip.tuning;
        a.hit.add(whole.getUUID());
        int index = a.hit.size() - 1;
        if (index + 1 >= t.maxTargets() || !t.sweep()) {
            a.stopped = true;
        }
        boolean tip = t.isTip(c.along());
        boolean crit = tip && tipSpeed >= t.crackSpeed();
        double bonus = Math.max(0, player.getAttributeValue(Attributes.ATTACK_DAMAGE) - 1.0);
        double amount = (t.damage() + bonus) * t.falloff(c.along()) * t.multiHit(index) * (crit ? t.tipCritMultiplier() : 1.0);
        WhipHit hit = new WhipHit(player, a.stack, a.whip, touched, whole, contact, c.along(), strand, index, tip, tipSpeed, near, crit, (float) amount);
        a.whip.hooks.cordHit(hit);
        if (tip) {
            a.whip.hooks.tipHit(hit);
        }
        if (hit.cancelled()) {
            return;
        }
        ServerLevel level = player.level();
        DamageSource source = a.whip.damage.make(player, a.stack);
        float dealt = EnchantmentHelper.modifyDamage(level, a.stack, whole, source, hit.damage);
        boolean hurt = dealt > 0 && touched.hurtServer(level, source, dealt);
        if (hurt) {
            EnchantmentHelper.doPostAttackEffectsWithItemSource(level, whole, source, a.stack);
            if (whole instanceof LivingEntity living) {
                player.setLastHurtMob(living);
                double kb = t.knockback() * (crit ? 1.5 : 1.0);
                if (kb > 0) {
                    living.knockback(kb, player.getX() - living.getX(), player.getZ() - living.getZ(), source, dealt);
                }
            }
            if (a.whip.summonTag != null) {
                WhipTags.apply(player, whole, a.whip.id, a.whip.summonTag);
            }
            if (!a.wore && a.stack.isDamageableItem()) {
                a.wore = true;
                a.stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
            }
        }
        a.whip.hooks.landed(hit, hurt);
    }

    /** Left click: swing on the press, and again whenever the cooldown allows while it is held. */
    private static final Triggers.Handler TRIGGER = new Triggers.Handler() {
        @Override
        public void press(ServerPlayer player, ItemStack stack) {
            swing(player);
        }

        @Override
        public void hold(ServerPlayer player, ItemStack stack, int ticks) {
            if (!player.getCooldowns().isOnCooldown(stack)) {
                swing(player);
            }
        }
    };

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        Triggers.register(stack -> definition(stack) != null, TRIGGER);
        WhipTags.init();
        if (EXAMPLE_WHIP != null) {
            register(EXAMPLE_WHIP.getId(), ExampleWhip.definition());
            WhipSelfTest.init();
        }
        modBus.addListener(Whips::payloads);
        WhipSlam.init(modBus);
        WhipHip.init(modBus);
        WhipFx.init(modBus);
        NeoForge.EVENT_BUS.addListener(Whips::playerTick);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> ACTIVE.remove(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> ACTIVE.remove(e.getEntity().getUUID()));
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        event.registrar("2").playToClient(SwingPayload.TYPE, SwingPayload.STREAM_CODEC, SwingPayload::handle);
    }

    /** Server to clients: a player started a swing (each strand's aim, side) with the whip item {@code item}. */
    public record SwingPayload(int entity, Identifier item, List<Vector3fc> looks, int side) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SwingPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("fundamentum", "whip_swing"));
        public static final StreamCodec<ByteBuf, SwingPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SwingPayload::entity, Identifier.STREAM_CODEC, SwingPayload::item,
            ByteBufCodecs.VECTOR3F.apply(ByteBufCodecs.list(8)), SwingPayload::looks, ByteBufCodecs.VAR_INT, SwingPayload::side, SwingPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(SwingPayload payload, IPayloadContext context) {
            WhipsClient.start(payload.entity(), payload.item(), payload.looks(), payload.side());
        }
    }

    private Whips() {}

    static boolean any(boolean[] flags) {
        for (boolean f : flags) {
            if (f) {
                return true;
            }
        }
        return false;
    }
}
