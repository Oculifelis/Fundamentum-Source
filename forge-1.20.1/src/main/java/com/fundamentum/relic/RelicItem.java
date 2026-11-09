package com.fundamentum.relic;

import java.util.function.Consumer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

/** A relic's item: drawn as a small copy of the relic ({@code client.RelicItemRenderer}). */
public class RelicItem extends BlockItem {
    public RelicItem(Block block, Item.Properties props) {
        super(block, props);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(com.fundamentum.relic.client.RelicItemRenderer.EXTENSIONS);
    }
}
