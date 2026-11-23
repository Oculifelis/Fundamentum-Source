package com.fundamentum.trigger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The client half of {@link Triggers}: each tick it reads the attack button while a trigger item is in the main hand and
 * tells the server when it goes down or up; vanilla attacking and block breaking are cancelled for those items. Mods read
 * {@link #isDown}, {@link #ticks} and {@link #pulling} for draw and charge animations, and {@link #onPress} to start
 * client-side effects on the click itself.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class TriggerClient {
    private static final List<Consumer<ItemStack>> PRESS = new CopyOnWriteArrayList<>();
    private static boolean down;
    private static int ticks;
    private static ItemStack stack = ItemStack.EMPTY;
    private static int slot = -1;

    /** Runs on this client the moment the local player's trigger goes down, with the stack in hand. */
    public static void onPress(Consumer<ItemStack> listener) {
        PRESS.add(listener);
    }

    /** True while the local player holds a trigger item in the main hand. */
    public static boolean holdingTrigger(Minecraft mc) {
        return mc.player != null && Triggers.takesPart(mc.player.getMainHandItem());
    }

    /** True while the local player's trigger is down. */
    public static boolean isDown() {
        return down;
    }

    /** Ticks the local player's trigger has been down, or 0. */
    public static int ticks() {
        return ticks;
    }

    /** True while the local player holds the trigger of this stack. */
    public static boolean pulling(ItemStack s) {
        return down && s == stack;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean want = mc.gui.screen() == null && mc.player != null && mc.player.isAlive() && !mc.player.isSpectator()
            && holdingTrigger(mc) && mc.options.keyAttack.isDown();
        if (want && down) {
            ItemStack held = mc.player.getMainHandItem();
            if (held.getItem() != stack.getItem() || mc.player.getInventory().getSelectedSlot() != slot) {
                // swapped items with the trigger down: let go first
                send(false);
            } else {
                // the same item in the same slot, re-sent by the server because its data changed: keep holding
                stack = held;
            }
        }
        if (want != down) {
            send(want);
        }
        ticks = down ? ticks + 1 : 0;
    }

    private static void send(boolean value) {
        Minecraft mc = Minecraft.getInstance();
        down = value;
        stack = value && mc.player != null ? mc.player.getMainHandItem() : ItemStack.EMPTY;
        slot = value && mc.player != null ? mc.player.getInventory().getSelectedSlot() : -1;
        ticks = 0;
        ClientPacketDistributor.sendToServer(new Triggers.TriggerPayload(value));
        if (value) {
            ItemStack pressed = stack;
            PRESS.forEach(listener -> listener.accept(pressed));
        }
    }

    /** Left click with a trigger item is the trigger, not an attack or a block break. */
    @SubscribeEvent
    public static void clickInput(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isAttack() && event.getHand() == InteractionHand.MAIN_HAND && holdingTrigger(Minecraft.getInstance())) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    private TriggerClient() {}
}
