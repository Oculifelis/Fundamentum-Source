package com.fundamentum.mount;

import com.fundamentum.net.Net;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.simple.SimpleChannel;
import com.fundamentum.data.Unknown;
import com.fundamentum.slots.Slots;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.TickEvent;
import com.fundamentum.net.IPayloadContext;
import org.jetbrains.annotations.Nullable;

/**
 * Summoned mounts, Terraria style: an item in the {@code mount} Curios slot, and the mount hotkey (default V) summons
 * its creature and seats the player on it; the hotkey again, getting off, unequipping the item, dying, changing
 * dimension or logging out puts it away. Which creature an item summons is data, in
 * {@code data/<namespace>/fundamentum/mounts/<name>.json} keyed by its "item" field (default: the file id):
 * <pre>{"item": "mymod:bat_whistle", "entity": "mymod:giant_bat", "speed": 0.3, "jump": 0.9, "flying": true,
 *  "flight_speed": 0.06, "step_height": 1.0, "health": 30, "invulnerable": true}</pre>
 * Only "entity" is required. Stats set the creature's attributes (where it has them); "flying" turns its gravity off;
 * "body" (1.15.0) is an item put in the creature's body slot, e.g. {@code "minecraft:white_harness"} for a happy ghast.
 * Mods make custom mounts by extending {@link MountEntity}; any rideable creature works too. Tamable creatures are tamed
 * to the rider, and anything that can wear a saddle is saddled. A creature the rider could not steer in vanilla (a pig
 * or strider without its stick, a llama, a dolphin) is steered by Fundamentum (1.15.0): it turns with the rider's look,
 * walks with their movement keys at its "speed", and in water or with "flying" climbs and dives where they look.
 * Summoned mounts are never saved (leftovers are removed on load) and, unless the data says otherwise, take no damage.
 */
public final class Mounts {
    /** One mount item's definition. Stats left null keep the creature's own values. */
    public record Def(ResourceLocation entity, @Nullable Double speed, @Nullable Double jump, boolean flying, @Nullable Double flightSpeed,
                      @Nullable Double stepHeight, @Nullable Double health, boolean invulnerable, @Nullable ResourceLocation body) {
        public Def(ResourceLocation entity, @Nullable Double speed, @Nullable Double jump, boolean flying, @Nullable Double flightSpeed,
                   @Nullable Double stepHeight, @Nullable Double health, boolean invulnerable) {
            this(entity, speed, jump, flying, flightSpeed, stepHeight, health, invulnerable, null);
        }

        static Def fromJson(JsonObject json) {
            return new Def(new ResourceLocation(json.get("entity").getAsString()), number(json, "speed"), number(json, "jump"),
                json.has("flying") && json.get("flying").getAsBoolean(), number(json, "flight_speed"), number(json, "step_height"),
                number(json, "health"), !json.has("invulnerable") || json.get("invulnerable").getAsBoolean(),
                json.has("body") ? new ResourceLocation(json.get("body").getAsString()) : null);
        }

        private static @Nullable Double number(JsonObject json, String field) {
            return json.has(field) ? json.get(field).getAsDouble() : null;
        }
    }

    public static final DataTable<Def> MOUNTS = DataTables
        .register(new ResourceLocation("fundamentum", "mounts"), "fundamentum/mounts", Def::fromJson)
        .keyField("item");

    /** Persistent-data key on a summoned mount: its rider's UUID. */
    private static final String OWNER = "fundamentum:mount_owner";
    private static final String INVULNERABLE = "fundamentum:mount_invulnerable";
    private static final String STEER = "fundamentum:mount_steer";
    /** Client side: ids of mounts this client's player steers through Fundamentum (see {@link SteerPayload}). */
    private static final Set<Integer> CLIENT_STEERED = ConcurrentHashMap.newKeySet();
    static final SimpleChannel CHANNEL = Net.channel("mount", "2");
    /** Each player's summoned mount, and the item that summoned it. */
    private static final Map<UUID, Active> ACTIVE = new ConcurrentHashMap<>();

    private record Active(Entity mount, Item item) {}

