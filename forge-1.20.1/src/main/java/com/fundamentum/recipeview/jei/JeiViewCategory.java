package com.fundamentum.recipeview.jei;

import com.fundamentum.multiblock.client.GuiMultiblockRenderer;
import com.fundamentum.multiblock.client.MultiblockScene;
import com.fundamentum.recipeview.ViewCategory;
import com.fundamentum.recipeview.ViewFluid;
import com.fundamentum.recipeview.ViewRecipe;
import com.fundamentum.recipeview.ViewSlot;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.placement.HorizontalAlignment;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** A declared {@link ViewCategory} as a JEI category. Slots sit with their 18x18 frame's corner at the declared x, y. */
final class JeiViewCategory implements IRecipeCategory<ViewRecipe> {
    final ViewCategory<?> view;
    final RecipeType<ViewRecipe> type;
    private final IDrawable icon;

    JeiViewCategory(ViewCategory<?> view, IGuiHelper gui) {
        this.view = view;
        this.type = RecipeType.create(view.namespace(), view.path(), ViewRecipe.class);
        ItemStack i = view.icon();
        this.icon = i.isEmpty() ? null : gui.createDrawableItemStack(i);
    }

    @Override public RecipeType<ViewRecipe> getRecipeType() { return type; }
    @Override public Component getTitle() { return view.title(); }
    @Override public IDrawable getIcon() { return icon; }
    @Override public int getWidth() { return view.width(); }
    @Override public int getHeight() { return view.height(); }

    @Override
    public void setRecipe(IRecipeLayoutBuilder b, ViewRecipe r, IFocusGroup focuses) {
        for (ViewSlot s : view.slots()) {
            RecipeIngredientRole role = s.role() == ViewSlot.Role.OUTPUT ? RecipeIngredientRole.OUTPUT : RecipeIngredientRole.INPUT;
            IRecipeSlotBuilder slot = b.addSlot(role, s.x() + 1, s.y() + 1);
            if (s.role() == ViewSlot.Role.OUTPUT) slot.setOutputSlotBackground(); else slot.setStandardSlotBackground();
            if (s.kind() == ViewSlot.Kind.ITEM) {
                slot.addItemStacks(r.items(s.index()));
            } else {
                for (ViewFluid f : r.fluids(s.index())) slot.addFluidStack(f.fluid(), f.amount());
            }
        }
    }

    @Override
    public void draw(ViewRecipe r, IRecipeSlotsView slots, GuiGraphics g, double mx, double my) {
        for (ViewRecipe.Blocks b : r.blocks()) {
            GuiMultiblockRenderer.submit(g, MultiblockScene.of(b.pattern()), b.x(), b.y(), b.w(), b.h(), GuiMultiblockRenderer.YAW, GuiMultiblockRenderer.PITCH);
        }
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder b, ViewRecipe r, IFocusGroup focuses) {
        ViewCategory.Arrow a = view.arrow();
        if (a != null) {
            if (a.ticks() > 0) b.addAnimatedRecipeArrow(a.ticks()).setPosition(a.x(), a.y());
            else b.addRecipeArrow().setPosition(a.x(), a.y());
        }
        for (ViewRecipe.Text t : r.text()) {
            if (t.x() < 0) {
                b.addText(t.text(), view.width(), 10).setPosition(0, t.y()).setTextAlignment(HorizontalAlignment.CENTER).setColor(t.colour());
            } else {
                b.addText(t.text(), Math.max(1, view.width() - t.x()), 10).setPosition(t.x(), t.y()).setColor(t.colour());
            }
        }
    }
}
