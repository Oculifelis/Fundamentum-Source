package com.fundamentum.compat.jade;

import com.fundamentum.Fundamentum;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * Server half of the disc shelf tooltip: sends the items of a shelf in the {@code fundamentum:disc_shelves} tag
 * (any block whose block entity is a container), since the client never knows them. It touches no client class, so a
 * dedicated server can load it; {@link DiscShelfJadeClient} draws the lines.
 */
public final class DiscShelfJade implements IServerDataProvider<BlockAccessor> {
    public static final DiscShelfJade INSTANCE = new DiscShelfJade();
    public static final ResourceLocation UID = new ResourceLocation(Fundamentum.MODID, "disc_shelf");
    public static final TagKey<Block> DISC_SHELVES = TagKey.create(Registries.BLOCK, new ResourceLocation(Fundamentum.MODID, "disc_shelves"));
    static final String KEY = "fundamentum_discs";

    static boolean applies(BlockAccessor accessor) {
        return accessor.getBlockState().is(DISC_SHELVES) && accessor.getBlockEntity() instanceof Container;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!applies(accessor)) {
            return;
        }
        Container c = (Container) accessor.getBlockEntity();
        ListTag list = new ListTag();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack stack = c.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag t = stack.save(new CompoundTag());
                t.putInt("Slot", i);
                list.add(t);
            }
        }
        data.put(KEY, list);
        data.putInt(KEY + "_size", c.getContainerSize());
    }

    @Override
    public ResourceLocation getUid() { return UID; }
}
