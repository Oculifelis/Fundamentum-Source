package com.fundamentum.client;

import com.fundamentum.api.ItemHud;
import java.util.List;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Draws {@link ItemHud}'s lines: asks the providers once a client tick and keeps the lines, then each frame draws them
 * against the configured anchor ({@link ItemHudConfig}) in the game's font. Hidden with F1.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class ItemHudOverlay {
    /** Line spacing; the last line is 8 high (7-pixel glyphs and their shadow). */
    private static final int LINE = 10;
    private static final int TEXT_H = 8;

    private static List<Component> lines = List.of();

    private ItemHudOverlay() {
    }

    @SubscribeEvent
    public static void layers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, ResourceLocation.fromNamespaceAndPath("fundamentum", "item_hud"), ItemHudOverlay::render);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        lines = mc.player == null || !ItemHudConfig.ENABLED.get() ? List.of() : ItemHud.lines(mc.player);
    }

    /** The lines on screen now (as of the last tick). */
    public static List<Component> lines() {
        return lines;
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        List<Component> shown = lines;
        Minecraft mc = Minecraft.getInstance();
        if (shown.isEmpty() || mc.options.hideGui || mc.player == null) {
            return;
        }
        Font font = mc.font;
        int contentW = 0;
        for (Component line : shown) {
            contentW = Math.max(contentW, font.width(line));
        }
        // the backdrop sits 3 pixels out on the left and top, 2 on the right and bottom (the text shadow fills the rest)
        int pad = ItemHudConfig.BACKGROUND.get() ? 3 : 0;
        int w = pad + contentW + Math.max(0, pad - 1);
        int h = pad + (shown.size() - 1) * LINE + TEXT_H + Math.max(0, pad - 1);
        float s = ItemHudConfig.SCALE.get().floatValue();
        HumanoidArm offArm = mc.player.getMainArm().getOpposite();
        int hotbarX = HudBox.hotbarRight(g.guiWidth(), offArm == HumanoidArm.RIGHT && !mc.player.getOffhandItem().isEmpty(),
            offArm == HumanoidArm.LEFT && mc.options.attackIndicator().get() == AttackIndicatorStatus.HOTBAR);
        int[] at = HudBox.place(ItemHudConfig.ANCHOR.get(), g.guiWidth(), g.guiHeight(), (int) Math.ceil(w * s), (int) Math.ceil(h * s),
            hotbarX, ItemHudConfig.X.get(), ItemHudConfig.Y.get());
        g.pose().pushPose();
        g.pose().translate(at[0], at[1], 0);
        g.pose().scale(s, s, 1);
        if (pad > 0) {
            int alpha = (int) Math.round(ItemHudConfig.BACKGROUND_OPACITY.get() * 255);
            if (alpha > 0) {
                g.fill(0, 0, w, h, alpha << 24);
            }
        }
        int color = HudBox.argb(ItemHudConfig.TEXT_COLOR.get(), ItemHudConfig.TEXT_OPACITY.get());
        boolean shadow = ItemHudConfig.SHADOW.get();
        int y = pad;
        for (Component line : shown) {
            g.drawString(font, line, pad, y, color, shadow);
            y += LINE;
        }
        g.pose().popPose();
    }
}
