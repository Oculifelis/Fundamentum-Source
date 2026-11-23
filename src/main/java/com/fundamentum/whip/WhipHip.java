package com.fundamentum.whip;

import com.fundamentum.config.Timers;
import com.fundamentum.port.FLife;
import io.netty.buffer.ByteBuf;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The whip at a player's hip (1.22.0): the first whip in the hotbar ({@link WhipDefinition#hipCoil}) while another item
 * is held. Other players' hotbars never reach a client, so the server tells everyone tracking the player which it is.
 */
public final class WhipHip {
    private static final Map<UUID, Optional<ResourceLocation>> SENT = new ConcurrentHashMap<>();

    static Optional<ResourceLocation> hip(ServerPlayer player) {
        if (Whips.definition(player.getMainHandItem()) != null) {
            return Optional.empty();
        }
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            WhipDefinition whip = Whips.definition(stack);
            if (whip != null && whip.hipCoil) {
                return Optional.of(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            }
        }
        return Optional.empty();
    }

    /** Sends the hip whip when it changed: after hotbar or held item changes, with a slow safety net (whip_hip_recheck_seconds). */
    private static void update(ServerPlayer player) {
        Optional<ResourceLocation> now = player.isSpectator() ? Optional.empty() : hip(player);
        if (!now.equals(SENT.get(player.getUUID()))) {
            SENT.put(player.getUUID(), now);
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new HipPayload(player.getId(), now));
        }
    }

    static void init(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1").playToClient(HipPayload.TYPE, HipPayload.STREAM_CODEC, HipPayload::handle));
        FLife.handsChanged(WhipHip::update);
        FLife.inventoryChanged(WhipHip::update);
        Timers.perPlayer("whip_hip_recheck_seconds", 30, WhipHip::update);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> SENT.remove(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.StartTracking e) -> {
            if (e.getTarget() instanceof ServerPlayer target && e.getEntity() instanceof ServerPlayer watcher) {
                Optional<ResourceLocation> hip = SENT.get(target.getUUID());
                if (hip != null && hip.isPresent()) {
                    PacketDistributor.sendToPlayer(watcher, new HipPayload(target.getId(), hip));
                }
            }
        });
    }

    /** Server to clients: the whip at this player's hip, or none. */
    public record HipPayload(int entity, Optional<ResourceLocation> item) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HipPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("fundamentum", "whip_hip"));
        public static final StreamCodec<ByteBuf, HipPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, HipPayload::entity, ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), HipPayload::item, HipPayload::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(HipPayload payload, IPayloadContext context) {
            WhipsClient.hip(payload.entity(), payload.item().orElse(null));
        }
    }

    private WhipHip() {}
}
