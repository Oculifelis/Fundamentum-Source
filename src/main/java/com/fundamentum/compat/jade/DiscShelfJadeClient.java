package com.fundamentum.compat.jade;
import com.fundamentum.music.JukeboxProgress;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.JadeIds;
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
        if (!DiscShelfJade.applies(accessor)) {
            return;
        }
        DiscShelfJade.INSTANCE.decodeFromData(accessor).ifPresent(items -> {
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
    public ResourceLocation getUid() { return DiscShelfJade.UID; }
}
