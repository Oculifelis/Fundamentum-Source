package com.fundamentum.hook;

import com.fundamentum.slots.Slots;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jspecify.annotations.Nullable;

/**
 * Grappling hooks, Terraria style: an item in the {@code hook} Curios slot, and the hook hotkey (default H; G is Curios' own key) fires a hook
 * where the player looks. It latches onto the first block it meets and pulls the player there, then holds them; jumping
 * lets go. Firing again adds hooks up to the item's count (the oldest goes first). Hook items are data, in
 * {@code data/<namespace>/fundamentum/hooks/<name>.json} keyed by "item" (default: the file id), all fields optional:
 * <pre>{"item": "mymod:ivy_whip", "range": 18, "shoot_speed": 1.5, "pull_speed": 0.7, "count": 1,
 *  "texture": "mymod:textures/entity/ivy_hook.png", "still": false, "air_seconds": 0}</pre>
 * Range is in blocks, speeds in blocks per tick. {@code still} (1.22.1, after Terraria's Static Hook) holds the player
 * exactly at the middle of the latched hooks, without the usual give, so two hooks on facing walls hang them in the air
 * between. {@code air_seconds} above 0 lets a hook that reaches its range without meeting a block anchor in the air
 * there, for that many seconds. A texture replaces the hook and chain's, in the layout of
 * {@code fundamentum:textures/entity/hook/hook.png} (see {@link HookModel}). A latched player takes no fall damage.
 */
public final class Hooks {
    /** One hook item's definition. */
    public record Def(double range, double shootSpeed, double pullSpeed, int count, @Nullable Identifier texture, boolean still,
                      double airSeconds) {
        public static final Def DEFAULT = new Def(18.0, 1.5, 0.7, 1, null, false, 0.0);

        static Def fromJson(JsonObject json) {
            return new Def(number(json, "range", DEFAULT.range), number(json, "shoot_speed", DEFAULT.shootSpeed),
                number(json, "pull_speed", DEFAULT.pullSpeed), Math.max(1, (int) number(json, "count", DEFAULT.count)),
                json.has("texture") ? Identifier.parse(json.get("texture").getAsString()) : null,
                json.has("still") && json.get("still").getAsBoolean(), Math.max(0.0, number(json, "air_seconds", 0.0)));
        }

        private static double number(JsonObject json, String field, double fallback) {
            return json.has(field) ? json.get(field).getAsDouble() : fallback;
        }
    }

    public static final DataTable<Def> HOOKS = DataTables
        .register(Identifier.fromNamespaceAndPath("fundamentum", "hooks"), "fundamentum/hooks", Def::fromJson)
        .keyField("item")
        .synced();

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, "fundamentum");
    public static final DeferredHolder<EntityType<?>, EntityType<HookEntity>> HOOK = ENTITIES.register("hook",
        id -> EntityType.Builder.<HookEntity>of(HookEntity::new, MobCategory.MISC).sized(0.25F, 0.25F)
            .clientTrackingRange(8).updateInterval(1).noSave().noSummon().build(ResourceKey.create(Registries.ENTITY_TYPE, id)));

    /** Each player's hooks out, oldest first (server side). */
    private static final Map<UUID, List<HookEntity>> OUT = new ConcurrentHashMap<>();

    /** The definition for a hook item, or null when it is not one. */
    public static @Nullable Def def(ItemStack stack) {
        return stack.isEmpty() ? null : HOOKS.get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** A player's hooks that are out (server side), oldest first. */
    public static List<HookEntity> out(Player player) {
        List<HookEntity> hooks = OUT.get(player.getUUID());
        if (hooks == null) {
            return List.of();
        }
        hooks.removeIf(HookEntity::isRemoved);
        return hooks;
    }

    /** True when one of the player's hooks holds onto a block. */
    public static boolean latched(Player player) {
        return out(player).stream().anyMatch(HookEntity::isLatched);
    }

    /** The hotkey: fires a hook from the hook slot's item; false (with a message) when there is none. */
    public static boolean fire(ServerPlayer player) {
        ItemStack stack = Slots.equipped(player, Slots.HOOK);
        Def def = def(stack);
        if (stack.isEmpty() || def == null) {
            player.sendOverlayMessage(stack.isEmpty() ? Component.translatable("fundamentum.hook.none")
                : Component.translatable("fundamentum.hook.unknown", stack.getHoverName()));
            return false;
        }
        List<HookEntity> hooks = OUT.computeIfAbsent(player.getUUID(), u -> new ArrayList<>());
        hooks.removeIf(HookEntity::isRemoved);
        while (hooks.size() >= def.count()) {
            hooks.remove(0).discard();
        }
        HookEntity hook = new HookEntity(player.level(), player, BuiltInRegistries.ITEM.getKey(stack.getItem()));
        // From the body's middle toward the crosshair's point at full range, so it still lands where the player aims.
        Vec3 aim = player.getEyePosition().add(player.getLookAngle().scale(def.range()));
        hook.setDeltaMovement(aim.subtract(hook.position()).normalize().scale(def.shootSpeed()));
        player.level().addFreshEntity(hook);
        hooks.add(hook);
        hook.playSound(SoundEvents.FISHING_BOBBER_THROW, 0.6F, 1.4F);
        return true;
    }

    /** Lets go of every hook the player has out. */
    public static void release(Player player) {
        List<HookEntity> hooks = OUT.remove(player.getUUID());
        if (hooks != null) {
            hooks.forEach(HookEntity::discard);
        }
    }

    public static void init(IEventBus modBus) {
        ENTITIES.register(modBus);
        modBus.addListener(Hooks::payloads);
        NeoForge.EVENT_BUS.addListener(Hooks::tick);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> release(e.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> release(e.getEntity()));
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent e) -> {
            if (e.getEntity() instanceof Player player) {
                release(player);
            }
        });
    }

    /** A latched player takes no fall damage; taking the item out of the slot lets go. */
    private static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && OUT.containsKey(player.getUUID())) {
            List<HookEntity> hooks = out(player);
            if (hooks.isEmpty() || player.isPassenger() || player.tickCount % 10 == 0 && def(Slots.equipped(player, Slots.HOOK)) == null) {
                release(player);
            } else if (latched(player)) {
                player.resetFallDistance();
            }
        }
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
            .playToServer(FirePayload.TYPE, FirePayload.STREAM_CODEC, FirePayload::handle)
            .playToServer(ReleasePayload.TYPE, ReleasePayload.STREAM_CODEC, ReleasePayload::handle);
    }

    /** Client to server: the hook hotkey was pressed. */
    public record FirePayload() implements CustomPacketPayload {
        public static final FirePayload INSTANCE = new FirePayload();
        public static final CustomPacketPayload.Type<FirePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("fundamentum", "fire_hook"));
        public static final StreamCodec<ByteBuf, FirePayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(FirePayload payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player && player.isAlive() && !player.isSpectator() && !player.isPassenger()) {
                fire(player);
            }
        }
    }

    /** Client to server: the player jumped off their hooks. */
    public record ReleasePayload() implements CustomPacketPayload {
        public static final ReleasePayload INSTANCE = new ReleasePayload();
        public static final CustomPacketPayload.Type<ReleasePayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("fundamentum", "release_hook"));
        public static final StreamCodec<ByteBuf, ReleasePayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(ReleasePayload payload, IPayloadContext context) {
            release(context.player());
        }
    }

    private Hooks() {}
}
