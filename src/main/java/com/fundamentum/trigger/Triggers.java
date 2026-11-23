package com.fundamentum.trigger;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

/**
 * Left click as a trigger. While an item that takes part is in the main hand, the attack button no longer attacks or
 * breaks blocks: the client reports it going down and up ({@link TriggerClient}) and the item's {@link Handler} gets
 * {@code press} on the click, {@code hold} every tick it stays down (ticks counting from 1), and {@code release} when it
 * is let go, or {@code interrupted} instead when the item leaves the hand or the player dies with it down.
 *
 * <p>An item takes part when its {@link Item} implements {@link Handler}, or through {@link #register} (an item, or any
 * test on the stack, e.g. a data table lookup). Register while the mod is constructed: the client reads the same list to
 * decide whether left click is a trigger.
 */
public final class Triggers {
    /** What a trigger item does. Every method runs on the server; all are optional. */
    public interface Handler {
        default void press(ServerPlayer player, ItemStack stack) {}

        default void hold(ServerPlayer player, ItemStack stack, int ticks) {}

        default void release(ServerPlayer player, ItemStack stack, int ticks) {}

        /** The item left the hand (or the player died) with the trigger still down; {@code release} is not called. */
        default void interrupted(ServerPlayer player, ItemStack stack, int ticks) {}
    }

    private record Entry(Predicate<ItemStack> items, Handler handler) {}

    private static final List<Entry> HANDLERS = new CopyOnWriteArrayList<>();

    private static final class Pull {
        final ItemStack stack;
        final Handler handler;
        int ticks;

        Pull(ItemStack stack, Handler handler) {
            this.stack = stack;
            this.handler = handler;
        }
    }

    private static final Map<UUID, Pull> HELD = new ConcurrentHashMap<>();

    /** Makes left click a trigger for every stack the test accepts. */
    public static void register(Predicate<ItemStack> items, Handler handler) {
        HANDLERS.add(new Entry(items, handler));
    }

    /** Makes left click a trigger for one item. */
    public static void register(Item item, Handler handler) {
        register(stack -> stack.is(item), handler);
    }

    /** The handler for a stack, or null when left click is an ordinary attack with it. */
    public static @Nullable Handler handler(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (stack.getItem() instanceof Handler handler) {
            return handler;
        }
        for (Entry entry : HANDLERS) {
            if (entry.items().test(stack)) {
                return entry.handler();
            }
        }
        return null;
    }

    /** True when left click is a trigger with this stack. */
    public static boolean takesPart(ItemStack stack) {
        return handler(stack) != null;
    }

    /** True while the player holds the trigger down (server side). */
    public static boolean isDown(Player player) {
        return HELD.containsKey(player.getUUID());
    }

    /** Ticks the player has held the trigger down (server side), or 0. */
    public static int ticks(Player player) {
        Pull pull = HELD.get(player.getUUID());
        return pull == null ? 0 : pull.ticks;
    }

    /**
     * Presses ({@code down}) or lets go of a player's trigger, as the trigger packet does: press when an item that
     * takes part is in the main hand, release after. Public for scripted use and tests.
     */
    public static void set(ServerPlayer player, boolean down) {
        Pull pull = HELD.get(player.getUUID());
        if (down && pull == null) {
            ItemStack stack = player.getMainHandItem();
            Handler handler = handler(stack);
            if (handler != null && player.isAlive() && !player.isSpectator()) {
                HELD.put(player.getUUID(), new Pull(stack, handler));
                handler.press(player, stack);
            }
        } else if (!down && pull != null) {
            HELD.remove(player.getUUID());
            pull.handler.release(player, pull.stack, pull.ticks);
        }
    }

    /** Lets go without a release (logout, death, an item swap). */
    public static void clear(ServerPlayer player) {
        Pull pull = HELD.remove(player.getUUID());
        if (pull != null) {
            pull.handler.interrupted(player, pull.stack, pull.ticks);
        }
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(Triggers::payloads);
        NeoForge.EVENT_BUS.addListener(Triggers::tick);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                clear(player);
            }
        });
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                clear(player);
            }
        });
    }

    private static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Pull pull = HELD.get(player.getUUID());
        if (pull == null) {
            return;
        }
        if (player.getMainHandItem() != pull.stack || !player.isAlive()) {
            clear(player);
            return;
        }
        pull.ticks++;
        pull.handler.hold(player, pull.stack, pull.ticks);
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TriggerPayload.TYPE, TriggerPayload.STREAM_CODEC, TriggerPayload::handle);
    }

    /** Client to server: the trigger went down or came back up. */
    public record TriggerPayload(boolean down) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<TriggerPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("fundamentum", "trigger"));
        public static final StreamCodec<ByteBuf, TriggerPayload> STREAM_CODEC = ByteBufCodecs.BOOL.map(TriggerPayload::new, TriggerPayload::down);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(TriggerPayload payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                set(player, payload.down());
            }
        }
    }

    private Triggers() {}
}
