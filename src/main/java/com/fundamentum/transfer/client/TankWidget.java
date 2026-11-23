package com.fundamentum.transfer.client;

import com.fundamentum.transfer.FluidTank;
import com.fundamentum.transfer.Transfer;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Draws a fluid tank in a screen: the fluid's still texture, tinted, tiled from the bottom up to the filled height,
 * with an optional dark frame, and the usual name and amount tooltip. Feed it a {@link FluidTank} (a menu's own copy)
 * or a position and index synced by {@link Transfer#track}. Client only.
 */
public final class TankWidget {
    private TankWidget() { }

    /** Draws a synced tank (see {@link Transfer#clientTank}). */
    public static void render(GuiGraphicsExtractor g, int x, int y, int w, int h, BlockPos pos, int index) {
        Transfer.TankView v = Transfer.clientTank(pos, index);
        render(g, x, y, w, h, v.fluid(), v.amount(), v.capacity());
    }

    public static void render(GuiGraphicsExtractor g, int x, int y, int w, int h, FluidTank tank) {
        render(g, x, y, w, h, tank.fluid(), tank.amount(), tank.capacity());
    }

    /** Draws {@code amount} of {@code fluid} out of {@code capacity} into the box. */
    public static void render(GuiGraphicsExtractor g, int x, int y, int w, int h, Fluid fluid, int amount, int capacity) {
        g.fill(x, y, x + w, y + h, 0xFF1E1E1E);
        if (fluid == null || fluid == Fluids.EMPTY || amount <= 0 || capacity <= 0) return;
        int filled = Math.max(1, Math.min(h, (int) ((long) h * amount / capacity)));
        var model = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid.defaultFluidState());
        TextureAtlasSprite sprite = model.stillMaterial().sprite();
        var tint = model.fluidTintSource();
        int color = tint == null ? -1 : tint.color(fluid.defaultFluidState());
        color = 0xFF000000 | color;
        int top = y + h - filled;
        g.enableScissor(x, top, x + w, y + h);
        for (int ty = y + h - 16; ty > top - 16; ty -= 16) {
            for (int tx = x; tx < x + w; tx += 16) g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, tx, ty, 16, 16, color);
        }
        g.disableScissor();
    }

    /** True when the mouse is over the box. */
    public static boolean hovered(int x, int y, int w, int h, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** Shows the tank tooltip next frame when the mouse is over the box. */
    public static void tooltip(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY, Fluid fluid, int amount, int capacity) {
        if (!hovered(x, y, w, h, mouseX, mouseY)) return;
        List<Component> lines = Transfer.tankTooltip(fluid, amount, capacity);
        g.setComponentTooltipForNextFrame(Minecraft.getInstance().font, lines, mouseX, mouseY);
    }
}
