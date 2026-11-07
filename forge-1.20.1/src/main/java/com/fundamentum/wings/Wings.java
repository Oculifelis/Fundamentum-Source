package com.fundamentum.wings;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.curios.Slots;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Terraria-style wings, worn in the {@code wings} Curios slot. Holding jump in the air rises until the flight time runs
 * out, then glides; landing refills it. No fall damage while flying or gliding. Each wing item's stats are data, in
 * {@code data/<namespace>/fundamentum/wings/<name>.json} keyed by its "item" field (default: the file id):
 * <pre>{"item": "mymod:angel_wings", "flight_ticks": 50, "rise_speed": 0.45, "rise_acceleration": 0.12,
 *  "horizontal_speed": 1.25, "glide_fall_speed": 0.12}</pre>
 * Every field is optional ({@link Stats#DEFAULT}). Flight time scales with the {@code fundamentum:wing_flight_time}
 * attribute and is capped at {@link #MAX_FLIGHT_TICKS}, below vanilla's 80-tick "flying is not enabled" kick. The wearer's
 * own mod draws the wings; Fundamentum only moves the player. Movement runs on the client (see WingsClient); the server
 * only clears fall damage for players who report flying and actually wear wings.
 */
public final class Wings {
    /** Longest continuous rise; a dedicated server kicks a "floating" player after 80 ticks. */
    public static final int MAX_FLIGHT_TICKS = 75;
    /** Slowest glide; vanilla counts anything slower than 0.03125 blocks per tick down as floating. */
    public static final double MIN_GLIDE_FALL_SPEED = 0.04;

    private static final String VERSION = "1";
    static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("fundamentum", "wings"), () -> VERSION, VERSION::equals, VERSION::equals);

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
        .register(new ResourceLocation("fundamentum", "wings"), "fundamentum/wings", Stats::fromJson)
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
        return wings.isEmpty() ? null : STATS.getOrDefault(ForgeRegistries.ITEMS.getKey(wings.getItem()), Stats.DEFAULT);
    }

    /** Flight time in ticks for this player and these wings, after the wing_flight_time attribute and the cap. */
    public static int flightTicks(Player player, Stats stats) {
        var attribute = StatAttributes.WING_FLIGHT_TIME.get();
        double scale = player.getAttributes().hasAttribute(attribute) ? player.getAttributeValue(attribute) : 1;
        return (int) Math.min(MAX_FLIGHT_TICKS, Math.round(stats.flightTicks() * scale));
    }

    public static void init() {
        CHANNEL.messageBuilder(StatePayload.class, 0, NetworkDirection.PLAY_TO_SERVER)
            .encoder(StatePayload::encode).decoder(StatePayload::decode).consumerMainThread(StatePayload::handle).add();
        MinecraftForge.EVENT_BUS.addListener(Wings::tick);
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> AIRBORNE.remove(e.getEntity().getUUID()));
    }

    private static void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player && AIRBORNE.contains(player.getUUID())) {
            if (worn(player).isEmpty()) {
                AIRBORNE.remove(player.getUUID());
            } else {
                player.resetFallDistance();
            }
        }
    }

    /** Client to server: this player started (true) or stopped (false) flying or gliding on wings. */
    public record StatePayload(boolean airborne) {
        void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(this.airborne);
        }

        static StatePayload decode(FriendlyByteBuf buf) {
            return new StatePayload(buf.readBoolean());
        }

        void handle(Supplier<NetworkEvent.Context> context) {
            ServerPlayer player = context.get().getSender();
            if (player == null) {
                return;
            }
            if (this.airborne && !worn(player).isEmpty()) {
                AIRBORNE.add(player.getUUID());
            } else {
                AIRBORNE.remove(player.getUUID());
            }
        }
    }

    private Wings() {}
}
