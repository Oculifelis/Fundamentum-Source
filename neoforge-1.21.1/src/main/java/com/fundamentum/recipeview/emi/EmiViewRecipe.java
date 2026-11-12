package com.fundamentum.recipeview.emi;

import com.fundamentum.recipeview.ViewCategory;
import com.fundamentum.recipeview.ViewFluid;
import com.fundamentum.recipeview.ViewRecipe;
import com.fundamentum.recipeview.ViewSlot;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.render.EmiTexture;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.SlotWidget;
import dev.emi.emi.api.widget.TextWidget;
import dev.emi.emi.api.widget.WidgetHolder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** One declared recipe as an EMI recipe. Slots sit with their 18x18 frame's corner at the declared x, y. */
final class EmiViewRecipe implements EmiRecipe {
    private final ViewRecipe recipe;
    private final EmiRecipeCategory category;
    private final ResourceLocation id;

    EmiViewRecipe(ViewRecipe recipe, EmiRecipeCategory category) {
        this.recipe = recipe;
        this.category = category;
        this.id = syntheticId(recipe.id());
    }

    /** EMI marks ids of recipes that are not real recipes with a leading "/" in the path. */
    static ResourceLocation syntheticId(String id) {
        int colon = id.indexOf(':');
        String path = id.substring(colon + 1).replaceAll("[^a-z0-9_./-]", "_");
        return ResourceLocation.fromNamespaceAndPath(id.substring(0, colon), "/" + path);
    }

    /** The slot's contents as one EMI ingredient (its alternatives). */
    private EmiIngredient entries(ViewSlot s) {
        List<EmiStack> list = new ArrayList<>();
        if (s.kind() == ViewSlot.Kind.ITEM) {
            for (ItemStack st : recipe.items(s.index())) list.add(EmiStack.of(st));
        } else {
            for (ViewFluid f : recipe.fluids(s.index())) list.add(EmiStack.of(f.fluid(), f.amount()));
        }
        return EmiIngredient.of(list);
    }

    @Override public EmiRecipeCategory getCategory() { return category; }
    @Override public ResourceLocation getId() { return id; }

    @Override
    public List<EmiIngredient> getInputs() {
        List<EmiIngredient> out = new ArrayList<>();
        for (ViewSlot s : recipe.category().slots()) if (s.role() == ViewSlot.Role.INPUT) out.add(entries(s));
        return out;
    }

    @Override
    public List<EmiIngredient> getCatalysts() {
        List<EmiIngredient> out = new ArrayList<>();
        for (ViewSlot s : recipe.category().slots()) if (s.role() == ViewSlot.Role.CATALYST) out.add(entries(s));
        return out;
    }

    @Override
    public List<EmiStack> getOutputs() {
        List<EmiStack> out = new ArrayList<>();
        for (ViewSlot s : recipe.category().slots()) if (s.role() == ViewSlot.Role.OUTPUT) out.addAll(entries(s).getEmiStacks());
        return out;
    }

    @Override public int getDisplayWidth() { return recipe.category().width(); }
    @Override public int getDisplayHeight() { return recipe.category().height(); }

    @Override
    public void addWidgets(WidgetHolder w) {
        ViewCategory<?> view = recipe.category();
        ViewCategory.Arrow a = view.arrow();
        if (a != null) {
            if (a.ticks() > 0) w.addFillingArrow(a.x(), a.y(), a.ticks() * 50);
            else w.addTexture(EmiTexture.EMPTY_ARROW, a.x(), a.y());
        }
        for (ViewSlot s : view.slots()) {
            SlotWidget slot = w.addSlot(entries(s), s.x(), s.y());
            if (s.role() == ViewSlot.Role.OUTPUT) slot.recipeContext(this);
            else if (s.role() == ViewSlot.Role.CATALYST) slot.catalyst(true);
        }
        for (ViewRecipe.Text t : recipe.text()) {
            if (t.x() < 0) w.addText(t.text(), view.width() / 2, t.y(), t.colour(), false).horizontalAlign(TextWidget.Alignment.CENTER);
            else w.addText(t.text(), t.x(), t.y(), t.colour(), false);
        }
    }
}
