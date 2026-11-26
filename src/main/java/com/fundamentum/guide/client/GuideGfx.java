package com.fundamentum.guide.client;

import com.fundamentum.port.FId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** {@link GuidePaint} on this version's GUI drawing. Client only, per version. */
final class GuideGfx implements GuidePaint {
    private final GuiGraphics g;

    GuideGfx(GuiGraphics g) {
        this.g = g;
    }

    @Override
    public Font font() {
        return Minecraft.getInstance().font;
    }

    @Override
    public void fill(int x0, int y0, int x1, int y1, int argb) {
        this.g.fill(x0, y0, x1, y1, argb);
    }

    @Override
    public void text(Component text, int x, int y, int argb, boolean shadow) {
        this.g.drawString(font(), text, x, y, argb, shadow);
    }

    @Override
    public void item(ItemStack stack, int x, int y) {
        this.g.renderItem(stack, x, y);
    }

    @Override
    public void texture(FId texture, int x, int y, int w, int h, int texW, int texH) {
        this.g.blit(texture.vanilla(), x, y, 0, 0, w, h, texW, texH);
    }

    @Override
    public void scissor(int x0, int y0, int x1, int y1) {
        this.g.enableScissor(x0, y0, x1, y1);
    }

    @Override
    public void endScissor() {
        this.g.disableScissor();
    }

    @Override
    public void tooltip(Component text, int mx, int my) {
        // deferred, so a tooltip from the frame (drawn first) still shows over the page
        if (Minecraft.getInstance().screen != null) Minecraft.getInstance().screen.setTooltipForNextRenderPass(text);
    }

    @Override
    public void tooltip(ItemStack stack, int mx, int my) {
        this.g.renderTooltip(font(), stack, mx, my);
    }

    @Override
    public void push(float tx, float ty, float sx, float sy) {
        this.g.pose().pushPose();
        this.g.pose().translate(tx, ty, 0);
        this.g.pose().scale(sx, sy, 1);
    }

    @Override
    public void pop() {
        this.g.pose().popPose();
    }

    @Override
    public boolean entity(FId type, int x0, int y0, int x1, int y1, int feet, int mx, int my, float zoom) {
        net.minecraft.world.entity.LivingEntity living = GuideCanvas.entity(type);
        if (living == null) return false;
        float size = Math.max(living.getBbHeight(), living.getBbWidth() * 0.9F);
        int scale = Math.max(4, (int) (Math.min(x1 - x0, y1 - y0) * 0.72F * zoom / Math.max(0.3F, size)));
        net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventoryFollowsMouse(this.g, x0, y0, x1, y1, scale, 0.0625F,
            mx, my, living);
        return true;
    }
}
