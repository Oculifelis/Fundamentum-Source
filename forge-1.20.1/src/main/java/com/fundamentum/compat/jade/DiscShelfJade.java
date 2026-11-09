package com.fundamentum.compat.jade;

import com.fundamentum.Fundamentum;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.level.block.Block;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.Identifiers;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

/**
 * Shelves that hold music discs (any block in the {@code fundamentum:disc_shelves} tag whose block entity is a
 * container): one line per disc with its icon, its slot and its song. The client never knows a shelf's items, so the
 * server sends them (Jade 11: a tag). Jade's own item-storage row is dropped for these blocks, since the list says the same.
 */
public final class DiscShelfJade implements IServerDataProvider<BlockAccessor>, IBlockComponentProvider {
    public static final DiscShelfJade INSTANCE = new DiscShelfJade();
    public static final ResourceLocation UID = new ResourceLocation(Fundamentum.MODID, "disc_shelf");
    public static final TagKey<Block> DISC_SHELVES = TagKey.create(Registries.BLOCK, new ResourceLocation(Fundamentum.MODID, "disc_shelves"));
    private static final String KEY = "fundamentum_discs";
    private static final String[] SIX = {"top_left", "top_mid", "top_right", "bottom_left", "bottom_mid", "bottom_right"};

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
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (!applies(accessor) || !data.contains(KEY)) {
            return;
        }
        tooltip.remove(Identifiers.UNIVERSAL_ITEM_STORAGE);
        int size = data.getInt(KEY + "_size");
        for (Tag t : data.getList(KEY, Tag.TAG_COMPOUND)) {
            CompoundTag ct = (CompoundTag) t;
            ItemStack stack = ItemStack.of(ct);
            if (stack.isEmpty()) {
                continue;
            }
            tooltip.add(IElementHelper.get().smallItem(stack));
            tooltip.append(Component.literal(" ").append(slotName(ct.getInt("Slot"), size)).append(": ")
                .withStyle(ChatFormatting.GRAY).append(songName(stack).copy().withStyle(ChatFormatting.WHITE)));
        }
    }

    static Component slotName(int i, int size) {
        return size == SIX.length && i >= 0 && i < SIX.length ? Component.translatable("fundamentum.jade.slot." + SIX[i])
            : Component.translatable("fundamentum.jade.slot", i + 1);
    }

    static Component songName(ItemStack stack) {
        return stack.getItem() instanceof RecordItem r ? r.getDisplayName() : stack.getHoverName();
    }

    /** After Jade's item-storage row (priority 1000), so that row can be removed. */
    @Override
    public int getDefaultPriority() {
        return 1100;
    }

    @Override
    public ResourceLocation getUid() { return UID; }
}
