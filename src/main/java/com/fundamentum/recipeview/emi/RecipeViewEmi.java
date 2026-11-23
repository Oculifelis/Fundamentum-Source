package com.fundamentum.recipeview.emi;

import com.fundamentum.recipeview.RecipeViews;
import com.fundamentum.recipeview.ViewCategory;
import com.fundamentum.recipeview.ViewRecipe;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiInfoRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.Comparison;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * EMI side of {@link RecipeViews}. EMI finds this class by its annotation; without EMI nothing loads it. EMI rebuilds its
 * recipes on each of its own reloads, so recipes that arrive later show after the next one.
 */
@EmiEntrypoint
public final class RecipeViewEmi implements EmiPlugin {
    @Override
    public void register(EmiRegistry r) {
        for (ViewCategory<?> c : RecipeViews.categories()) {
            EmiRecipeCategory cat = new EmiRecipeCategory(new ResourceLocation(c.namespace(), c.path()), EmiStack.of(c.icon())) {
                @Override
                public Component getName() { return c.title(); }
            };
            r.addCategory(cat);
            for (ItemStack s : c.catalysts()) r.addWorkstation(cat, EmiStack.of(s));
            for (ViewRecipe recipe : c.build()) r.addRecipe(new EmiViewRecipe(recipe, cat));
        }
        int page = 0;
        for (Map.Entry<List<ItemStack>, List<Component>> e : RecipeViews.builtInfo()) {
            List<EmiIngredient> stacks = new ArrayList<>();
            for (ItemStack s : e.getKey()) stacks.add(EmiStack.of(s));
            r.addRecipe(new EmiInfoRecipe(stacks, e.getValue(), new ResourceLocation("fundamentum", "/recipeview/info/" + page++)));
        }
        r.removeEmiStacks(s -> {
            ItemStack stack = s.getItemStack();
            return !stack.isEmpty() && RecipeViews.hidden(stack);
        });
        for (RecipeViews.Subtypes s : RecipeViews.subtypes()) {
            r.setDefaultComparison(s.item().get(), Comparison.compareData(stack -> {
                String k = s.key().apply(stack.getItemStack());
                return k == null ? "" : k;
            }));
        }
    }
}
