package com.fundamentum.recipeview.jei;

import com.fundamentum.recipeview.RecipeViews;
import com.fundamentum.recipeview.ViewCategory;
import com.fundamentum.recipeview.ViewRecipe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * JEI side of {@link RecipeViews}. JEI finds this class by its annotation; nothing else refers to it, so it is never loaded
 * without JEI. Recipes that reach the client after JEI loaded come in through {@link RecipeViews#refresh}.
 */
@JeiPlugin
public final class RecipeViewJei implements IModPlugin {
    private static final Map<String, JeiViewCategory> CATEGORIES = new HashMap<>();
    private static final Map<String, List<ViewRecipe>> SHOWN = new HashMap<>();
    private static IJeiRuntime runtime;
    private static boolean listening;

    @Override
    public ResourceLocation getPluginUid() { return ResourceLocation.fromNamespaceAndPath("fundamentum", "recipeview"); }

    @Override
    public void registerItemSubtypes(ISubtypeRegistration r) {
        for (RecipeViews.Subtypes s : RecipeViews.subtypes()) {
            r.registerSubtypeInterpreter(s.item().get(), new ISubtypeInterpreter<ItemStack>() {
                @Override
                public Object getSubtypeData(ItemStack stack, UidContext context) {
                    String k = s.key().apply(stack);
                    return k == null || k.isEmpty() ? null : k;
                }

                @Override
                public String getLegacyStringSubtypeInfo(ItemStack stack, UidContext context) {
                    String k = s.key().apply(stack);
                    return k == null ? "" : k;
                }
            });
        }
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration r) {
        CATEGORIES.clear();
        for (ViewCategory<?> c : RecipeViews.categories()) {
            JeiViewCategory j = new JeiViewCategory(c, r.getJeiHelpers().getGuiHelper());
            CATEGORIES.put(c.id(), j);
            r.addRecipeCategories(j);
        }
    }

    @Override
    public void registerRecipes(IRecipeRegistration r) {
        SHOWN.clear();
        for (JeiViewCategory j : CATEGORIES.values()) {
            List<ViewRecipe> list = j.view.build();
            SHOWN.put(j.view.id(), list);
            r.addRecipes(j.type, list);
        }
        for (Map.Entry<List<ItemStack>, List<Component>> e : RecipeViews.builtInfo()) {
            r.addItemStackInfo(e.getKey(), e.getValue().toArray(Component[]::new));
        }
        if (!listening) {
            listening = true;
            RecipeViews.onRefresh(() -> Minecraft.getInstance().execute(RecipeViewJei::reload));
        }
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration r) {
        for (JeiViewCategory j : CATEGORIES.values()) {
            for (ItemStack s : j.view.catalysts()) r.addRecipeCatalyst(s, j.type);
        }
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime rt) {
        runtime = rt;
        List<ItemStack> hide = new ArrayList<>();
        for (ItemStack s : rt.getIngredientManager().getAllIngredients(VanillaTypes.ITEM_STACK)) {
            if (RecipeViews.hidden(s)) hide.add(s);
        }
        if (!hide.isEmpty()) rt.getIngredientManager().removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, hide);
        reload();
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    /** Swaps every category's shown recipes for a fresh build. */
    private static void reload() {
        if (runtime == null) return;
        for (JeiViewCategory j : CATEGORIES.values()) {
            List<ViewRecipe> old = SHOWN.getOrDefault(j.view.id(), List.of());
            List<ViewRecipe> fresh = j.view.build();
            if (!old.isEmpty()) runtime.getRecipeManager().hideRecipes(j.type, old);
            if (!fresh.isEmpty()) runtime.getRecipeManager().addRecipes(j.type, fresh);
            SHOWN.put(j.view.id(), fresh);
        }
    }
}
