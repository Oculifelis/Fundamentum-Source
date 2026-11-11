package com.fundamentum.wings;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.curios.Slots;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Terraria-style wings, worn in the {@code wings} Curios slot. Holding jump in the air rises until the flight time runs
 * out, then glides; landing refills it. No fall damage while flying or gliding. Each wing item's stats are data, in
 * {@code data/<namespace>/fundamentum/wings/<name>.json} keyed by its "item" field (default: the file id):
 * <pre>{"item": "mymod:angel_wings", "flight_ticks": 50, "rise_speed": 0.45, "rise_acceleration": 0.12,
 *  "horizontal_speed": 1.25, "glide_fall_speed": 0.12}</pre>
 * Every field is optional ({@link Stats#DEFAULT}). An optional {@code "fx"} block adds particles and sounds (see {@link Fx}, 1.28.0):
 * <pre>"fx": {"burst": ["minecraft:cherry_leaves"], "burst_count": 12, "flap": ["minecraft:cherry_leaves"], "flap_count": 4,
 *  "trail": ["minecraft:cherry_leaves"], "trail_rate": 0.4, "flap_sound": "minecraft:entity.phantom.flap",
 *  "takeoff_sound": "minecraft:item.elytra.flying", "volume": 0.5, "pitch": 1.2, "flap_speed": 1.0}</pre>
 * Every wearer's flight phase (folded, rising, gliding) is sent to the players who can see them, so the wings spread and
 * flap for everyone (see WingsAnim, 1.28.0). Flight time scales with the {@code fundamentum:wing_flight_time}
 * attribute and is capped at {@link #MAX_FLIGHT_TICKS}, below vanilla's 80-tick "flying is not enabled" kick. Wings whose data names a
 * {@code texture} are drawn by Fundamentum on the elytra model (see WingsRender, 1.28.0); otherwise the wearer's own mod
 * draws them. Movement runs on the client (see WingsClient); the server
 * only clears fall damage for players who report flying and actually wear wings.
 */
public final class Wings {
    /** Longest continuous rise; a dedicated server kicks a "floating" player after 80 ticks. */
    public static final int MAX_FLIGHT_TICKS = 75;
    /** Slowest glide; vanilla counts anything slower than 0.03125 blocks per tick down as floating. */
    public static final double MIN_GLIDE_FALL_SPEED = 0.04;

    /** Flight phases, as sent between client and server: wings folded (grounded or not holding jump), rising, gliding. */
    public static final byte FOLDED = 0, RISING = 1, GLIDING = 2;

    /**
     * Particles and sounds for one pair of wings (1.28.0). Particle fields list plain particle ids (a string or an array);
     * every listed type is spawned. {@code burst} on take-off, {@code flap} on every downstroke while rising,
     * {@code trail} while gliding ({@code trail_rate} per tick, fractions allowed; half that while rising). Sounds play
     * on each downstroke and on take-off, at {@code volume} and {@code pitch}. {@code flap_speed} scales the flap rate.
     * Particles default to none, sounds to silence.
     */
    public record Fx(List<String> burst, int burstCount, List<String> flap, int flapCount, List<String> trail, double trailRate,
                     @Nullable String flapSound, @Nullable String takeoffSound, float volume, float pitch, float flapSpeed) {
        static Fx fromJson(JsonObject json) {
            return new Fx(ids(json.get("burst")), json.has("burst_count") ? json.get("burst_count").getAsInt() : 12,
                ids(json.get("flap")), json.has("flap_count") ? json.get("flap_count").getAsInt() : 4,
                ids(json.get("trail")), json.has("trail_rate") ? json.get("trail_rate").getAsDouble() : 0.4,
                json.has("flap_sound") ? json.get("flap_sound").getAsString() : null,
                json.has("takeoff_sound") ? json.get("takeoff_sound").getAsString() : null,
                json.has("volume") ? json.get("volume").getAsFloat() : 0.5F,
                json.has("pitch") ? json.get("pitch").getAsFloat() : 1.0F,
                json.has("flap_speed") ? Math.max(0.1F, json.get("flap_speed").getAsFloat()) : 1.0F);
        }

        private static List<String> ids(@Nullable JsonElement element) {
            if (element == null || element.isJsonNull()) {
                return List.of();
            }
            if (element instanceof JsonArray array) {
                List<String> out = new java.util.ArrayList<>();
                array.forEach(e -> out.add(e.getAsString()));
                return List.copyOf(out);
            }
            return List.of(element.getAsString());
        }
    }

    /**
     * {@code fx} is null when the data has no fx block: the wings still spread and flap, silently. {@code texture}
     * (1.28.0, this version only: it has no equipment assets) is the elytra-shaped texture Fundamentum draws the
     * wings with, e.g. {@code "mymod:textures/entity/wings/angel.png"}; without it the owning mod draws them.
     */
    public record Stats(int flightTicks, double riseSpeed, double riseAcceleration, double horizontalSpeed, double glideFallSpeed, @Nullable Fx fx, @Nullable String texture) {
        public static final Stats DEFAULT = new Stats(40, 0.4, 0.1, 1.0, 0.15, null, null);

        /** Kept from 1.27: no fx and no texture. */
        public Stats(int flightTicks, double riseSpeed, double riseAcceleration, double horizontalSpeed, double glideFallSpeed) {
            this(flightTicks, riseSpeed, riseAcceleration, horizontalSpeed, glideFallSpeed, null, null);
        }

        static Stats fromJson(JsonObject json) {
            return new Stats(
                Math.max(0, json.has("flight_ticks") ? json.get("flight_ticks").getAsInt() : DEFAULT.flightTicks),
                json.has("rise_speed") ? json.get("rise_speed").getAsDouble() : DEFAULT.riseSpeed,
                json.has("rise_acceleration") ? json.get("rise_acceleration").getAsDouble() : DEFAULT.riseAcceleration,
                json.has("horizontal_speed") ? json.get("horizontal_speed").getAsDouble() : DEFAULT.horizontalSpeed,
                Math.max(MIN_GLIDE_FALL_SPEED, json.has("glide_fall_speed") ? json.get("glide_fall_speed").getAsDouble() : DEFAULT.glideFallSpeed),
                json.has("fx") ? Fx.fromJson(json.getAsJsonObject("fx")) : null,
                json.has("texture") ? json.get("texture").getAsString() : null);
        }
    }

    public static final DataTable<Stats> STATS = DataTables
        .register(ResourceLocation.fromNamespaceAndPath("fundamentum", "wings"), "fundamentum/wings", Stats::fromJson)
        .keyField("item")
        .synced();

    /** Players (server side) whose client says they are rising or gliding, with that phase. */
    private static final Map<UUID, Byte> AIRBORNE = new ConcurrentHashMap<>();

    /** The wings a player wears, or empty. */
    public static ItemStack worn(Player player) {
        return Slots.equipped(player, Slots.WINGS);
    }

    /** The stats of worn wings (defaults for wing items with no data), or null when none are worn. */
    public static Stats stats(Player player) {
        ItemStack wings = worn(player);
        return wings.isEmpty() ? null : STATS.getOrDefault(BuiltInRegistries.ITEM.getKey(wings.getItem()), Stats.DEFAULT);
    }

    /** The data for one wings item ({@link Stats#DEFAULT} when its item has none). */
    public static Stats stats(ItemStack wings) {
        return STATS.getOrDefault(BuiltInRegistries.ITEM.getKey(wings.getItem()), Stats.DEFAULT);
    }

    /** Flight time in ticks for this player and these wings, after the wing_flight_time attribute and the cap. */
    public static int flightTicks(Player player, Stats stats) {
        double scale = player.getAttributes().hasAttribute(StatAttributes.WING_FLIGHT_TIME) ? player.getAttributeValue(StatAttributes.WING_FLIGHT_TIME) : 1;
        return (int) Math.min(MAX_FLIGHT_TICKS, Math.round(stats.flightTicks() * scale));
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(Wings::payloads);
        NeoForge.EVENT_BUS.addListener(Wings::tick);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> AIRBORNE.remove(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener(Wings::startTracking);
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("2");
        r.playToServer(StatePayload.TYPE, StatePayload.STREAM_CODEC, StatePayload::handle);
        r.playToClient(PhasePayload.TYPE, PhasePayload.STREAM_CODEC, PhasePayload::handle);
    }

    /** The flight phase the server last heard from this player ({@link #FOLDED} when none). */
    public static byte phase(Player player) {
        return AIRBORNE.getOrDefault(player.getUUID(), FOLDED);
    }

    private static void setPhase(ServerPlayer player, byte phase) {
        Byte old = phase == FOLDED ? AIRBORNE.remove(player.getUUID()) : AIRBORNE.put(player.getUUID(), phase);
        if ((old == null ? FOLDED : old) != phase) {
            PacketDistributor.sendToPlayersTrackingEntity(player, new PhasePayload(player.getId(), phase));
        }
    }

    /** A player who starts seeing a flying wearer learns its phase at once. */
    private static void startTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof Player target && event.getEntity() instanceof ServerPlayer tracker) {
            byte phase = phase(target);
            if (phase != FOLDED) {
                PacketDistributor.sendToPlayer(tracker, new PhasePayload(target.getId(), phase));
            }
        }
    }

    private static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && AIRBORNE.containsKey(player.getUUID())) {
            if (worn(player).isEmpty()) {
                setPhase(player, FOLDED);
            } else {
                player.resetFallDistance();
            }
        }
    }

    /** Client to server: this player's flight phase changed ({@link #FOLDED}, {@link #RISING} or {@link #GLIDING}). */
    public record StatePayload(byte phase) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<StatePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("fundamentum", "wing_state"));
        public static final StreamCodec<ByteBuf, StatePayload> STREAM_CODEC = ByteBufCodecs.BYTE.map(StatePayload::new, StatePayload::phase);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(StatePayload payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                byte phase = payload.phase >= RISING && payload.phase <= GLIDING && !worn(player).isEmpty() ? payload.phase : FOLDED;
                setPhase(player, phase);
            }
        }
    }

    /** Server to clients: a player they can see changed flight phase, so its wings move for them too. */
    public record PhasePayload(int entity, byte phase) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PhasePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("fundamentum", "wing_phase"));
        public static final StreamCodec<ByteBuf, PhasePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PhasePayload::entity, ByteBufCodecs.BYTE, PhasePayload::phase, PhasePayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(PhasePayload payload, IPayloadContext context) {
            WingsAnim.remotePhase(payload.entity(), payload.phase());
        }
    }

    private Wings() {}
}
