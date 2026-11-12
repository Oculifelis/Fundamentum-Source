package com.fundamentum.hud.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.fundamentum.client.HudBox;
import com.fundamentum.hud.HudLayers;
import com.fundamentum.hud.HudLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The HUD editor: every HUD element drawn through {@link HudLayerRenderer} shows as a box over the live HUD. Drag a box
 * to move it (it snaps to the nearest of nine screen anchors), scroll over it to resize it, shift and scroll to fade it,
 * right click to turn it on or off. Reset puts the selected element back, Reset all puts every element back. Done
 * saves {@code config/fundamentum-hud-layout.json}. Opened from the Fundamentum settings screen or the HUD editor key.
 */
public final class HudEditorScreen extends Screen {
    private static final int ON = 0xFFFFFFFF;
    private static final int OFF = 0xFFFF5555;
    private static final int PICKED = 0xFFFFDD33;

    private final Screen parent;
    private Identifier selected;
    private boolean dragging;
    private int grabX;
    private int grabY;
    private Button resetOne;

    public HudEditorScreen(Screen parent) {
        super(Component.translatable("fundamentum.hud_editor.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int bw = 80;
        int y = this.height / 2 - 10;
        int x = this.width / 2 - (bw * 3 + 8) / 2;
        this.resetOne = addRenderableWidget(Button.builder(Component.translatable("fundamentum.hud_editor.reset"), b -> {
            if (this.selected != null) {
                HudLayout.set(this.selected.toString(), null);
                HudLayers.setEnabled(this.selected, null);
            }
        }).bounds(x, y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("fundamentum.hud_editor.reset_all"), b -> {
            HudLayout.resetAll();
            for (Identifier id : elements()) HudLayers.setEnabled(id, null);
        }).bounds(x + bw + 4, y, bw, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + (bw + 4) * 2, y, bw, 20).build());
    }

    /** Every layer the editor can show, in draw order. */
    static List<Identifier> elements() {
        List<Identifier> out = new ArrayList<>();
        for (Identifier id : HudLayers.ordered()) if (HudLayerRenderer.isElement(id)) out.add(id);
        return out;
    }

    /** The element under a point: the smallest box that holds it, so a small element wins over a screen-sized one. */
    private Identifier at(double mx, double my) {
        Identifier best = null;
        long bestArea = Long.MAX_VALUE;
        for (Identifier id : elements()) {
            int[] b = HudLayerRenderer.box(id, this.width, this.height);
            if (b == null || mx < b[0] || my < b[1] || mx >= b[0] + b[2] || my >= b[1] + b[3]) continue;
            long area = (long) b[2] * b[3];
            if (area < bestArea) {
                best = id;
                bestArea = area;
            }
        }
        return best;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        // nothing: the live HUD stays in view
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        Identifier hover = at(mouseX, mouseY);
        for (Identifier id : elements()) {
            int[] b = HudLayerRenderer.box(id, this.width, this.height);
            if (b == null) continue;
            boolean on = HudLayers.enabled(id);
            int color = id.equals(this.selected) ? PICKED : on ? ON : OFF;
            g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], id.equals(hover) ? 0x40FFFFFF : 0x20FFFFFF);
            g.outline(b[0], b[1], b[2], b[3], color);
            String label = id.getPath() + (on ? "" : " (off)");
            int ly = b[1] >= 10 ? b[1] - 9 : b[1] + b[3] + 1;
            g.text(this.font, Component.literal(label), b[0], ly, color, true);
        }
        String info;
        if (this.selected != null) {
            HudLayout.Placement p = HudLayout.get(this.selected.toString());
            info = String.format(Locale.ROOT, "%s  %s  x %d  y %d  scale %.2f  opacity %.2f", this.selected,
                p.anchor().name().toLowerCase(Locale.ROOT), p.x(), p.y(), p.scale(), p.opacity());
        } else {
            info = Component.translatable("fundamentum.hud_editor.help").getString();
        }
        int iw = this.font.width(info);
        int iy = this.height / 2 + 16;
        g.fill(this.width / 2 - iw / 2 - 3, iy - 2, this.width / 2 + iw / 2 + 3, iy + 10, 0xA0000000);
        g.text(this.font, Component.literal(info), this.width / 2 - iw / 2, iy, 0xFFFFFFFF, false);
        this.resetOne.active = this.selected != null;
        super.extractRenderState(g, mouseX, mouseY, a);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        Identifier id = at(event.x(), event.y());
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (id != null) HudLayers.setEnabled(id, !HudLayers.enabled(id));
            return id != null;
        }
        this.selected = id;
        if (id == null) return false;
        int[] b = HudLayerRenderer.box(id, this.width, this.height);
        this.grabX = (int) event.x() - b[0];
        this.grabY = (int) event.y() - b[1];
        this.dragging = true;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.dragging && this.selected != null) {
            moveTo(this.selected, (int) event.x() - this.grabX, (int) event.y() - this.grabY);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        this.dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        Identifier id = at(x, y);
        if (id == null) id = this.selected;
        if (id == null || scrollY == 0) return super.mouseScrolled(x, y, scrollX, scrollY);
        this.selected = id;
        String key = id.toString();
        HudLayout.Placement p = HudLayout.get(key);
        float step = scrollY > 0 ? 0.05F : -0.05F;
        if (Minecraft.getInstance().hasShiftDown()) HudLayout.set(key, p.withOpacity(p.opacity() + step));
        else HudLayout.set(key, p.withScale(p.scale() + step));
        return true;
    }

    /** Puts an element's top left at {@code left}, {@code top} (kept on screen), against the nearest anchor. */
    public void moveTo(Identifier id, int left, int top) {
        String key = id.toString();
        int[] b = HudLayerRenderer.box(id, this.width, this.height);
        if (b == null) return;
        HudLayout.Placement p = HudLayout.get(key);
        if (!HudLayerRenderer.isSized(id)) {
            HudLayout.set(key, p.withAnchor(HudBox.Anchor.TOP_LEFT, left, top));
            return;
        }
        left = Math.max(0, Math.min(this.width - b[2], left));
        top = Math.max(0, Math.min(this.height - b[3], top));
        HudBox.Anchor anchor = HudLayout.nearest(this.width, this.height, left, top, b[2], b[3]);
        int[] off = HudLayout.offsetFor(anchor, this.width, this.height, b[2], b[3], HudLayerRenderer.hotbarX(this.width), left, top);
        HudLayout.set(key, p.withAnchor(anchor, off[0], off[1]));
    }

    /** Selects an element (for tests and keyboard use). */
    public void select(Identifier id) {
        this.selected = id;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        HudLayers.saveDefault();
        Minecraft.getInstance().gui.setScreen(this.parent);
    }
}
