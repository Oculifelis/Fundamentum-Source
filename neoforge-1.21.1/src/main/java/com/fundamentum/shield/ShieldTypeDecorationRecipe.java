package com.fundamentum.shield;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * A banner on a Fundamentum shield ({@code fundamentum:shield_decoration}, one recipe for every {@link ShieldTypeItem}):
 * vanilla's shield decoration recipe on this version only takes {@code minecraft:shield}. Same rules as vanilla's: one
 * plain shield and one banner anywhere in the grid give the shield with the banner's patterns and colour.
 */
public class ShieldTypeDecorationRecipe extends CustomRecipe {
    public ShieldTypeDecorationRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return !find(input, false).isEmpty() && !find(input, true).isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack banner = find(input, true);
        ItemStack shield = find(input, false);
        if (banner.isEmpty() || shield.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack result = shield.copyWithCount(1);
        result.set(DataComponents.BANNER_PATTERNS, banner.get(DataComponents.BANNER_PATTERNS));
        result.set(DataComponents.BASE_COLOR, ((BannerItem) banner.getItem()).getColor());
        return result;
    }

    /** The one banner (or one plain Fundamentum shield) in the grid; empty when there is none, more than one or anything else. */
    private static ItemStack find(CraftingInput input, boolean banner) {
        ItemStack found = ItemStack.EMPTY;
        int banners = 0;
        int shields = 0;
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() instanceof BannerItem) {
                banners++;
                if (banner) {
                    found = stack;
                }
            } else if (stack.getItem() instanceof ShieldTypeItem
                && stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY).layers().isEmpty()) {
                shields++;
                if (!banner) {
                    found = stack;
                }
            } else {
                return ItemStack.EMPTY;
            }
        }
        return banners == 1 && shields == 1 ? found : ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return Shields.DECORATION.get();
    }
}
