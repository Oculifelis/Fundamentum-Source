package com.fundamentum.guide.client;

import com.fundamentum.guide.GuideTheme;
import com.fundamentum.port.FId;
import com.fundamentum.port.FVanilla;
import com.fundamentum.recipeview.ViewHover;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Draws a laid-out guide page in a scrolling box, in the book's theme; clicking a link opens it. Client only, per version. */
public final class GuideCanvas extends AbstractWidget {
    /** The item under the mouse in the last frame drawn, so recipe viewers' keys (show recipes, show uses) work on it. */
    private static volatile ViewHover.Hit hover;
    private static volatile long hoverAt;

    static {
        ViewHover.source((screen, mx, my) -> {
            ViewHover.Hit h = hover;
            if (h == null || screen != Minecraft.getInstance().gui.screen() || System.currentTimeMillis() - hoverAt > 250) return null;
            return mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h() ? h : null;
        });
    }

    private static final Map<FId, LivingEntity> ENTITIES = new HashMap<>();
    private final Consumer<FId> onLink;
    private final GuideTheme theme;
    private GuideLayout.Result content = new GuideLayout.Result(List.of(), 0);
    private double scroll;
    private float yaw = com.fundamentum.multiblock.client.GuiMultiblockRenderer.YAW;

    public GuideCanvas(int x, int y, int w, int h, Consumer<FId> onLink) {
        this(x, y, w, h, GuideTheme.DEFAULT, onLink);
    }

    public GuideCanvas(int x, int y, int w, int h, GuideTheme theme, Consumer<FId> onLink) {
        super(x, y, w, h, Component.empty());
        this.onLink = onLink;
        this.theme = theme;
    }

    /** The width pages are laid out in (room left for the scroll bar). */
    public int contentWidth() {
        return this.width - 12;
    }

    public void setContent(GuideLayout.Result content) {
        this.content = content;
        this.scroll = 0;
        this.yaw = com.fundamentum.multiblock.client.GuiMultiblockRenderer.YAW;
    }

    /** How far down the page is scrolled, 0 to 1. */
    public double scrollFraction() {
        int max = maxScroll();
        return max <= 0 ? 0 : this.scroll / max;
    }

    public void setScrollFraction(double f) {
        this.scroll = Math.max(0, Math.min(maxScroll(), f * maxScroll()));
    }

    private int maxScroll() {
        return Math.max(0, this.content.height() - this.height + 4);
    }

    private static ItemStack cycle(List<ItemStack> stacks) {
        return stacks.get((int) ((System.currentTimeMillis() / 1000) % stacks.size()));
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        GuidePaint p = new GuideGfx(g);
        GuideTheme.Page pal = this.theme.page();
        int x0 = getX() + 1;
        int y0 = getY() + 2 - (int) this.scroll;
        p.scissor(getX(), getY(), getX() + this.width, getY() + this.height);
        ItemStack hovered = ItemStack.EMPTY;
        int hx = 0, hy = 0;
        boolean inside = isMouseOver(mx, my);
        for (GuideLayout.Op op : this.content.ops()) {
            if (op instanceof GuideLayout.Text t) {
                p.text(t.text(), x0 + t.x(), y0 + t.y(), t.colour(), pal.shadow());
            } else if (op instanceof GuideLayout.Box b) {
                GuideSkin.rect(p, x0 + b.x(), y0 + b.y(), b.w(), b.h(), b.colour());
            } else if (op instanceof GuideLayout.Slot s) {
                GuideSkin.slot(p, this.theme, x0 + s.x(), y0 + s.y(), s.size());
            } else if (op instanceof GuideLayout.Well w) {
                GuideSkin.well(p, this.theme, x0 + w.x(), y0 + w.y(), w.w(), w.h(), this.theme.well().fill());
            } else if (op instanceof GuideLayout.Arrow a) {
                GuideSkin.craftArrow(p, this.theme, x0 + a.x(), y0 + a.y());
            } else if (op instanceof GuideLayout.Rule r) {
                GuideSkin.rule(p, x0 + r.x(), y0 + r.y(), r.w(), r.dark(), r.light());
            } else if (op instanceof GuideLayout.ItemOp i) {
                ItemStack s = cycle(i.stacks());
                int ix = x0 + i.x();
                int iy = y0 + i.y();
                p.item(s, ix, iy);
                if (inside && mx >= ix && mx < ix + 16 && my >= iy && my < iy + 16) {
                    hovered = s;
                    hx = ix;
                    hy = iy;
                }
            } else if (op instanceof GuideLayout.Image im) {
                p.texture(im.texture(), x0 + im.x(), y0 + im.y(), im.w(), im.h(), im.w(), im.h());
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
                    p.text(Component.literal("? " + e.type()), ex + 2, ey + 2, GuideLayout.MISSING, false);
                }
            }
        }
        p.endScissor();
        if (maxScroll() > 0) {
            int bar = Math.max(10, (this.height - 2) * this.height / Math.max(1, this.content.height() + 4));
            GuideSkin.scrollbar(p, this.theme, getX() + this.width - 7, getY(), this.height, bar, this.scroll / maxScroll());
        }
        if (!hovered.isEmpty()) p.tooltip(hovered, mx, my);
        hover = hovered.isEmpty() ? null : new ViewHover.Hit(hovered, hx, hy, 16, 16);
        hoverAt = System.currentTimeMillis();
    }

    static LivingEntity entity(FId id) {
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
        double mx = event.x() - getX() - 1;
        double my = event.y() - getY() - 2 + this.scroll;
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
