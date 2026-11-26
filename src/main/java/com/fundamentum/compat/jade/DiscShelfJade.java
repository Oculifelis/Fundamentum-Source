package com.fundamentum.compat.jade;

import com.fundamentum.Fundamentum;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.StreamServerDataProvider;

/**
 * Server half of the disc shelf tooltip: sends the items of a shelf in the {@code fundamentum:disc_shelves} tag
 * (any block whose block entity is a container), since the client never knows them. It touches no client class, so a
 * dedicated server can load it; {@link DiscShelfJadeClient} draws the lines.
 */
public final class DiscShelfJade implements StreamServerDataProvider<BlockAccessor, List<ItemStack>> {
    public static final DiscShelfJade INSTANCE = new DiscShelfJade();
    public static final Identifier UID = Identifier.fromNamespaceAndPath(Fundamentum.MODID, "disc_shelf");
    public static final TagKey<Block> DISC_SHELVES =
        TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Fundamentum.MODID, "disc_shelves"));

    static boolean applies(BlockAccessor accessor) {
        return accessor.getBlockState().is(DISC_SHELVES) && accessor.getBlockEntity() instanceof Container;
    }

    @Override
    public boolean shouldRequestData(BlockAccessor accessor) {
        return applies(accessor);
    }

    @Override
    public List<ItemStack> streamData(BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof Container c)) {
            return null;
        }
        List<ItemStack> items = new ArrayList<>(c.getContainerSize());
        for (int i = 0; i < c.getContainerSize(); i++) {
            items.add(c.getItem(i).copy());
        }
        return items;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, List<ItemStack>> streamCodec() {
        return ItemStack.OPTIONAL_LIST_STREAM_CODEC;
    }

    @Override
    public Identifier getUid() { return UID; }
}
