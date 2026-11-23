package com.fundamentum.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/**
 * Item HUD (1.25.7): a few lines of plain text right of the hotbar while the player holds an item that asks for them,
 * the way Ostentum's Engineer's Crossbow shows its mode, bolts loaded and ammo. A mod registers a {@link Provider} for
 * its items; Fundamentum draws the lines in the game's font, placed and styled by the player's client config
 * (config/fundamentum-hud-client.toml, or Mods, Fundamentum, Config: anchor, offsets, scale, text colour and opacity,
 * shadow, backdrop), in the spirit of Jade's. Colours and styles a line sets itself win over the configured colour.
 * <p>
 * Lag-free: providers are asked once per client tick (main hand first, then the off hand; the first provider with
 * lines wins), never per frame; each frame only draws the cached lines. Register from client setup or any time after
 * (the list is thread-safe). Usable from KubeJS (client scripts):
 * {@code ItemHud.registerItem(Item.of('minecraft:bow').item, (player, stack) => [Text.of('Bow')])}.
 */
public final class ItemHud {
    /** Supplies the lines for one held stack. Client side, once a tick while a matching item is held. */
    @FunctionalInterface
    public interface Provider {
        /** The lines to show, top to bottom; an empty list shows nothing (and lets the next provider try). */
        List<Component> lines(Player player, ItemStack stack);
    }

    private record Entry(Predicate<ItemStack> items, Provider provider) {
    }

    private static final List<Entry> PROVIDERS = new CopyOnWriteArrayList<>();

    private ItemHud() {
    }

    /** Shows {@code provider}'s lines while a stack matching {@code items} is held. */
    public static void register(Predicate<ItemStack> items, Provider provider) {
        PROVIDERS.add(new Entry(items, provider));
    }

    /** Shows {@code provider}'s lines while {@code item} is held (looked up when asked, so a deferred item is fine). */
    public static void registerItem(ItemLike item, Provider provider) {
        register(stack -> stack.is(item.asItem()), provider);
    }

    /** The lines for what {@code player} holds right now: main hand first, then the off hand; empty when none. */
    public static List<Component> lines(Player player) {
        if (PROVIDERS.isEmpty()) {
            return List.of();
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty()) {
                continue;
            }
            for (Entry e : PROVIDERS) {
                if (e.items().test(stack)) {
                    List<Component> lines = e.provider().lines(player, stack);
                    if (lines != null && !lines.isEmpty()) {
                        return lines;
                    }
                }
            }
        }
        return List.of();
    }
}
