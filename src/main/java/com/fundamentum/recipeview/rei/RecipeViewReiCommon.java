package com.fundamentum.recipeview.rei;

import com.fundamentum.recipeview.RecipeViews;
import me.shedaniel.rei.api.common.entry.comparison.ItemComparatorRegistry;
import me.shedaniel.rei.api.common.plugins.REICommonPlugin;
import me.shedaniel.rei.forge.REIPluginCommon;

/** REI's common side of {@link RecipeViews}: items whose data makes them separate entries. Loaded by REI only. */
@REIPluginCommon
public final class RecipeViewReiCommon implements REICommonPlugin {
    @Override
    public void registerItemComparators(ItemComparatorRegistry r) {
        for (RecipeViews.Subtypes s : RecipeViews.subtypes()) {
            r.register((context, stack) -> {
                String k = s.key().apply(stack);
                return k == null ? 0L : k.hashCode();
            }, s.item().get());
        }
    }
}
