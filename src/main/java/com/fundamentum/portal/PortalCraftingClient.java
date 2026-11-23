package com.fundamentum.portal;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The client's copy of the portal recipes, which the server sends on join and after /reload ({@link PortalCrafting}).
 * JEI reads it; it may arrive before or after JEI has built its list, so readers can {@link #listen} for new copies.
 */
public final class PortalCraftingClient {
    private PortalCraftingClient() { }

    private static List<RecipeHolder<PortalRecipe>> recipes = List.of();
    private static int generation;
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    public static void init() {
        NeoForge.EVENT_BUS.addListener((RecipesReceivedEvent e) -> {
            if (!e.getRecipeTypes().contains(PortalCrafting.TYPE.get())) return;
            recipes = List.copyOf(e.getRecipeMap().byType(PortalCrafting.TYPE.get()));
            generation++;
            LISTENERS.forEach(Runnable::run);
        });
    }

    public static List<RecipeHolder<PortalRecipe>> recipes() { return recipes; }

    /** Goes up by one with every copy received. */
    public static int generation() { return generation; }

    public static void listen(Runnable onNewCopy) { LISTENERS.add(onNewCopy); }
}
