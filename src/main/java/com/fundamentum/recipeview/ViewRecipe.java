package com.fundamentum.recipeview;

import com.fundamentum.port.FId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * One recipe as a viewer shows it: what fills each slot, plus lines of text. Made by a category's filler from the mod's
 * own recipe object, so the viewers never see the mod's classes. A slot can hold several alternatives (they cycle).
 */
public final class ViewRecipe {
    /** A line of text at {@code x, y}; {@code x < 0} centres it across the category. */
    public record Text(Component text, int x, int y, int colour) {}

    /** A multiblock pattern drawn as real block models in the box {@code x, y, w, h}. */
    public record Blocks(FId pattern, int x, int y, int w, int h) {}

    private final ViewCategory<?> category;
    private final String id;
    private final Map<Integer, List<ItemStack>> items = new HashMap<>();
    private final Map<Integer, List<ViewFluid>> fluids = new HashMap<>();
    private final List<Text> text = new ArrayList<>();
    private final List<Blocks> blocks = new ArrayList<>();

    ViewRecipe(ViewCategory<?> category, String id) {
        this.category = category;
        this.id = id;
    }

    /** The items shown in a slot (alternatives cycle). Empty stacks are dropped. */
    public ViewRecipe items(int slot, List<ItemStack> stacks) {
        ViewSlot s = category.slot(slot);
        if (s.kind() != ViewSlot.Kind.ITEM) throw new IllegalArgumentException("slot " + slot + " of " + category.id() + " holds fluids");
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack st : stacks) if (st != null && !st.isEmpty()) copy.add(st.copy());
        items.put(slot, Collections.unmodifiableList(copy));
        return this;
    }

    public ViewRecipe item(int slot, ItemStack stack) { return items(slot, List.of(stack)); }

    /** The fluids shown in a slot (alternatives cycle). */
    public ViewRecipe fluids(int slot, List<ViewFluid> list) {
        ViewSlot s = category.slot(slot);
        if (s.kind() != ViewSlot.Kind.FLUID) throw new IllegalArgumentException("slot " + slot + " of " + category.id() + " holds items");
        fluids.put(slot, List.copyOf(list));
        return this;
    }

    public ViewRecipe fluid(int slot, ViewFluid fluid) { return fluids(slot, List.of(fluid)); }

    /** A line of text at a fixed place, in the category's text colour. */
    public ViewRecipe text(Component line, int x, int y) { text.add(new Text(line, x, y, category.textColour())); return this; }

    /** A centred line of text at height {@code y}. */
    public ViewRecipe text(Component line, int y) { return text(line, -1, y); }

    /** Draws a multiblock pattern's blocks in a box (the viewers that can draw it do, the rest skip it). */
    public ViewRecipe blocks(FId pattern, int x, int y, int w, int h) { blocks.add(new Blocks(pattern, x, y, w, h)); return this; }

    public ViewCategory<?> category() { return category; }
    /** A stable id for the recipe ({@code <category id>/<recipe key>}), for viewers that need one (EMI). */
    public String id() { return id; }
    public List<ItemStack> items(int slot) { return items.getOrDefault(slot, List.of()); }
    public List<ViewFluid> fluids(int slot) { return fluids.getOrDefault(slot, List.of()); }
    public List<Text> text() { return Collections.unmodifiableList(text); }
    public List<Blocks> blocks() { return Collections.unmodifiableList(blocks); }

    /** Every item in slots of the given role, for viewers that index recipes by input and output. */
    public List<ItemStack> stacks(ViewSlot.Role role) {
        List<ItemStack> out = new ArrayList<>();
        for (ViewSlot s : category.slots()) if (s.role() == role) out.addAll(items(s.index()));
        return out;
    }
}
