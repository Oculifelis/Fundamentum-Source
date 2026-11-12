package com.fundamentum.recipeview.rei;

import com.fundamentum.multiblock.client.GuiMultiblockRenderer;
import com.fundamentum.multiblock.client.MultiblockScene;
import com.fundamentum.recipeview.ViewCategory;
import com.fundamentum.recipeview.ViewRecipe;
import com.fundamentum.recipeview.ViewSlot;
import java.util.ArrayList;
import java.util.List;
import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Label;
import me.shedaniel.rei.api.client.gui.widgets.Slot;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.network.chat.Component;

/** A declared {@link ViewCategory} as a REI category; the area sits 4 pixels inside REI's recipe frame. */
final class ReiViewCategory implements DisplayCategory<ReiViewDisplay> {
    private static final int PAD = 4;
    final ViewCategory<?> view;
    final CategoryIdentifier<ReiViewDisplay> id;

    ReiViewCategory(ViewCategory<?> view) {
        this.view = view;
        this.id = CategoryIdentifier.of(view.namespace(), view.path());
    }

    @Override public CategoryIdentifier<? extends ReiViewDisplay> getCategoryIdentifier() { return id; }
    @Override public Component getTitle() { return view.title(); }
    @Override public Renderer getIcon() { return EntryStacks.of(view.icon()); }
    @Override public int getDisplayHeight() { return view.height() + PAD * 2; }
    @Override public int getDisplayWidth(ReiViewDisplay display) { return view.width() + PAD * 2; }

    @Override
    public List<Widget> setupDisplay(ReiViewDisplay display, Rectangle bounds) {
        int x0 = bounds.x + PAD, y0 = bounds.y + PAD;
        List<Widget> w = new ArrayList<>();
        w.add(Widgets.createRecipeBase(bounds));
        ViewCategory.Arrow a = view.arrow();
        if (a != null) {
            var arrow = Widgets.createArrow(new Point(x0 + a.x(), y0 + a.y()));
            if (a.ticks() > 0) arrow.animationDurationTicks(a.ticks());
            w.add(arrow);
        }
        for (ViewSlot s : view.slots()) {
            Slot slot = Widgets.createSlot(new Point(x0 + s.x() + 1, y0 + s.y() + 1)).entries(display.entries(s));
            if (s.role() == ViewSlot.Role.OUTPUT) slot.markOutput(); else slot.markInput();
            w.add(slot);
        }
        for (ViewRecipe.Blocks b : display.recipe.blocks()) {
            MultiblockScene.Scene scene = MultiblockScene.of(b.pattern());
            w.add(Widgets.createDrawableWidget((g, mx, my, delta) -> GuiMultiblockRenderer.submit(g, scene, x0 + b.x(), y0 + b.y(), b.w(), b.h(),
                GuiMultiblockRenderer.YAW, GuiMultiblockRenderer.PITCH)));
        }
        for (ViewRecipe.Text t : display.recipe.text()) {
            Label label;
            if (t.x() < 0) label = Widgets.createLabel(new Point(x0 + view.width() / 2, y0 + t.y()), t.text()).centered();
            else label = Widgets.createLabel(new Point(x0 + t.x(), y0 + t.y()), t.text()).leftAligned();
            w.add(label.noShadow().color(t.colour(), 0xFFBBBBBB));
        }
        return w;
    }
}
