package com.fundamentum.compat.jei;

import com.fundamentum.Fundamentum;
import com.fundamentum.portal.PortalCraftingClient;
import com.fundamentum.portal.PortalRecipe;
import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Optional JEI integration: the portal crafting category. JEI finds this class by its annotation; nothing else refers to
 * it, so Fundamentum runs the same without JEI. The recipes reach the client after join (and after /reload), possibly
 * after JEI built its list, so a newer copy replaces the shown one at runtime.
 */
@JeiPlugin
public final class FundamentumJeiPlugin implements IModPlugin {
    private static IJeiRuntime runtime;
    private static boolean listening;
    private static int shownGeneration;
    private static List<RecipeHolder<PortalRecipe>> shown = List.of();

    @Override
    public ResourceLocation getPluginUid() { return ResourceLocation.fromNamespaceAndPath(Fundamentum.MODID, "jei"); }

    @Override
    public void registerCategories(IRecipeCategoryRegistration r) {
        r.addRecipeCategories(new PortalCategory(r.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration r) {
        shownGeneration = PortalCraftingClient.generation();
        shown = PortalCraftingClient.recipes();
        r.addRecipes(PortalCategory.TYPE, shown);
        RelicsJei.info(r);
        if (!listening) {
            listening = true;
            PortalCraftingClient.listen(() -> Minecraft.getInstance().execute(FundamentumJeiPlugin::refresh));
        }
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration r) {
        r.addRecipeCatalysts(PortalCategory.TYPE, Items.OBSIDIAN, Items.FLINT_AND_STEEL, Items.ENDER_EYE);
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime rt) {
        runtime = rt;
        refresh();
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
        shownGeneration = 0;
        shown = List.of();
    }

    private static void refresh() {
        if (runtime == null || PortalCraftingClient.generation() == shownGeneration) return;
        if (!shown.isEmpty()) runtime.getRecipeManager().hideRecipes(PortalCategory.TYPE, shown);
        shownGeneration = PortalCraftingClient.generation();
        shown = PortalCraftingClient.recipes();
        if (!shown.isEmpty()) runtime.getRecipeManager().addRecipes(PortalCategory.TYPE, shown);
    }
}
