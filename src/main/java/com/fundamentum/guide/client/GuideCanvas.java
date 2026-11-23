package com.fundamentum.guide.client;

import com.fundamentum.port.FId;
import com.fundamentum.port.FVanilla;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Draws a laid-out guide page in a scrolling box; clicking a link opens it. Client only, per version. */
public final class GuideCanvas extends AbstractWidget {
    private static final Map<FId, LivingEntity> ENTITIES = new HashMap<>();
    private final Consumer<FId> onLink;
    private GuideLayout.Result content = new GuideLayout.Result(List.of(), 0);
    private double scroll;
    private float yaw = com.fundamentum.multiblock.client.GuiMultiblockRenderer.YAW;

    public GuideCanvas(int x, int y, int w, int h, Consumer<FId> onLink) {
        super(x, y, w, h, Component.empty());
        this.onLink = onLink;
    }

    /** The width pages are laid out in (room left for the scroll bar). */
    public int contentWidth() {
        return this.width - 10;
    }

    public void setContent(GuideLayout.Result content) {
        this.content = content;
        this.scroll = 0;
        this.yaw = com.fundamentum.multiblock.client.GuiMultiblockRenderer.YAW;
    }

    private int maxScroll() {
        return Math.max(0, this.content.height() - this.height + 8);
    }

    private static ItemStack cycle(List<ItemStack> stacks) {
        return stacks.get((int) ((System.currentTimeMillis() / 1000) % stacks.size()));
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        Font font = Minecraft.getInstance().font;
        int x0 = getX() + 4;
        int y0 = getY() + 4 - (int) this.scroll;
        g.fill(getX(), getY(), getX() + this.width, getY() + this.height, 0x90000000);
        g.enableScissor(getX(), getY(), getX() + this.width, getY() + this.height);
        ItemStack hovered = ItemStack.EMPTY;
        boolean inside = isMouseOver(mx, my);
        for (GuideLayout.Op op : this.content.ops()) {
            if (op instanceof GuideLayout.Text t) {
                g.text(font, t.text(), x0 + t.x(), y0 + t.y(), t.colour(), false);
            } else if (op instanceof GuideLayout.Box b) {
                g.fill(x0 + b.x(), y0 + b.y(), x0 + b.x() + b.w(), y0 + b.y() + b.h(), b.colour());
            } else if (op instanceof GuideLayout.ItemOp i) {
                ItemStack s = cycle(i.stacks());
                int ix = x0 + i.x();
                int iy = y0 + i.y();
                g.item(s, ix, iy);
                if (inside && mx >= ix && mx < ix + 16 && my >= iy && my < iy + 16) hovered = s;
            } else if (op instanceof GuideLayout.Image im) {
                g.blit(RenderPipelines.GUI_TEXTURED, im.texture().vanilla(), x0 + im.x(), y0 + im.y(), 0, 0, im.w(), im.h(), im.w(), im.h());
            } else if (op instanceof GuideLayout.Blocks b) {
                com.fundamentum.multiblock.client.GuiMultiblockRenderer.submit(g, com.fundamentum.multiblock.client.MultiblockScene.of(b.pattern()),
                    x0 + b.x(), y0 + b.y(), b.w(), b.h(), this.yaw, com.fundamentum.multiblock.client.GuiMultiblockRenderer.PITCH);
            } else if (op instanceof GuideLayout.Entity e) {
                LivingEntity living = entity(e.type());
                int ex = x0 + e.x();
                int ey = y0 + e.y();
                if (living != null) {
                    InventoryScreen.extractEntityInInventoryFollowsMouse(g, ex, ey, ex + e.w(), ey + e.h(), e.scale(), 0.0625F, mx, my, living);
                } else {
                    g.text(font, Component.literal("? " + e.type()), ex + 2, ey + 2, GuideLayout.MISSING, false);
                }
            }
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            int bar = Math.max(10, this.height * this.height / Math.max(1, this.content.height() + 8));
            int by = getY() + (int) ((this.height - bar) * (this.scroll / maxScroll()));
            g.fill(getX() + this.width - 4, getY(), getX() + this.width, getY() + this.height, 0x40FFFFFF);
            g.fill(getX() + this.width - 4, by, getX() + this.width, by + bar, 0xC0FFFFFF);
        }
        if (!hovered.isEmpty()) g.setTooltipForNextFrame(font, hovered, mx, my);
    }

    private static LivingEntity entity(FId id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        LivingEntity cached = ENTITIES.get(id);
        if (cached != null && cached.level() == mc.level) return cached;
        EntityType<?> type = FVanilla.entityType(id);
        if (type == null) return null;
        try {
            Entity e = type.create(mc.level, EntitySpawnReason.LOAD);
            if (e instanceof LivingEntity living) {
                ENTITIES.put(id, living);
                return living;
            }
        } catch (RuntimeException ex) {
            return null;
        }
        return null;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x() - getX() - 4;
        double my = event.y() - getY() - 4 + this.scroll;
        for (GuideLayout.Op op : this.content.ops()) {
            if (op instanceof GuideLayout.Link l && mx >= l.x() && mx < l.x() + l.w() && my >= l.y() && my < l.y() + l.h()) {
                this.onLink.accept(l.target());
                return;
            }
        }
    }

    /** Dragging turns the multiblock views on the page. */
    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        this.yaw = (float) ((this.yaw + dx * 2) % 360);
    }

    /** How far the multiblock views are turned (for tests). */
    public float yaw() {
        return this.yaw;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (!isMouseOver(mx, my)) return false;
        this.scroll = Math.max(0, Math.min(maxScroll(), this.scroll - sy * 12));
        return true;
    }

    @Override
    public void playDownSound(net.minecraft.client.sounds.SoundManager sounds) {
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
