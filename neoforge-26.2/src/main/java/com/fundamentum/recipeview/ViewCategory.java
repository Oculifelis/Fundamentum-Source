package com.fundamentum.recipeview;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The declaration of one recipe type for the recipe viewers: an id, a title, an icon, a fixed-size area with slots, an
 * optional progress arrow, the recipes and how each fills the slots. Made with {@link RecipeViews#category}; JEI, EMI and
 * REI each build their own page from it.
 *
 * <p>{@code recipes} is asked whenever a viewer (re)loads, on the client, so it may read client-side copies of synced data.
 * {@code fill} turns one of the mod's recipe objects into a {@link ViewRecipe}.
 */
public final class ViewCategory<T> {
    /** The progress arrow: top-left corner and how many ticks one sweep takes (0 = a still arrow). */
    public record Arrow(int x, int y, int ticks) {}

    private final String id;
    private Component title;
    private Supplier<ItemStack> icon = () -> ItemStack.EMPTY;
    private int width = 120, height = 40, textColour = 0xFF404040;
    private final List<ViewSlot> slots = new ArrayList<>();
    private Arrow arrow;
    private Supplier<? extends Collection<? extends T>> recipes = List::of;
    private Function<? super T, String> key = t -> Integer.toHexString(System.identityHashCode(t));
    private BiConsumer<? super T, ViewRecipe> fill = (t, r) -> {};
    private Supplier<? extends List<ItemStack>> catalysts = List::of;

    ViewCategory(String id) {
        this.id = id;
        this.title = Component.literal(id);
    }

    public ViewCategory<T> title(Component title) { this.title = Objects.requireNonNull(title); return this; }
    public ViewCategory<T> icon(Supplier<ItemStack> icon) { this.icon = Objects.requireNonNull(icon); return this; }
    public ViewCategory<T> size(int width, int height) {
        if (width < 18 || height < 18) throw new IllegalArgumentException("category " + id + " is smaller than a slot");
        this.width = width;
        this.height = height;
        return this;
    }
    /** ARGB colour of the category's text (default dark grey, as in vanilla screens). */
    public ViewCategory<T> textColour(int argb) { this.textColour = argb; return this; }

    /** Adds a slot and returns its index (0, 1, 2, ... in the order added). Call {@link #size} first. */
    public int slot(ViewSlot.Role role, ViewSlot.Kind kind, int x, int y) {
        if (x < 0 || y < 0 || x + 18 > width || y + 18 > height) {
            throw new IllegalArgumentException("slot at " + x + "," + y + " is outside category " + id + " (" + width + "x" + height + ")");
        }
        ViewSlot s = new ViewSlot(slots.size(), Objects.requireNonNull(role), Objects.requireNonNull(kind), x, y);
        slots.add(s);
        return s.index();
    }

    public int input(int x, int y) { return slot(ViewSlot.Role.INPUT, ViewSlot.Kind.ITEM, x, y); }
    public int output(int x, int y) { return slot(ViewSlot.Role.OUTPUT, ViewSlot.Kind.ITEM, x, y); }
    public int fluidInput(int x, int y) { return slot(ViewSlot.Role.INPUT, ViewSlot.Kind.FLUID, x, y); }
    public int fluidOutput(int x, int y) { return slot(ViewSlot.Role.OUTPUT, ViewSlot.Kind.FLUID, x, y); }

    public ViewCategory<T> arrow(int x, int y, int ticks) { this.arrow = new Arrow(x, y, Math.max(0, ticks)); return this; }

    /** The recipes, asked each time a viewer loads. */
    public ViewCategory<T> recipes(Supplier<? extends Collection<? extends T>> recipes) { this.recipes = Objects.requireNonNull(recipes); return this; }
    /** A stable key per recipe (its id, say); EMI needs one. Defaults to the object's identity. */
    public ViewCategory<T> key(Function<? super T, String> key) { this.key = Objects.requireNonNull(key); return this; }
    public ViewCategory<T> fill(BiConsumer<? super T, ViewRecipe> fill) { this.fill = Objects.requireNonNull(fill); return this; }
    /** Blocks or tools that make this recipe type (shown beside the category). */
    public ViewCategory<T> catalysts(Supplier<? extends List<ItemStack>> catalysts) { this.catalysts = Objects.requireNonNull(catalysts); return this; }

    public String id() { return id; }
    public String namespace() { return id.substring(0, id.indexOf(':')); }
    public String path() { return id.substring(id.indexOf(':') + 1); }
    public Component title() { return title; }
    public ItemStack icon() { ItemStack s = icon.get(); return s == null ? ItemStack.EMPTY : s; }
    public int width() { return width; }
    public int height() { return height; }
    public int textColour() { return textColour; }
    public List<ViewSlot> slots() { return Collections.unmodifiableList(slots); }
    public ViewSlot slot(int index) {
        if (index < 0 || index >= slots.size()) throw new IllegalArgumentException("category " + id + " has no slot " + index);
        return slots.get(index);
    }
    public Arrow arrow() { return arrow; }
    public List<ItemStack> catalysts() { List<ItemStack> c = catalysts.get(); return c == null ? List.of() : c; }

    /** Builds the shown recipes now. A recipe whose filler throws is skipped and logged once, so one bad recipe hides only itself. */
    public List<ViewRecipe> build() {
        List<ViewRecipe> out = new ArrayList<>();
        Collection<? extends T> all = recipes.get();
        if (all == null) return out;
        for (T t : all) {
            String k;
            try {
                k = key.apply(t);
                ViewRecipe r = new ViewRecipe(this, id + "/" + k);
                fill.accept(t, r);
                out.add(r);
            } catch (RuntimeException e) {
                RecipeViews.warnOnce(id + "/" + t, "Recipe viewer category " + id + " skipped a recipe: " + e);
            }
        }
        return out;
    }
}
