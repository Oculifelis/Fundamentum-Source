package com.fundamentum.portal;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.RecipeBookCategories;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.event.RegisterRecipeBookCategoriesEvent;

/**
 * The portal recipes' own recipe book category ({@code FUNDAMENTUM_PORTAL_CRAFTING}, an extension of vanilla's
 * RecipeBookCategories; 26.x registers {@code fundamentum:portal_crafting} in its recipe book category registry). Like
 * 26.x's it belongs to no book tab, so the recipes are known to the recipe book but shown in none; it only gives them a
 * category, so the client files them without an "unknown recipe category" warning.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class PortalRecipeBook {
    /** The category's constructor argument, its icons (enum extension parameter; the loader wants an EnumProxy here). */
    public static final EnumProxy<RecipeBookCategories> ICONS = new EnumProxy<>(RecipeBookCategories.class,
        (Supplier<List<ItemStack>>) () -> List.of(new ItemStack(Items.OBSIDIAN)));

    private PortalRecipeBook() {}

    public static RecipeBookCategories category() {
        return ICONS.getValue();
    }

    @SubscribeEvent
    public static void categories(RegisterRecipeBookCategoriesEvent event) {
        RecipeBookCategories book = category();
        event.registerRecipeCategoryFinder(PortalCrafting.TYPE.get(), holder -> book);
    }
}
