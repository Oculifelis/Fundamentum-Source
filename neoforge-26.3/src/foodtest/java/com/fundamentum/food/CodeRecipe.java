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
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;

/** Adds a shapeless recipe made in code (no JSON file) to the live recipe manager, for the food test. */
final class CodeRecipe {
    private CodeRecipe() { }

    /** Adds the recipe; the returned action puts the recipes back as they were. */
    static Runnable add(MinecraftServer server, FId id, Item result, Item... ingredients) throws ReflectiveOperationException {
        RecipeManager manager = server.getRecipeManager();
        Field field = null;
        for (Field f : RecipeManager.class.getDeclaredFields()) {
            if (f.getType() == RecipeMap.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) field = f;
        }
        if (field == null) throw new NoSuchFieldException("RecipeManager has no recipe map");
        field.setAccessible(true);
        RecipeMap before = (RecipeMap) field.get(manager);
        List<Ingredient> parts = new ArrayList<>();
        for (Item i : ingredients) parts.add(Ingredient.of(i));
        ShapelessRecipe recipe = new ShapelessRecipe(new Recipe.CommonInfo(false),
            new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""), new ItemStackTemplate(result), parts);
        List<RecipeHolder<?>> all = new ArrayList<>(manager.getRecipes());
        all.add(new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, id.vanilla()), recipe));
        field.set(manager, RecipeMap.createClient(all));
        Field f = field;
        return () -> {
            try {
                f.set(manager, before);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        };
    }
}
