package com.fundamentum.food;

import com.fundamentum.port.FId;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.crafting.Recipe;

/** Adds a shapeless recipe made in code (no JSON file) to the live recipe manager, for the food test. */
final class CodeRecipe {
    private CodeRecipe() { }

    /** Adds the recipe; the returned action puts the recipes back as they were. */
    static Runnable add(MinecraftServer server, FId id, Item result, Item... ingredients) throws ReflectiveOperationException {
        RecipeManager manager = server.getRecipeManager();
        List<Recipe<?>> before = new ArrayList<>(manager.getRecipes());
        NonNullList<Ingredient> parts = NonNullList.create();
        for (Item i : ingredients) parts.add(Ingredient.of(i));
        ShapelessRecipe recipe = new ShapelessRecipe(id.vanilla(), "", CraftingBookCategory.MISC, new ItemStack(result), parts);
        List<Recipe<?>> all = new ArrayList<>(before);
        all.add(recipe);
        manager.replaceRecipes(all);
        return () -> manager.replaceRecipes(before);
    }
}