    /** The definition for a mount item, or null when it is not one. */
    public static @Nullable Def def(ItemStack stack) {
        return stack.isEmpty() ? null : MOUNTS.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** A player's summoned mount, or null. */
    public static @Nullable Entity active(Player player) {
        Active active = ACTIVE.get(player.getUUID());
        return active == null ? null : active.mount();
    }

    /** True for a creature Fundamentum summoned as a mount. */
    public static boolean isSummoned(Entity entity) {
        return entity.getPersistentData().contains(OWNER);
    }

    /** The hotkey: put the mount away if one is out, otherwise summon it. */
    public static void toggle(ServerPlayer player) {
        if (active(player) != null) {
            dismiss(player);
        } else {
            summon(player);
        }
    }

    /** Summons the mount in the player's mount slot and seats them on it; false (with a message) when there is none. */
    public static boolean summon(ServerPlayer player) {
        dismiss(player);
        ItemStack stack = Slots.equipped(player, Slots.MOUNT);
        Def def = def(stack);
        if (stack.isEmpty() || def == null) {
            player.displayClientMessage(stack.isEmpty() ? Component.translatable("fundamentum.mount.none")
                : Component.translatable("fundamentum.mount.unknown", stack.getHoverName()), true);
            return false;
        }
        // The entity type registry falls back to the pig, so check the id first.
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.containsKey(def.entity()) ? BuiltInRegistries.ENTITY_TYPE.get(def.entity()) : null;
        ServerLevel level = player.serverLevel();
        Entity mount = type == null ? null : type.create(level);
        if (mount == null) {
            if (type == null) {
                Unknown.warn("entity", def.entity(), "mount for " + stack.getItem());
            }
            return false;
        }
        mount.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0.0F);
        mount.getPersistentData().putString(OWNER, player.getUUID().toString());
        mount.getPersistentData().putBoolean(INVULNERABLE, def.invulnerable());
        if (mount instanceof LivingEntity living) {
            set(living, Attributes.MOVEMENT_SPEED, def.speed());
            set(living, Attributes.JUMP_STRENGTH, def.jump());
            set(living, Attributes.FLYING_SPEED, def.flightSpeed());
            set(living, Attributes.MAX_HEALTH, def.health());
            living.setHealth(living.getMaxHealth());
        }
        if (def.stepHeight() != null) {
            mount.setMaxUpStep(def.stepHeight().floatValue());
        }
        if (mount instanceof AbstractHorse horse) {
            horse.setOwnerUUID(player.getUUID());
            horse.setTamed(true);
        }
        if (mount instanceof TamableAnimal tamable && !tamable.isTame()) {
            tamable.tame(player);
        }
        if (mount instanceof Mob mob) {
            // 1.20.1 has no saddle equipment slot: saddle anything Saddleable (horses, pigs, striders)
            if (mob instanceof net.minecraft.world.entity.Saddleable saddleable && saddleable.isSaddleable() && !saddleable.isSaddled()) {
                saddleable.equipSaddle(null);
            }
            // 1.20.1 has no body slot: "body" goes in a horse's or llama's armour slot (horse armour, carpets) and is
            // ignored on anything else
            if (def.body() != null && mob instanceof AbstractHorse horse && horse.canWearArmor()) {
                BuiltInRegistries.ITEM.getOptional(def.body()).ifPresent(item -> horse.equipArmor(player, new ItemStack(item)));
            }
        }
        mount.setNoGravity(def.flying());
        ACTIVE.put(player.getUUID(), new Active(mount, stack.getItem()));
        level.addFreshEntity(mount);
        player.startRiding(mount, true);
        // Vanilla would not let the rider steer this one (no stick, no saddle slot, a dolphin): Fundamentum steers it.
        if (mount instanceof Mob && mount.getControllingPassenger() != player) {
            mount.getPersistentData().putBoolean(STEER, true);
            Net.sendTo(CHANNEL, player, new SteerPayload(mount.getId(), true));
        }
        poof(mount);
        return true;
    }

    /** True for a summoned mount Fundamentum steers for its rider (on either side). */
    public static boolean steers(Entity entity) {
        return entity.level().isClientSide() ? CLIENT_STEERED.contains(entity.getId()) : entity.getPersistentData().getBoolean(STEER);
    }

