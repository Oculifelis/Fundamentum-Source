package com.fundamentum.shield;

import net.minecraft.core.component.DataComponents;
import net.minecraft.locale.Language;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

/**
 * A Fundamentum shield item: vanilla's shield (raise, banners, off hand, cooldown icon) whose stats come from
 * {@link Shields}. Build its properties with {@link #properties} and add a durability.
 */
public class ShieldTypeItem extends ShieldItem {
    public ShieldTypeItem(Item.Properties properties) {
        super(properties);
    }

    /** The vanilla shield's components (one per stack, banner layers); add a durability. */
    public static Item.Properties properties(Item.Properties p) {
        return p.stacksTo(1).component(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
    }

    /** A banner shield is "Red <name>" in vanilla; ours fall back to the plain name when no colour key is given. */
    @Override
    public String getDescriptionId(ItemStack stack) {
        DyeColor color = stack.get(DataComponents.BASE_COLOR);
        if (color != null) {
            String key = getDescriptionId() + "." + color.getName();
            if (Language.getInstance().has(key)) {
                return key;
            }
        }
        return getDescriptionId();
    }
}
