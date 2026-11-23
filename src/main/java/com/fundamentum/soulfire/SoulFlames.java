package com.fundamentum.soulfire;

import com.fundamentum.api.SoulFire;
import com.fundamentum.damage.Elements;
import com.fundamentum.net.ClientNet;
import com.fundamentum.net.Net;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ProtectionEnchantment;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.network.simple.SimpleChannel;

/** The soul burning behind {@link SoulFire}: the synced flag, what sets and clears it, and the burn damage. */
public final class SoulFlames {
    /** The flag, in the entity's Forge persistent data (saved with it; on a client, set by {@link Sync}). */
    public static final String SOUL_BURNING = "fundamentum:soul_burning";

    /** The flag to clients tracking the entity (1.20.1 stand-in for a synced attachment). */
    private record Sync(int entity, boolean soul) {
        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(entity);
            buf.writeBoolean(soul);
        }

        static Sync read(FriendlyByteBuf buf) {
            return new Sync(buf.readVarInt(), buf.readBoolean());
        }
    }

    private static final SimpleChannel CHANNEL = Net.optionalChannel("soul_fire", "1");

    /** A hit of soul fire (any, in the element tag). */
    public static final ResourceKey<DamageType> SOUL_FIRE = com.fundamentum.damage.SharedDamage.SOUL_FIRE;
    /** A soul burning tick (vanilla's on_fire, in soul fire). */
    public static final ResourceKey<DamageType> ON_SOUL_FIRE = ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation("fundamentum", "on_soul_fire"));

    /** Server side only: the fire ticks an entity had after its last tick, and when a soul hit last landed on it. */
    private static final class Track {
        int lastFire;
        long soulHit = Long.MIN_VALUE;
    }

    private static final Map<Entity, Track> TRACK = new WeakHashMap<>();

    public static void init(IEventBus modBus) {
        Net.toClient(CHANNEL, 0, Sync.class, Sync::write, Sync::read, (msg, context) -> {
            Entity entity = ClientNet.entity(msg.entity());
            if (entity != null) {
                entity.getPersistentData().putBoolean(SOUL_BURNING, msg.soul());
            }
        });
        MinecraftForge.EVENT_BUS.addListener(SoulFlames::tick);
        MinecraftForge.EVENT_BUS.addListener(SoulFlames::hit);
        MinecraftForge.EVENT_BUS.addListener(SoulFlames::startTracking);
        MinecraftForge.EVENT_BUS.addListener(SoulFlames::burn);
        SoulFireSelfTest.init();
    }

    public static boolean isSoulBurning(Entity entity) {
        return flag(entity) && entity.isOnFire();
    }

    /** The stored flag alone (true until the next tick clears it, even once the fire is out). */
    public static boolean flag(Entity entity) {
        return entity.getPersistentData().getBoolean(SOUL_BURNING);
    }

    public static void ignite(Entity entity, int ticks) {
        if (entity.level().isClientSide || entity.fireImmune()) {
            return;
        }
        int t = entity instanceof LivingEntity living ? ProtectionEnchantment.getFireAfterDampener(living, ticks) : ticks;
        if (entity.getRemainingFireTicks() < t) {
            entity.setRemainingFireTicks(t);
        }
        set(entity, true);
        track(entity).lastFire = entity.getRemainingFireTicks();
    }

    public static void clear(Entity entity) {
        if (!entity.level().isClientSide) {
            set(entity, false);
        }
    }

    private static void set(Entity entity, boolean soul) {
        if (flag(entity) != soul) {
            if (soul) {
                entity.getPersistentData().putBoolean(SOUL_BURNING, true);
            } else {
                entity.getPersistentData().remove(SOUL_BURNING);
            }
            Net.sendTrackingAndSelf(CHANNEL, entity, new Sync(entity.getId(), soul));
        }
    }

    private static void startTracking(PlayerEvent.StartTracking event) {
        Entity target = event.getTarget();
        if (event.getEntity() instanceof ServerPlayer player && flag(target)) {
            Net.sendTo(CHANNEL, player, new Sync(target.getId(), true));
        }
    }

    private static Track track(Entity entity) {
        return TRACK.computeIfAbsent(entity, e -> new Track());
    }

    /**
     * While soul burning, the once-a-second burn hit is {@code fundamentum:on_soul_fire} for soulFireBurnDamage instead
     * of on_fire for 1 (1.20.1: the on_fire hit is cancelled at the attack event and dealt again, no mixin).
     */
    private static void burn(LivingAttackEvent event) {
        LivingEntity entity = event.getEntity();
        if (!entity.level().isClientSide && event.getSource().is(DamageTypes.ON_FIRE) && isSoulBurning(entity)) {
            event.setCanceled(true);
            entity.hurt(burnSource(entity.level()), burnDamage());
        }
    }

    /** The burn tick's damage: what the burn hook swaps vanilla's 1 HP on_fire for while soul burning. */
    public static float burnDamage() {
        return SoulFireConfig.burnDamage();
    }

    public static DamageSource burnSource(Level level) {
        return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(ON_SOUL_FIRE));
    }

    /** Whether a hit sets what it hits soul-burning (for the fire it starts, or already burning). */
    public static boolean isSoulHit(DamageSource source) {
        if (source.is(ON_SOUL_FIRE)) {
            return false; // a burn tick keeps the fire blue but doesn't start more
        }
        if (Elements.SOUL_FIRE.matches(source)) {
            return true;
        }
        Entity direct = source.getDirectEntity();
        if (direct != null && direct != source.getEntity() && direct.getType().is(SoulFire.PROJECTILES)) {
            return true;
        }
        // 1.20.1 has no weapon on a damage source: a melee hit is the attacker's main hand
        ItemStack weapon = source.getEntity() instanceof LivingEntity attacker && direct == attacker ? attacker.getMainHandItem() : null;
        return weapon != null && weapon.is(SoulFire.WEAPONS);
    }

    private static void hit(LivingDamageEvent event) {
        LivingEntity target = event.getEntity();
        DamageSource source = event.getSource();
        if (target.level().isClientSide || !isSoulHit(source)) {
            return;
        }
        track(target).soulHit = target.level().getGameTime();
        if (Elements.SOUL_FIRE.matches(source) && SoulFireConfig.hitIgniteTicks() > 0) {
            ignite(target, SoulFireConfig.hitIgniteTicks());
        } else if (target.getRemainingFireTicks() > 0) {
            set(target, true);
        }
    }

    /** 1.20.1: living entities only (Forge has no tick event for every entity); this runs before the entity's own tick. */
    private static void tick(LivingEvent.LivingTickEvent event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide) {
            return;
        }
        int fire = entity.getRemainingFireTicks();
        boolean soul = flag(entity);
        Track track = TRACK.get(entity);
        if (fire <= 0) {
            if (soul) {
                set(entity, false);
            }
            if (track != null && track.soulHit < entity.level().getGameTime() - 1) {
                TRACK.remove(entity);
            }
            return;
        }
        if (track == null) {
            track = track(entity);
        }
        int touching = touching(entity);
        if (touching < 0 || entity.isInLava()) {
            set(entity, false);
        } else if (touching > 0) {
            set(entity, true);
        } else if (fire > track.lastFire) {
            // set alight (again) this tick: soul if a soul hit just landed, else ordinary fire
            set(entity, track.soulHit >= entity.level().getGameTime() - 1);
        }
        track.lastFire = fire;
    }

    /** 1 if the entity is inside a soul fire source, -1 if inside ordinary fire, else 0. */
    private static int touching(Entity entity) {
        AABB box = entity.getBoundingBox().deflate(1.0E-3);
        int found = 0;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = entity.level().getBlockState(pos);
            boolean campfire = state.is(BlockTags.CAMPFIRES);
            if (campfire && !CampfireBlock.isLitCampfire(state)) {
                continue;
            }
            if (state.is(SoulFire.SOURCES)) {
                found = 1;
            } else if (state.is(BlockTags.FIRE) || campfire) {
                return -1;
            }
        }
        return found;
    }

    private SoulFlames() {}
}
