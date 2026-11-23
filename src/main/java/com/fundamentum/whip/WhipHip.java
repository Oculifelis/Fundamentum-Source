package com.fundamentum.whip;

import com.fundamentum.config.Timers;
import com.fundamentum.port.FLife;
import com.fundamentum.net.Net;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import com.fundamentum.net.IPayloadContext;

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
            Net.sendTrackingAndSelf(CHANNEL, player, new HipPayload(player.getId(), now));
        }
    }

    private static final SimpleChannel CHANNEL = Net.channel("whip_hip", "1");

    static void init(IEventBus modBus) {
        Net.toClient(CHANNEL, 0, HipPayload.class, HipPayload::encode, HipPayload::decode, HipPayload::handle);
        FLife.handsChanged(WhipHip::update);
        FLife.inventoryChanged(WhipHip::update);
        Timers.perPlayer("whip_hip_recheck_seconds", 30, WhipHip::update);
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> SENT.remove(e.getEntity().getUUID()));
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.StartTracking e) -> {
            if (e.getTarget() instanceof ServerPlayer target && e.getEntity() instanceof ServerPlayer watcher) {
                Optional<ResourceLocation> hip = SENT.get(target.getUUID());
                if (hip != null && hip.isPresent()) {
                    Net.sendTo(CHANNEL, watcher, new HipPayload(target.getId(), hip));
                }
            }
        });
    }

    /** Server to clients: the whip at this player's hip, or none. */
    public record HipPayload(int entity, Optional<ResourceLocation> item) {
        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(this.entity);
            buf.writeOptional(this.item, FriendlyByteBuf::writeResourceLocation);
        }

        static HipPayload decode(FriendlyByteBuf buf) {
            return new HipPayload(buf.readVarInt(), buf.readOptional(FriendlyByteBuf::readResourceLocation));
        }


        static void handle(HipPayload payload, IPayloadContext context) {
            WhipsClient.hip(payload.entity(), payload.item().orElse(null));
        }
    }

    private WhipHip() {}
}
