package com.fundamentum.wings;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.curios.Slots;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Terraria-style wings, worn in the {@code wings} Curios slot. Holding jump in the air rises until the flight time runs
 * out, then glides; landing refills it. No fall damage while flying or gliding. Each wing item's stats are data, in
 * {@code data/<namespace>/fundamentum/wings/<name>.json} keyed by its "item" field (default: the file id):
 * <pre>{"item": "mymod:angel_wings", "flight_ticks": 50, "rise_speed": 0.45, "rise_acceleration": 0.12,
 *  "horizontal_speed": 1.25, "glide_fall_speed": 0.12}</pre>
 * Every field is optional ({@link Stats#DEFAULT}). Flight time scales with the {@code fundamentum:wing_flight_time}
 * attribute and is capped at {@link #MAX_FLIGHT_TICKS}, below vanilla's 80-tick "flying is not enabled" kick. A wing item with an
 * equippable component naming an equipment asset is drawn from that asset (see WingsRender, 1.15.0); otherwise the wearer's
 * own mod draws the wings; Fundamentum only moves the player. Movement runs on the client (see WingsClient); the server
 * only clears fall damage for players who report flying and actually wear wings.
 */
public final class Wings {
    /** Longest continuous rise; a dedicated server kicks a "floating" player after 80 ticks. */
    public static final int MAX_FLIGHT_TICKS = 75;
    /** Slowest glide; vanilla counts anything slower than 0.03125 blocks per tick down as floating. */
    public static final double MIN_GLIDE_FALL_SPEED = 0.04;

    public record Stats(int flightTicks, double riseSpeed, double riseAcceleration, double horizontalSpeed, double glideFallSpeed) {
        public static final Stats DEFAULT = new Stats(40, 0.4, 0.1, 1.0, 0.15);

        static Stats fromJson(JsonObject json) {
            return new Stats(
                Math.max(0, json.has("flight_ticks") ? json.get("flight_ticks").getAsInt() : DEFAULT.flightTicks),
                json.has("rise_speed") ? json.get("rise_speed").getAsDouble() : DEFAULT.riseSpeed,
                json.has("rise_acceleration") ? json.get("rise_acceleration").getAsDouble() : DEFAULT.riseAcceleration,
                json.has("horizontal_speed") ? json.get("horizontal_speed").getAsDouble() : DEFAULT.horizontalSpeed,
                Math.max(MIN_GLIDE_FALL_SPEED, json.has("glide_fall_speed") ? json.get("glide_fall_speed").getAsDouble() : DEFAULT.glideFallSpeed));
        }
    }

    public static final DataTable<Stats> STATS = DataTables
        .register(ResourceLocation.fromNamespaceAndPath("fundamentum", "wings"), "fundamentum/wings", Stats::fromJson)
        .keyField("item")
        .synced();

    /** Players (server side) whose client says they are flying or gliding. */
    private static final Set<UUID> AIRBORNE = ConcurrentHashMap.newKeySet();

    /** The wings a player wears, or empty. */
    public static ItemStack worn(Player player) {
        return Slots.equipped(player, Slots.WINGS);
    }

    /** The stats of worn wings (defaults for wing items with no data), or null when none are worn. */
    public static Stats stats(Player player) {
        ItemStack wings = worn(player);
        return wings.isEmpty() ? null : STATS.getOrDefault(BuiltInRegistries.ITEM.getKey(wings.getItem()), Stats.DEFAULT);
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
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(StatePayload.TYPE, StatePayload.STREAM_CODEC, StatePayload::handle);
    }

    private static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && AIRBORNE.contains(player.getUUID())) {
            if (worn(player).isEmpty()) {
                AIRBORNE.remove(player.getUUID());
            } else {
                player.resetFallDistance();
            }
        }
    }

    /** Client to server: this player started (true) or stopped (false) flying or gliding on wings. */
    public record StatePayload(boolean airborne) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<StatePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("fundamentum", "wing_state"));
        public static final StreamCodec<ByteBuf, StatePayload> STREAM_CODEC = ByteBufCodecs.BOOL.map(StatePayload::new, StatePayload::airborne);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(StatePayload payload, IPayloadContext context) {
            if (payload.airborne && !worn(context.player()).isEmpty()) {
                AIRBORNE.add(context.player().getUUID());
            } else {
                AIRBORNE.remove(context.player().getUUID());
            }
        }
    }

    private Wings() {}
}
