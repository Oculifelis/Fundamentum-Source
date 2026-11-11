package com.fundamentum.portal;

import net.minecraft.client.RecipeBookCategories;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterRecipeBookCategoriesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The portal recipes' own recipe book category ({@code FUNDAMENTUM_PORTAL_CRAFTING}, made with Forge's
 * {@code RecipeBookCategories.create}; 26.x registers {@code fundamentum:portal_crafting} in its recipe book category
 * registry). Like 26.x's it belongs to no book tab, so the recipes are known to the recipe book but shown in none; it
 * only gives them a category, so the client files them without an "unknown recipe category" warning.
 */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PortalRecipeBook {
    public static final RecipeBookCategories CATEGORY = RecipeBookCategories.create("FUNDAMENTUM_PORTAL_CRAFTING", new ItemStack(Items.OBSIDIAN));

    private PortalRecipeBook() {}

    @SubscribeEvent
    public static void categories(RegisterRecipeBookCategoriesEvent event) {
        event.registerRecipeCategoryFinder(PortalCrafting.TYPE.get(), recipe -> CATEGORY);
    }
}
