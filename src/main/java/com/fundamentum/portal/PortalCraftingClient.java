package com.fundamentum.portal;

import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * The client's copy of the portal recipes, which the server sends on join and after /reload ({@link PortalCrafting}).
 * JEI reads it; it may arrive before or after JEI has built its list, so readers can {@link #listen} for new copies.
 */
public final class PortalCraftingClient {
    private PortalCraftingClient() { }

    private static List<PortalRecipe> recipes = List.of();
    private static int generation;
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener((RecipesUpdatedEvent e) -> {
            recipes = List.copyOf(e.getRecipeManager().getAllRecipesFor(PortalCrafting.TYPE.get()));
            generation++;
            LISTENERS.forEach(Runnable::run);
        });
    }

    public static List<PortalRecipe> recipes() { return recipes; }

    /** Goes up by one with every copy received. */
    public static int generation() { return generation; }

    public static void listen(Runnable onNewCopy) { LISTENERS.add(onNewCopy); }
}
