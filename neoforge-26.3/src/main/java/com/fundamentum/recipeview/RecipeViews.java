package com.fundamentum.recipeview;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One place to declare what JEI, EMI and REI show: recipe categories ({@link ViewCategory}), items kept out of their lists,
 * information pages, and items whose data makes them different entries. Each viewer that is installed reads these when it
 * loads; with none installed nothing here is ever read. Declare from mod construction or client setup; the suppliers are
 * only called by a viewer, on the client.
 *
 * <p>This package uses nothing else from Fundamentum, so it can ship on its own.
 */
public final class RecipeViews {
    /** An information page: the stacks it belongs to and its lines. */
    public record Info(Supplier<? extends List<ItemStack>> stacks, Supplier<? extends List<Component>> lines) {}
    /** Items of one kind whose stacks differ by data: {@code key} returns the part that makes an entry its own ("" = plain). */
    public record Subtypes(Supplier<? extends Item> item, Function<ItemStack, String> key) {}

    private static final Logger LOG = LoggerFactory.getLogger("fundamentum/recipeview");
    private static final Map<String, ViewCategory<?>> CATEGORIES = new LinkedHashMap<>();
    private static final List<Predicate<ItemStack>> HIDDEN = new CopyOnWriteArrayList<>();
    private static final List<Info> INFO = new CopyOnWriteArrayList<>();
    private static final List<Supplier<? extends Collection<Info>>> INFO_SOURCES = new CopyOnWriteArrayList<>();
    private static final List<Subtypes> SUBTYPES = new CopyOnWriteArrayList<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private RecipeViews() {}

    /** Declares a category with the id {@code namespace:path}. Ids must be unique. */
    public static synchronized <T> ViewCategory<T> category(String id) {
        Objects.requireNonNull(id, "id");
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("recipe view category id must be namespace:path in lower case, got " + id);
        }
        if (CATEGORIES.containsKey(id)) throw new IllegalStateException("recipe view category " + id + " is declared twice");
        ViewCategory<T> c = new ViewCategory<>(id);
        CATEGORIES.put(id, c);
        return c;
    }

    /** Hides every stack the rule matches from all viewers' item lists (recipes still show them). */
    public static void hide(Predicate<ItemStack> rule) { HIDDEN.add(Objects.requireNonNull(rule)); }

    /** Hides every stack of these items, whatever their data. */
    public static void hide(Supplier<? extends Item> item) {
        Objects.requireNonNull(item);
        hide(stack -> stack.getItem() == item.get());
    }

    /** Adds an information page to the stacks. */
    public static void info(Supplier<? extends List<ItemStack>> stacks, Supplier<? extends List<Component>> lines) {
        INFO.add(new Info(Objects.requireNonNull(stacks), Objects.requireNonNull(lines)));
    }

    /**
     * Adds information pages whose number is only known later (for example one per data-driven entry): the supplier is
     * read each time a viewer loads.
     */
    public static void infoPages(Supplier<? extends Collection<Info>> pages) {
        INFO_SOURCES.add(Objects.requireNonNull(pages));
    }

    /** Makes stacks of {@code item} separate entries by the key their data gives. */
    public static void subtypes(Supplier<? extends Item> item, Function<ItemStack, String> key) {
        SUBTYPES.add(new Subtypes(Objects.requireNonNull(item), Objects.requireNonNull(key)));
    }

    /** Called by a viewer plugin when the viewer can reload; {@link #refresh} runs it. */
    public static void onRefresh(Runnable listener) { LISTENERS.add(Objects.requireNonNull(listener)); }

    /** Tells the viewers that recipes (or hidden items) changed, for data that reaches the client after the viewer loaded. */
    public static void refresh() {
        for (Runnable r : LISTENERS) {
            try { r.run(); } catch (RuntimeException e) { warnOnce("refresh/" + r.getClass(), "A recipe viewer failed to refresh: " + e); }
        }
    }

    public static synchronized List<ViewCategory<?>> categories() { return List.copyOf(CATEGORIES.values()); }
    /** The declared category with this id, or null. */
    public static synchronized ViewCategory<?> find(String id) { return CATEGORIES.get(id); }
    public static List<Info> infos() { return Collections.unmodifiableList(INFO); }
    public static List<Subtypes> subtypes() { return Collections.unmodifiableList(SUBTYPES); }

    /** Whether any hiding rule matches the stack. A rule that throws counts as no match and is logged once. */
    public static boolean hidden(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        for (Predicate<ItemStack> rule : HIDDEN) {
            try {
                if (rule.test(stack)) return true;
            } catch (RuntimeException e) {
                warnOnce("hide/" + rule.getClass(), "A recipe viewer hiding rule failed: " + e);
            }
        }
        return false;
    }

    /** The subtype key of a stack, or "" when no subtype rule covers its item. */
    public static String subtypeKey(ItemStack stack) {
        for (Subtypes s : SUBTYPES) {
            if (stack.getItem() == s.item().get()) {
                String k = s.key().apply(stack);
                return k == null ? "" : k;
            }
        }
        return "";
    }

    /** Built information pages with empty ones dropped, for the viewer plugins. */
    public static List<Map.Entry<List<ItemStack>, List<Component>>> builtInfo() {
        List<Map.Entry<List<ItemStack>, List<Component>>> out = new ArrayList<>();
        List<Info> all = new ArrayList<>(INFO);
        for (Supplier<? extends Collection<Info>> src : INFO_SOURCES) {
            try {
                Collection<Info> more = src.get();
                if (more != null) all.addAll(more);
            } catch (RuntimeException e) {
                warnOnce("infos/" + src.getClass(), "A recipe viewer information page source failed: " + e);
            }
        }
        for (Info i : all) {
            try {
                List<ItemStack> stacks = i.stacks().get();
                List<Component> lines = i.lines().get();
                if (stacks != null && !stacks.isEmpty() && lines != null && !lines.isEmpty()) out.add(Map.entry(List.copyOf(stacks), List.copyOf(lines)));
            } catch (RuntimeException e) {
                warnOnce("info/" + i, "A recipe viewer information page failed: " + e);
            }
        }
        return out;
    }

    static void warnOnce(String key, String message) {
        if (WARNED.add(key)) LOG.warn(message);
    }
}
