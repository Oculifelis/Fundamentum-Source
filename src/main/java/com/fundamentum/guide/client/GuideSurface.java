package com.fundamentum.guide.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A screen-sized widget that draws a {@link GuidePaint.Layer} behind the other widgets and passes it clicks and
 * scrolls; a click it does not use falls through to the widgets. Client only, per version.
 */
public final class GuideSurface extends AbstractWidget {
    private final GuidePaint.Layer layer;

    public GuideSurface(int w, int h, GuidePaint.Layer layer) {
        super(0, 0, w, h, Component.empty());
        this.layer = layer;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
        this.layer.paint(new GuideGfx(g), mx, my);
    }

    @Override
    public boolean isMouseOver(double mx, double my) {
        return this.layer.over(mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        return button == 0 && this.layer.click(mx, my);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0) this.layer.drag(mx, my);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) this.layer.release(mx, my);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sy) {
        return this.layer.scroll(mx, my, sy);
    }

    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager sounds) {
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
    }
}