    /**
     * One tick of a steered mount's movement, in place of vanilla's ridden travel: it faces where the rider looks, walks
     * with their keys (backwards and sideways slower) at its movement speed, and in water or with no gravity climbs and
     * dives with their pitch.
     */
    public static void steerTravel(LivingEntity mount, Player rider) {
        float yaw = rider.getYRot();
        mount.setYRot(yaw);
        mount.yRotO = yaw;
        mount.setXRot(0.0F);
        mount.yBodyRot = yaw;
        mount.yHeadRot = yaw;
        float forward = rider.zza < 0.0F ? rider.zza * 0.25F : rider.zza;
        float strafe = rider.xxa * 0.5F;
        double up = 0.0;
        if (mount.isInWater() || mount.isNoGravity()) {
            float pitch = rider.getXRot() * Mth.DEG_TO_RAD;
            up = -Mth.sin(pitch) * forward;
            forward *= Mth.cos(pitch);
        }
        if (mount.isControlledByLocalInstance()) {
            mount.setSpeed((float) mount.getAttributeValue(Attributes.MOVEMENT_SPEED));
            mount.travel(new Vec3(strafe, up, forward));
        } else {
            mount.setDeltaMovement(Vec3.ZERO);
        }
    }

    /** Puts a player's mount away, if they have one out. */
    public static void dismiss(Player player) {
        Active active = ACTIVE.remove(player.getUUID());
        if (active != null && active.mount().getPersistentData().getBoolean(STEER) && player instanceof ServerPlayer sp) {
            Net.sendTo(CHANNEL, sp, new SteerPayload(active.mount().getId(), false));
        }
        if (active != null && !active.mount().isRemoved()) {
            poof(active.mount());
            active.mount().ejectPassengers();
            active.mount().discard();
        }
    }

    private static void set(LivingEntity entity, Attribute attribute, @Nullable Double value) {
        AttributeInstance instance = value == null ? null : entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private static void poof(Entity mount) {
        if (mount.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.POOF, mount.getX(), mount.getY() + mount.getBbHeight() / 2, mount.getZ(), 12,
                mount.getBbWidth() / 2, mount.getBbHeight() / 3, mount.getBbWidth() / 2, 0.02);
        }
    }

    public static void init(IEventBus modBus) {
        Net.toServerUnit(CHANNEL, 0, TogglePayload.class, TogglePayload.INSTANCE, TogglePayload::handle);
        Net.toClient(CHANNEL, 1, SteerPayload.class, SteerPayload::encode, SteerPayload::decode, SteerPayload::handle);
        MinecraftForge.EVENT_BUS.addListener(Mounts::tick);
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> dismiss(e.getEntity()));
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> dismiss(e.getEntity()));
        MinecraftForge.EVENT_BUS.addListener((LivingDeathEvent e) -> {
            if (e.getEntity() instanceof Player player) {
                dismiss(player);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((LivingAttackEvent e) -> {
            if (e.getEntity().getPersistentData().getBoolean(INVULNERABLE)) {
                e.setCanceled(true);
            }
        });
        // A summoned mount loaded from disk (a horse after a crash, say) belongs to no one: remove it.
        MinecraftForge.EVENT_BUS.addListener((EntityJoinLevelEvent e) -> {
            if (!e.getLevel().isClientSide() && isSummoned(e.getEntity()) && ACTIVE.values().stream().noneMatch(a -> a.mount() == e.getEntity())) {
                e.setCanceled(true);
            }
        });
    }

    /** Puts the mount away once its rider is off it, it died, or the item left the mount slot. */
    private static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.player instanceof ServerPlayer player) {
            Active active = ACTIVE.get(player.getUUID());
            if (active != null && (player.getVehicle() != active.mount() || !active.mount().isAlive()
                || player.tickCount % 10 == 0 && !Slots.equipped(player, Slots.MOUNT).is(active.item()))) {
                dismiss(player);
            }
        }
    }

    /** Server to the rider: Fundamentum steers mount {@code id} for you (or no longer). */
    public record SteerPayload(int id, boolean on) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(this.id);
            buf.writeBoolean(this.on);
        }

        static SteerPayload decode(FriendlyByteBuf buf) {
            return new SteerPayload(buf.readVarInt(), buf.readBoolean());
        }


        static void handle(SteerPayload payload, IPayloadContext context) {
            if (payload.on()) {
                CLIENT_STEERED.add(payload.id());
            } else {
                CLIENT_STEERED.remove(payload.id());
            }
        }
    }

    /** Client side: forget steered mounts when leaving a world. */
    static void clearClient() {
        CLIENT_STEERED.clear();
    }

    /** Client to server: the mount hotkey was pressed. */
    public record TogglePayload() {
        public static final TogglePayload INSTANCE = new TogglePayload();

        static void handle(TogglePayload payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player && player.isAlive() && !player.isSpectator()) {
                toggle(player);
            }
        }
    }

    private Mounts() {}
}
