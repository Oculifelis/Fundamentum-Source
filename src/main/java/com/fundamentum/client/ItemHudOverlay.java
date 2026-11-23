package com.fundamentum.client;

import com.fundamentum.api.ItemHud;
import com.fundamentum.hud.HudLayout;
import com.fundamentum.hud.client.HudLayerRenderer;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Draws {@link ItemHud}'s lines as the HUD element {@code fundamentum:item_hud}: asks the providers once a client tick,
 * keeps the lines and measures them only when they change, then each frame draws them where the HUD layout puts the
 * element. Text style comes from {@link ItemHudConfig}; its anchor, x, y and scale are the element's default placement
 * until the player moves it in the HUD editor. Hidden with F1.
 */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ItemHudOverlay {
    public static final ResourceLocation LAYER = new ResourceLocation("fundamentum", "item_hud");
    /** Line spacing; the last line is 8 high (7-pixel glyphs and their shadow). */
    private static final int LINE = 10;
    private static final int TEXT_H = 8;

    private static List<Component> lines = List.of();
    private static int contentW;

    private ItemHudOverlay() {
    }

    /** The element registration, on the mod bus (1.20.1: when the GUI overlays are registered). */
    @Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Layers {
        private Layers() {
        }

        @SubscribeEvent
        public static void layers(RegisterGuiOverlaysEvent event) {
            HudLayout.Placement defaults;
            try {
                defaults = new HudLayout.Placement(ItemHudConfig.ANCHOR.get(), ItemHudConfig.X.get(), ItemHudConfig.Y.get(),
                    ItemHudConfig.SCALE.get().floatValue(), 1);
            } catch (RuntimeException notLoaded) {
                defaults = new HudLayout.Placement(HudBox.Anchor.HOTBAR_RIGHT, 0, 0);
            }
            HudLayerRenderer.register(LAYER, 300, true, defaults, ItemHudOverlay::width, ItemHudOverlay::height, ItemHudOverlay::render);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        List<Component> now = mc.player == null || !ItemHudConfig.ENABLED.get() ? List.of() : ItemHud.lines(mc.player);
        if (now.equals(lines)) return;
        int w = 0;
        for (Component line : now) {
            w = Math.max(w, mc.font.width(line));
        }
        lines = now;
        contentW = w;
    }

    /** The lines on screen now (as of the last tick). */
    public static List<Component> lines() {
        return lines;
    }

    private static int pad() {
        return ItemHudConfig.BACKGROUND.get() ? 3 : 0;
    }

    private static int width() {
        if (lines.isEmpty()) return 0;
        int pad = pad();
        // the backdrop sits 3 pixels out on the left and top, 2 on the right and bottom (the text shadow fills the rest)
        return pad + contentW + Math.max(0, pad - 1);
    }

    private static int height() {
        if (lines.isEmpty()) return 0;
        int pad = pad();
        return pad + (lines.size() - 1) * LINE + TEXT_H + Math.max(0, pad - 1);
    }

    private static void render(GuiGraphics g, float partial) {
        List<Component> shown = lines;
        Minecraft mc = Minecraft.getInstance();
        if (shown.isEmpty() || mc.player == null) {
            return;
        }
        int pad = pad();
        if (pad > 0) {
            int alpha = (int) Math.round(ItemHudConfig.BACKGROUND_OPACITY.get() * 255);
            if (alpha > 0) {
                g.fill(0, 0, width(), height(), HudLayerRenderer.fade(alpha << 24));
            }
        }
        int color = HudLayerRenderer.fade(HudBox.argb(ItemHudConfig.TEXT_COLOR.get(), ItemHudConfig.TEXT_OPACITY.get()));
        boolean shadow = ItemHudConfig.SHADOW.get();
        int y = pad;
        for (Component line : shown) {
            g.drawString(mc.font, line, pad, y, color, shadow);
            y += LINE;
        }
    }
}
