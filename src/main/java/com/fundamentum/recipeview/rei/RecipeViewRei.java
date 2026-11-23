package com.fundamentum.recipeview.rei;

import com.fundamentum.recipeview.RecipeViews;
import com.fundamentum.recipeview.ViewCategory;
import com.fundamentum.recipeview.ViewRecipe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import me.shedaniel.rei.api.common.util.EntryIngredients;
import me.shedaniel.rei.api.common.util.EntryStacks;
import me.shedaniel.rei.forge.REIPluginClient;
import me.shedaniel.rei.plugin.common.displays.DefaultInformationDisplay;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * REI side of {@link RecipeViews}. REI finds this class by its annotation; without REI nothing loads it. REI rebuilds its
 * displays on each of its own reloads, so recipes that arrive later show after the next one.
 */
@REIPluginClient
public final class RecipeViewRei implements REIClientPlugin {
    private final List<ReiViewCategory> categories = new ArrayList<>();

    @Override
    public void registerCategories(CategoryRegistry r) {
        categories.clear();
        for (ViewCategory<?> c : RecipeViews.categories()) {
            ReiViewCategory cat = new ReiViewCategory(c);
            categories.add(cat);
            r.add(cat);
            for (ItemStack s : c.catalysts()) r.addWorkstations(cat.id, EntryStacks.of(s));
        }
    }

    @Override
    public void registerDisplays(DisplayRegistry r) {
        for (ReiViewCategory cat : categories) {
            for (ViewRecipe recipe : cat.view.build()) r.add(new ReiViewDisplay(recipe, cat.id));
        }
        for (Map.Entry<List<ItemStack>, List<Component>> e : RecipeViews.builtInfo()) {
            r.add(DefaultInformationDisplay.createFromEntries(EntryIngredients.ofItemStacks(e.getKey()), e.getKey().get(0).getHoverName())
                    .lines(e.getValue()));
        }
    }

    @Override
    public void registerEntries(EntryRegistry r) {
        r.removeEntryIf(e -> e.getType() == VanillaEntryTypes.ITEM && RecipeViews.hidden(e.<ItemStack>castValue()));
    }
}
