package com.fundamentum.compat.jade;

import com.fundamentum.Fundamentum;
import com.fundamentum.music.JukeboxProgress;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.JadeIds;
import snownee.jade.api.StreamServerDataProvider;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

/**
 * Shelves that hold music discs (any block in the {@code fundamentum:disc_shelves} tag whose block entity is a
 * container): one line per disc with its icon, its slot and its song. The client never knows a shelf's items, so the
 * server sends them. Jade's own item-storage row is dropped for these blocks, since the list says the same.
 */
public final class DiscShelfJade implements StreamServerDataProvider<BlockAccessor, List<ItemStack>>, IBlockComponentProvider {
    public static final DiscShelfJade INSTANCE = new DiscShelfJade();
    public static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(Fundamentum.MODID, "disc_shelf");
    public static final TagKey<Block> DISC_SHELVES =
        TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(Fundamentum.MODID, "disc_shelves"));
    private static final String[] SIX = {"top_left", "top_mid", "top_right", "bottom_left", "bottom_mid", "bottom_right"};

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
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!applies(accessor)) {
            return;
        }
        decodeFromData(accessor).ifPresent(items -> {
            if (items == null) {
                return;
            }
            tooltip.remove(JadeIds.UNIVERSAL_ITEM_STORAGE);
            HolderLookup.Provider registries = accessor.getLevel().registryAccess();
            for (int i = 0; i < items.size(); i++) {
                ItemStack stack = items.get(i);
                if (stack.isEmpty()) {
                    continue;
                }
                tooltip.add(IElementHelper.get().smallItem(stack));
                tooltip.append(Component.literal(" ").append(slotName(i, items.size())).append(": ")
                    .withStyle(ChatFormatting.GRAY).append(songName(registries, stack).copy().withStyle(ChatFormatting.WHITE)));
            }
        });
    }

    static Component slotName(int i, int size) {
        return size == SIX.length ? Component.translatable("fundamentum.jade.slot." + SIX[i])
            : Component.translatable("fundamentum.jade.slot", i + 1);
    }

    static Component songName(HolderLookup.Provider registries, ItemStack stack) {
        return JukeboxProgress.song(registries, stack).map(h -> h.value().description()).orElse(stack.getHoverName());
    }

    /** After Jade's item-storage row (priority 1000), so that row can be removed. */
    @Override
    public int getDefaultPriority() {
        return 1100;
    }

    @Override
    public ResourceLocation getUid() { return UID; }
}
