package com.fundamentum.recipeview.rei;

import com.fundamentum.recipeview.ViewFluid;
import com.fundamentum.recipeview.ViewRecipe;
import com.fundamentum.recipeview.ViewSlot;
import java.util.ArrayList;
import java.util.List;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.util.EntryIngredients;
import me.shedaniel.rei.api.common.util.EntryStacks;

/** One declared recipe as a REI display. Made on the client only, so it is never synced and needs no serializer. */
final class ReiViewDisplay implements Display {
    final ViewRecipe recipe;
    private final CategoryIdentifier<ReiViewDisplay> id;

    ReiViewDisplay(ViewRecipe recipe, CategoryIdentifier<ReiViewDisplay> id) {
        this.recipe = recipe;
        this.id = id;
    }

    /** The slot's contents as one REI ingredient (its alternatives). */
    EntryIngredient entries(ViewSlot s) {
        if (s.kind() == ViewSlot.Kind.ITEM) return EntryIngredients.ofItemStacks(recipe.items(s.index()));
        EntryIngredient.Builder b = EntryIngredient.builder();
        for (ViewFluid f : recipe.fluids(s.index())) b.add(EntryStacks.of(f.fluid(), f.amount()));
        return b.build();
    }

    private List<EntryIngredient> side(boolean output) {
        List<EntryIngredient> out = new ArrayList<>();
        for (ViewSlot s : recipe.category().slots()) {
            if ((s.role() == ViewSlot.Role.OUTPUT) == output) out.add(entries(s));
        }
        return out;
    }

    @Override public List<EntryIngredient> getInputEntries() { return side(false); }
    @Override public List<EntryIngredient> getOutputEntries() { return side(true); }
    @Override public CategoryIdentifier<?> getCategoryIdentifier() { return id; }
}
