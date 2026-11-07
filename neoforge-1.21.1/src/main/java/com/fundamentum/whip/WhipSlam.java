package com.fundamentum.whip;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jetbrains.annotations.Nullable;

/**
 * Charged ground slams ({@link WhipDefinition.Slam}, 1.22.0). The client sends use (right click) going down and up
 * while a slam whip is held; the server times the charge, and on the release brings the cord down ahead: hostiles in
 * the radius (and whatever the player last hit) are hurt, thrown out and up, block debris flies from widening rings,
 * and {@link WhipHooks#slam} runs. Every client sees the cord spin overhead while it charges and a ring on the ground.
 */
public final class WhipSlam {
    private record Charge(ResourceLocation item, long start) {}

    private static final Map<UUID, Charge> CHARGING = new ConcurrentHashMap<>();

    /** True while the player is charging a slam (server side). */
    public static boolean charging(ServerPlayer player) {
        return CHARGING.containsKey(player.getUUID());
    }

    static void press(ServerPlayer player, boolean down) {
        ItemStack stack = player.getMainHandItem();
        WhipDefinition whip = Whips.definition(stack);
        if (down) {
            if (whip == null || whip.slam == null || CHARGING.containsKey(player.getUUID()) || Whips.swinging(player)
                || player.getCooldowns().isOnCooldown(stack.getItem()) || !player.isAlive() || player.isSpectator()) {
                return;
            }
            CHARGING.put(player.getUUID(), new Charge(BuiltInRegistries.ITEM.getKey(stack.getItem()), player.level().getGameTime()));
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new ChargePayload(player.getId(), true));
            return;
        }
        Charge c = CHARGING.remove(player.getUUID());
        if (c == null) {
            return;
        }
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new ChargePayload(player.getId(), false));
        if (whip == null || whip.slam == null || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(c.item)) {
            return;
        }
        long held = player.level().getGameTime() - c.start;
        if (held >= Math.max(1, whip.slam.chargeTicks() / 4)) {
            slam(player, stack, whip, (float) Math.min(1.0, (double) held / whip.slam.chargeTicks()));
        }
    }

    /** Brings a slam down now at {@code strength} (0..1 of a full charge). Returns where it landed. */
    public static Vec3 slam(ServerPlayer player, ItemStack stack, WhipDefinition whip, float strength) {
        WhipDefinition.Slam s = whip.slam;
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        flat = flat.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : flat.normalize();
        Vec3 eye = player.getEyePosition();
        Vec3 ahead = eye.add(flat.scale(s.reach()));
        BlockHitResult wall = level.clip(new ClipContext(eye, ahead, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (wall.getType() != HitResult.Type.MISS) {
            ahead = wall.getLocation().subtract(flat.scale(0.3));
        }
        BlockHitResult ground = level.clip(new ClipContext(ahead, ahead.add(0, -6, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 at = ground.getType() == HitResult.Type.MISS ? new Vec3(ahead.x, player.getY(), ahead.z) : ground.getLocation();
        double radius = s.radius() * (0.5 + 0.5 * strength);

        List<LivingEntity> hit = new ArrayList<>();
        DamageSource source = whip.damage.make(player, stack);
        for (Entity e : level.getEntities(player, new AABB(at, at).inflate(radius, 2.0, radius))) {
            Entity whole = WhipTargets.whole(e);
            if (!(whole instanceof LivingEntity living) || hit.contains(living) || !WhipTargets.touchable(player, whole)
                || WhipTargets.spared(player, whole) || !(WhipTargets.isHostile(whole) || whole == player.getLastHurtMob())) {
                continue;
            }
            Vec3 off = living.position().subtract(at);
            double dist = Math.sqrt(off.x * off.x + off.z * off.z);
            if (dist > radius || Math.abs(off.y) > 2.5) {
                continue;
            }
            float amount = (float) (s.damage() * strength * (1.0 - 0.5 * dist / radius));
            float dealt = EnchantmentHelper.modifyDamage(level, stack, living, source, amount);
            if (dealt > 0 && living.hurt(source, dealt)) {
                hit.add(living);
                double resist = 1.0 - living.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
                Vec3 out = dist < 1.0E-3 ? flat : new Vec3(off.x / dist, 0, off.z / dist);
                living.push(out.x * s.knockback() * strength * resist, s.lift() * strength * resist, out.z * s.knockback() * strength * resist);
                living.hurtMarked = true;
                player.setLastHurtMob(living);
            }
        }
        if (!hit.isEmpty() && stack.isDamageableItem()) {
            stack.hurtAndBreak(1, player, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        }
        debris(level, at, radius);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.MACE_SMASH_GROUND, SoundSource.PLAYERS, 0.6F + 0.4F * strength, 1.1F);
        if (whip.crackSound != null) {
            level.playSound(null, at.x, at.y, at.z, whip.crackSound, SoundSource.PLAYERS, 1.0F, 0.8F);
        }
        player.getCooldowns().addCooldown(stack.getItem(), s.cooldownTicks());
        player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        Vec3 hand = Whips.hand(player, 1.0F);
        Vec3 down = at.subtract(hand);
        Vector3fc aim = down.lengthSqr() < 1.0E-6 ? new Vector3f(0, -1, 0) : down.normalize().toVector3f();
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
            new Whips.SwingPayload(player.getId(), BuiltInRegistries.ITEM.getKey(stack.getItem()), List.of(aim), 1));
        PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, 64,
            new SlamPayload(player.getId(), at.toVector3f(), (float) radius, whip.shockwave != 0 ? whip.shockwave : 0xB0FFFFFF));
        whip.hooks.slam(player, stack, whip, at, strength, hit);
        return at;
    }

    /** Block chips thrown out from three rings, each block's own particle, from the surface under each point. */
    private static void debris(ServerLevel level, Vec3 at, double radius) {
        for (int ring = 1; ring <= 3; ring++) {
            double r = radius * ring / 3.0;
            int points = 10 + ring * 8;
            for (int i = 0; i < points; i++) {
                double a = Mth.TWO_PI * i / points + ring * 0.4;
                double x = at.x + Math.cos(a) * r, z = at.z + Math.sin(a) * r;
                BlockState state = surface(level, x, at.y, z);
                if (state == null) {
                    continue;
                }
                double speed = 0.18 + 0.06 * ring;
                // Count 0: the offsets are the particle's velocity.
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), x, at.y + 0.1, z, 0,
                    Math.cos(a) * speed, 0.35 + 0.1 * (3 - ring), Math.sin(a) * speed, 1.0);
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), x, at.y + 0.1, z, 3, 0.15, 0.05, 0.15, 0.2);
            }
        }
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3, at.z, 1, 0, 0, 0, 0);
    }

    private static @Nullable BlockState surface(ServerLevel level, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y - 0.05, z);
        for (int dy = 0; dy < 3; dy++) {
            BlockState state = level.getBlockState(pos.below(dy));
            if (!state.isAir() && !state.getCollisionShape(level, pos.below(dy)).isEmpty()) {
                return state;
            }
        }
        return null;
    }

    private static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Charge c = CHARGING.get(player.getUUID());
        if (c != null && (!player.isAlive() || !BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).equals(c.item))) {
            CHARGING.remove(player.getUUID());
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new ChargePayload(player.getId(), false));
        }
    }

    static void init(IEventBus modBus) {
        modBus.addListener(WhipSlam::payloads);
        NeoForge.EVENT_BUS.addListener(WhipSlam::tick);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> CHARGING.remove(e.getEntity().getUUID()));
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToServer(UsePayload.TYPE, UsePayload.STREAM_CODEC, UsePayload::handle);
        r.playToClient(ChargePayload.TYPE, ChargePayload.STREAM_CODEC, ChargePayload::handle);
        r.playToClient(SlamPayload.TYPE, SlamPayload.STREAM_CODEC, SlamPayload::handle);
    }

    /** Client to server: use went down or up with a slam whip in hand. */
    public record UsePayload(boolean down) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UsePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("fundamentum", "whip_use"));
        public static final StreamCodec<ByteBuf, UsePayload> STREAM_CODEC = ByteBufCodecs.BOOL.map(UsePayload::new, UsePayload::down);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(UsePayload payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                press(player, payload.down());
            }
        }
    }

    /** Server to clients: a player started or stopped charging. */
    public record ChargePayload(int entity, boolean charging) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<ChargePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("fundamentum", "whip_charge"));
        public static final StreamCodec<ByteBuf, ChargePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ChargePayload::entity, ByteBufCodecs.BOOL, ChargePayload::charging, ChargePayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(ChargePayload payload, IPayloadContext context) {
            WhipsClient.charge(payload.entity(), payload.charging());
        }
    }

    /** Server to clients: a slam landed at {@code at} with this radius; the ring's colour (ARGB). */
    public record SlamPayload(int entity, Vector3f at, float radius, int color) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SlamPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("fundamentum", "whip_slam"));
        public static final StreamCodec<ByteBuf, SlamPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SlamPayload::entity, WhipFx.V3, SlamPayload::at, ByteBufCodecs.FLOAT, SlamPayload::radius,
            ByteBufCodecs.INT, SlamPayload::color, SlamPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(SlamPayload payload, IPayloadContext context) {
            WhipsClient.slammed(payload.entity(), new Vec3(payload.at()), payload.radius(), payload.color());
        }
    }

    private WhipSlam() {}
}
