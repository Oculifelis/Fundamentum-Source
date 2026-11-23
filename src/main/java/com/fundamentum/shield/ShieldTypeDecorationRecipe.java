package com.fundamentum.shield;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * A banner on a Fundamentum shield ({@code fundamentum:shield_decoration}, one recipe for every {@link ShieldTypeItem}):
 * vanilla's shield decoration recipe on this version only takes {@code minecraft:shield}. Same rules as vanilla's: one
 * plain shield and one banner anywhere in the grid give the shield with the banner's patterns and colour.
 */
public class ShieldTypeDecorationRecipe extends CustomRecipe {
    public ShieldTypeDecorationRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer input, Level level) {
        return !find(input, false).isEmpty() && !find(input, true).isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingContainer input, RegistryAccess registries) {
        ItemStack banner = find(input, true);
        ItemStack shield = find(input, false);
        if (banner.isEmpty() || shield.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack result = shield.copyWithCount(1);
        CompoundTag data = BlockItem.getBlockEntityData(banner);
        CompoundTag tag = data == null ? new CompoundTag() : data.copy();
        tag.putInt("Base", ((BannerItem) banner.getItem()).getColor().getId());
        BlockItem.setBlockEntityData(result, BlockEntityType.BANNER, tag);
        return result;
    }

    /** The one banner (or one plain Fundamentum shield) in the grid; empty when there is none, more than one or anything else. */
    private static ItemStack find(CraftingContainer input, boolean banner) {
        ItemStack found = ItemStack.EMPTY;
        int banners = 0;
        int shields = 0;
        for (int i = 0; i < input.getContainerSize(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() instanceof BannerItem) {
                banners++;
                if (banner) {
                    found = stack;
                }
            } else if (stack.getItem() instanceof ShieldTypeItem && BlockItem.getBlockEntityData(stack) == null) {
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
