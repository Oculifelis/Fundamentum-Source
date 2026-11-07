package com.fundamentum.soulfire;

import com.fundamentum.api.SoulFire;
import com.fundamentum.damage.Elements;
import com.mojang.serialization.Codec;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** The soul burning behind {@link SoulFire}: the synced flag, what sets and clears it, and the burn damage. */
public final class SoulFlames {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "fundamentum");

    public static final Supplier<AttachmentType<Boolean>> SOUL_BURNING = ATTACHMENTS.register("soul_burning", () -> AttachmentType.builder(() -> false)
        .serialize(Codec.BOOL.fieldOf("soul"), soul -> soul)
        .sync(ByteBufCodecs.BOOL)
        .build());

    /** A hit of soul fire (any, in the element tag). */
    public static final ResourceKey<DamageType> SOUL_FIRE = com.fundamentum.damage.SharedDamage.SOUL_FIRE;
    /** A soul burning tick (vanilla's on_fire, in soul fire). */
    public static final ResourceKey<DamageType> ON_SOUL_FIRE = ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath("fundamentum", "on_soul_fire"));

    /** Server side only: the fire ticks an entity had after its last tick, and when a soul hit last landed on it. */
    private static final class Track {
        int lastFire;
        long soulHit = Long.MIN_VALUE;
    }

    private static final Map<Entity, Track> TRACK = new WeakHashMap<>();

    public static void init(IEventBus modBus) {
        ATTACHMENTS.register(modBus);
        NeoForge.EVENT_BUS.addListener(SoulFlames::tick);
        NeoForge.EVENT_BUS.addListener(SoulFlames::hit);
        SoulFireSelfTest.init();
    }

    public static boolean isSoulBurning(Entity entity) {
        return entity.hasData(SOUL_BURNING) && entity.getData(SOUL_BURNING) && entity.isOnFire();
    }

    public static void ignite(Entity entity, int ticks) {
        if (entity.level().isClientSide() || entity.fireImmune()) {
            return;
        }
        entity.igniteForTicks(ticks);
        set(entity, true);
        track(entity).lastFire = entity.getRemainingFireTicks();
    }

    public static void clear(Entity entity) {
        if (!entity.level().isClientSide()) {
            set(entity, false);
        }
    }

    private static void set(Entity entity, boolean soul) {
        boolean has = entity.hasData(SOUL_BURNING) && entity.getData(SOUL_BURNING);
        if (has != soul) {
            entity.setData(SOUL_BURNING, soul);
        }
    }

    private static Track track(Entity entity) {
        return TRACK.computeIfAbsent(entity, e -> new Track());
    }

    /** The burn tick's damage: what the burn mixin swaps vanilla's 1 HP on_fire for while soul burning. */
    public static float burnDamage() {
        return SoulFireConfig.burnDamage();
    }

    public static DamageSource burnSource(ServerLevel level) {
        return new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ON_SOUL_FIRE));
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
        if (direct != null && direct != source.getEntity() && direct.getType().builtInRegistryHolder().is(SoulFire.PROJECTILES)) {
            return true;
        }
        ItemStack weapon = source.getWeaponItem();
        return weapon != null && weapon.is(SoulFire.WEAPONS);
    }

    private static void hit(LivingDamageEvent.Post event) {
        LivingEntity target = event.getEntity();
        DamageSource source = event.getSource();
        if (target.level().isClientSide() || !isSoulHit(source)) {
            return;
        }
        track(target).soulHit = target.level().getGameTime();
        if (Elements.SOUL_FIRE.matches(source) && SoulFireConfig.hitIgniteTicks() > 0) {
            ignite(target, SoulFireConfig.hitIgniteTicks());
        } else if (target.getRemainingFireTicks() > 0) {
            set(target, true);
        }
    }

    private static void tick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide()) {
            return;
        }
        int fire = entity.getRemainingFireTicks();
        boolean soul = entity.hasData(SOUL_BURNING) && entity.getData(SOUL_BURNING);
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
