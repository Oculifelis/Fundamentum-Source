package com.fundamentum.shield;

import java.util.function.Consumer;
import net.minecraft.locale.Language;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

/**
 * A Fundamentum shield item: vanilla's shield (raise, banners, off hand, cooldown icon) whose stats come from
 * {@link Shields}. Build its properties with {@link #properties} and add a durability.
 */
public class ShieldTypeItem extends ShieldItem {
    public ShieldTypeItem(Item.Properties properties) {
        super(properties);
    }

    /** The vanilla shield's properties (one per stack); add a durability. */
    public static Item.Properties properties(Item.Properties p) {
        return p.stacksTo(1);
    }

    /** A banner shield is "Red <name>" in vanilla; ours fall back to the plain name when no colour key is given. */
    @Override
    public String getDescriptionId(ItemStack stack) {
        if (BlockItem.getBlockEntityData(stack) != null) {
            String key = getDescriptionId() + "." + getColor(stack).getName();
            if (Language.getInstance().has(key)) {
                return key;
            }
        }
        return getDescriptionId();
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(ShieldsClient.EXTENSIONS);
    }
}
