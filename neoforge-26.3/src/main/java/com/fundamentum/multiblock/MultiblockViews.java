package com.fundamentum.multiblock;

import com.fundamentum.port.FId;
import com.fundamentum.recipeview.RecipeViews;
import com.fundamentum.recipeview.ViewCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * The recipe viewer page {@code fundamentum:multiblocks} (JEI, EMI, REI): one entry per pattern, the structure drawn as
 * real blocks on top (JEI, REI and the guide), then the blocks it takes and how many of each (up to twelve kinds), the controller on the right, and its size. A pattern's {@code name} is a
 * translation key; without one the page reads {@code multiblock.<ns>.<path>}, falling back to the file name.
 */
public final class MultiblockViews {
    private MultiblockViews() { }

    public static final String CATEGORY = "fundamentum:multiblocks";
    private static final int PART_SLOTS = 12;
    /** Height of the block view at the top of each entry. */
    private static final int VIEW_H = 60;

    /** One page entry. */
    public record Entry(FId id, MultiblockPattern pattern) { }

    static void init() {
        ViewCategory<Entry> c = RecipeViews.category(CATEGORY);
        c.title(Component.translatable("jei.fundamentum.multiblocks"))
            .icon(() -> new ItemStack(Items.BRICKS))
            .size(150, VIEW_H + 66)
            .arrow(110, VIEW_H + 14, 0);
        int[] parts = new int[PART_SLOTS];
        for (int i = 0; i < PART_SLOTS; i++) parts[i] = c.input(1 + 18 * (i % 6), VIEW_H + 5 + 18 * (i / 6));
        int controller = c.output(132, VIEW_H + 14);
        c.recipes(MultiblockViews::entries)
            .key(e -> e.id().toString().replace(':', '/'))
            .fill((e, r) -> {
                MultiblockPattern p = e.pattern();
                r.blocks(e.id(), 0, 0, 150, VIEW_H);
                MultiblockPattern.Part ctrl = p.controllerPart();
                int slot = 0;
                for (Map.Entry<Character, Integer> count : p.counts().entrySet()) {
                    MultiblockPattern.Part part = p.parts().get(count.getKey());
                    if (part == null || part == ctrl) continue;
                    if (slot >= PART_SLOTS) break;
                    List<ItemStack> stacks = stacks(part, Math.min(count.getValue(), 99));
                    if (!stacks.isEmpty()) r.items(parts[slot++], stacks);
                }
                r.items(controller, stacks(ctrl, 1));
                r.text(Component.literal(p.width() + " x " + p.height() + " x " + p.depth()), VIEW_H + 44);
                r.text(title(e.id(), p), VIEW_H + 55);
            });
        Multiblocks.TABLE.onLoad(t -> RecipeViews.refresh());
    }

    /** Every pattern as a page entry, in id order. */
    public static List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<FId, MultiblockPattern> e : Multiblocks.patterns().entrySet()) out.add(new Entry(e.getKey(), e.getValue()));
        return out;
    }

    public static Component title(FId id, MultiblockPattern p) {
        if (p.name() != null) return Component.translatable(p.name());
        String path = id.path();
        String plain = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ');
        if (!plain.isEmpty()) plain = Character.toUpperCase(plain.charAt(0)) + plain.substring(1);
        return Component.translatableWithFallback("multiblock." + id.namespace() + "." + path.replace('/', '.'), plain);
    }

    /** The items a part accepts, each stack holding {@code count}. */
    static List<ItemStack> stacks(MultiblockPattern.Part part, int count) {
        List<ItemStack> out = new ArrayList<>();
        if (part == null || part.any() || part.air()) return out;
        for (MultiblockPattern.Option o : part.options()) {
            if (o.block() != null) {
                add(out, o.block(), count);
            } else {
                for (Block b : BuiltInRegistries.BLOCK) {
                    if (b.defaultBlockState().is(o.tag())) add(out, b, count);
                }
            }
        }
        return out;
    }

    private static void add(List<ItemStack> out, Block block, int count) {
        Item item = block.asItem();
        if (item != Items.AIR) out.add(new ItemStack(item, count));
    }
}
