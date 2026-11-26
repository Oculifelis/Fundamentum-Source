package com.fundamentum.compat.jade;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.Identifiers;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElementHelper;

/**
 * Client half of the disc shelf tooltip: one line per disc with its icon, its slot and its song, from the items
 * {@link DiscShelfJade} sent. Jade's own item-storage row is dropped for these blocks, since the list says the same.
 * Registered only on the client.
 */
public final class DiscShelfJadeClient implements IBlockComponentProvider {
    public static final DiscShelfJadeClient INSTANCE = new DiscShelfJadeClient();
    private static final String[] SIX = {"top_left", "top_mid", "top_right", "bottom_left", "bottom_mid", "bottom_right"};

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        if (!DiscShelfJade.applies(accessor) || !data.contains(DiscShelfJade.KEY)) {
            return;
        }
        tooltip.remove(Identifiers.UNIVERSAL_ITEM_STORAGE);
        int size = data.getInt(DiscShelfJade.KEY + "_size");
        for (Tag t : data.getList(DiscShelfJade.KEY, Tag.TAG_COMPOUND)) {
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
    public ResourceLocation getUid() { return DiscShelfJade.UID; }
}
