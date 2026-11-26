package com.fundamentum.slots;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.port.FTags;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.event.CurioChangeEvent;
import top.theillusivec4.curios.api.type.ICuriosMenu;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

/**
 * Fundamentum's accessory slots: {@link #WINGS} (with a cosmetic twin), {@link #MOUNT}, {@link #HOOK}, {@link #SUMMON}
 * and {@link #QUIVER}, on players. They are Curios slots (Curios is required), defined as data in
 * {@code data/fundamentum/curios/}, and every call goes straight to Curios, so the same calls reach other mods' Curios
 * slots too.
 * <p>
 * An item may go in a slot when it is in the item tag {@code #curios:<slot>} (e.g. {@code #curios:wings}) or passes a
 * {@link #validator} added for that slot. Every Curios slot change posts {@link Events.SlotEquipped} and
 * {@link Events.SlotUnequipped} on the server, for every slot type, Fundamentum's and other mods' alike. Curios reports
 * changes to worn items as the entity ticks; a cosmetic twin changed through {@link #setCosmetic} posts the events at
 * once, and one changed by hand in Curios' screen posts them as the screen's slots change (at the latest when it closes).
 * Renderers draw {@link #shown}: the cosmetic twin when it holds something, else the worn item.
 */
public final class Slots {
    public static final String WINGS = "wings";
    public static final String MOUNT = "mount";
    public static final String HOOK = "hook";
    public static final String SUMMON = "summon";
    public static final String QUIVER = "quiver";

    /** Where an item sits: its slot type, the index in it, and whether that is the cosmetic twin. */
    public record Worn(String slot, int index, boolean cosmetic, ItemStack stack) { }

    private static final Map<String, List<Predicate<ItemStack>>> VALIDATORS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final List<Consumer<LivingEntity>> LISTENERS = new CopyOnWriteArrayList<>();
    private static boolean initialised;

    /** Called once while Fundamentum loads: forwards Curios' slot changes as Fundamentum's slot events. */
    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        SlotMigration.init();
        // Curios checks every slot of every type for changes as each entity ticks
        NeoForge.EVENT_BUS.addListener((CurioChangeEvent.Item event) -> changed(event.getEntity(),
            event.getSlotContext().identifier(), event.getSlotContext().index(), event.getSlotContext().cosmetic(),
            event.getFrom(), event.getTo()));
        // Curios never reports cosmetic twins, so watch its screen while it is open
        NeoForge.EVENT_BUS.addListener((PlayerContainerEvent.Open event) -> {
            if (event.getEntity() instanceof ServerPlayer p) menuOpened(p, event.getContainer());
        });
        NeoForge.EVENT_BUS.addListener((PlayerContainerEvent.Close event) -> {
            if (event.getEntity() instanceof ServerPlayer p) menuClosed(p);
        });
    }

    /**
     * Always true: Curios is required.
     *
     * @deprecated Curios is always present.
     */
    @Deprecated
    public static boolean curiosLoaded() {
        return true;
    }

    // ---- validity

    /** Lets {@code test} allow more items into a slot type than its {@code #curios:<slot>} tag holds. */
    public static void validator(String slot, Predicate<ItemStack> test) {
        VALIDATORS.computeIfAbsent(slot, k -> new CopyOnWriteArrayList<>()).add(test);
    }

    /** Whether {@code stack} may go in a slot type: in {@code #curios:<slot>}, or allowed by a {@link #validator}. */
    public static boolean allows(String slot, ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (stack.is(FTags.item(FId.of("curios", slot)))) return true;
        for (Predicate<ItemStack> test : VALIDATORS.getOrDefault(slot, List.of())) {
            if (test.test(stack)) return true;
        }
        return false;
    }

    // ---- reading

    /** How many slots of a type the entity has (0 when it has none). */
    public static int size(LivingEntity entity, String slot) {
        return size(entity, slot, false);
    }

    /** How many cosmetic twins of a slot type the entity has (0 when the type has none). */
    public static int cosmeticSize(LivingEntity entity, String slot) {
        return size(entity, slot, true);
    }

    /** The item in one slot, or empty. */
    public static ItemStack get(LivingEntity entity, String slot, int index) {
        return get(entity, slot, index, false);
    }

    /** The item in one cosmetic twin, or empty. */
    public static ItemStack getCosmetic(LivingEntity entity, String slot, int index) {
        return get(entity, slot, index, true);
    }

    /** The first item worn in a slot type (not its cosmetic twin), or empty. */
    public static ItemStack equipped(LivingEntity entity, String slot) {
        return first(stacks(entity, slot));
    }

    /** Every item worn in a slot type (all of its slots, not the cosmetic twins). */
    public static List<ItemStack> allEquipped(LivingEntity entity, String slot) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : stacks(entity, slot)) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** The first item in a slot type's cosmetic twins, or empty; what to draw instead of {@link #equipped}. */
    public static ItemStack cosmetic(LivingEntity entity, String slot) {
        int n = cosmeticSize(entity, slot);
        for (int i = 0; i < n; i++) {
            ItemStack s = getCosmetic(entity, slot, i);
            if (!s.isEmpty()) return s;
        }
        return ItemStack.EMPTY;
    }

    /**
     * The render hook: what to draw for a slot type, the cosmetic twin when it holds something, else the worn item, else
     * empty. Renderers (wings, back items, belts) draw this rather than {@link #equipped}.
     */
    public static ItemStack shown(LivingEntity entity, String slot) {
        ItemStack c = cosmetic(entity, slot);
        return c.isEmpty() ? equipped(entity, slot) : c;
    }

    /** Every stack in a slot type, by slot index, empty ones included. */
    public static List<ItemStack> stacks(LivingEntity entity, String slot) {
        int n = size(entity, slot);
        List<ItemStack> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(get(entity, slot, i));
        return out;
    }

    /** Every item worn in any slot (not cosmetic ones), Curios slots of other mods included. */
    public static List<ItemStack> allWorn(LivingEntity entity) {
        List<ItemStack> out = new ArrayList<>();
        for (Worn w : all(entity)) out.add(w.stack());
        return out;
    }

    /** The first worn item (cosmetic twins excluded) that {@code test} accepts, with where it sits. */
    public static Optional<Worn> firstMatching(LivingEntity entity, Predicate<ItemStack> test) {
        for (Worn w : all(entity)) {
            if (test.test(w.stack())) return Optional.of(w);
        }
        return Optional.empty();
    }

    /** The first item in one slot type that {@code test} accepts. */
    public static Optional<Worn> firstMatching(LivingEntity entity, String slot, Predicate<ItemStack> test) {
        int n = size(entity, slot);
        for (int i = 0; i < n; i++) {
            ItemStack s = get(entity, slot, i);
            if (!s.isEmpty() && test.test(s)) return Optional.of(new Worn(slot, i, false, s));
        }
        return Optional.empty();
    }

    // ---- writing (server side)

    /** Puts {@code stack} in one slot, replacing what was there; false if the entity has no such slot. No validity check. */
    public static boolean set(LivingEntity entity, String slot, int index, ItemStack stack) {
        return set(entity, slot, index, false, stack);
    }

    /** Puts {@code stack} in one cosmetic twin; false if there is no such twin. */
    public static boolean setCosmetic(LivingEntity entity, String slot, int index, ItemStack stack) {
        return set(entity, slot, index, true, stack);
    }

    /** The same as {@link #set}; the name older callers use. */
    public static boolean equip(LivingEntity entity, String slot, int index, ItemStack stack) {
        return set(entity, slot, index, stack);
    }

    /**
     * Puts {@code stack} in the first empty slot of a type that {@link #allows} it, as a right-click equip would.
     * Returns where it went, or empty when it is not allowed there or every slot is full.
     */
    public static Optional<Worn> tryEquip(LivingEntity entity, String slot, ItemStack stack) {
        if (stack.isEmpty() || !allows(slot, stack)) return Optional.empty();
        int n = size(entity, slot);
        for (int i = 0; i < n; i++) {
            if (get(entity, slot, i).isEmpty() && set(entity, slot, i, stack)) return Optional.of(new Worn(slot, i, false, stack));
        }
        return Optional.empty();
    }

    /** Empties one slot and returns what was in it. */
    public static ItemStack unequip(LivingEntity entity, String slot, int index) {
        ItemStack was = get(entity, slot, index).copy();
        if (!was.isEmpty()) set(entity, slot, index, ItemStack.EMPTY);
        return was;
    }

    // ---- change listening

    /**
     * Calls {@code listener} whenever an item in any slot of an entity changes, as Curios' own change check finds it (on
     * the server and the client), and when a cosmetic twin is changed through {@link #setCosmetic}.
     */
    public static void onChange(Consumer<LivingEntity> listener) {
        LISTENERS.add(listener);
    }

    /** A slot changed: tell the listeners and, on the server, post the slot events. */
    static void changed(LivingEntity entity, String slot, int index, boolean cosmetic, ItemStack from, ItemStack to) {
        if (ItemStack.matches(from, to)) return;
        if (!entity.level().isClientSide()) {
            if (!from.isEmpty()) FEvents.post(new Events.SlotUnequipped(entity, slot, index, cosmetic, from.copy()));
            if (!to.isEmpty()) FEvents.post(new Events.SlotEquipped(entity, slot, index, cosmetic, to.copy()));
        }
        for (Consumer<LivingEntity> l : LISTENERS) l.accept(entity);
    }

    // ---- cosmetic twins changed in Curios' screen

    /** Cosmetic twins as they were when last looked at, per player with a Curios screen open. */
    private static final Map<ServerPlayer, Map<String, List<ItemStack>>> WATCHED = new java.util.WeakHashMap<>();

    /** A menu opened: when it is Curios' screen, remember the cosmetic twins and compare them as its slots change. */
    static boolean menuOpened(ServerPlayer player, AbstractContainerMenu menu) {
        if (!(menu instanceof ICuriosMenu)) return false;
        watch(player, menu);
        return true;
    }

    /** Watches {@code menu} for slot changes that may have changed {@code player}'s cosmetic twins. */
    static void watch(ServerPlayer player, AbstractContainerMenu menu) {
        synchronized (WATCHED) {
            WATCHED.put(player, cosmetics(player));
        }
        menu.addSlotListener(new ContainerListener() {
            @Override
            public void slotChanged(AbstractContainerMenu m, int slot, ItemStack stack) {
                compareCosmetics(player);
            }

            @Override
            public void dataChanged(AbstractContainerMenu m, int index, int value) { }
        });
    }

    /** A menu closed: a last comparison, then stop watching. */
    static void menuClosed(ServerPlayer player) {
        compareCosmetics(player);
        synchronized (WATCHED) {
            WATCHED.remove(player);
        }
    }

    /** Posts the slot events for every cosmetic twin that differs from the remembered copy, then remembers the new ones. */
    static void compareCosmetics(ServerPlayer player) {
        Map<String, List<ItemStack>> was;
        synchronized (WATCHED) {
            was = WATCHED.get(player);
        }
        if (was == null) return;
        Map<String, List<ItemStack>> now = cosmetics(player);
        synchronized (WATCHED) {
            if (WATCHED.containsKey(player)) WATCHED.put(player, now);
        }
        now.forEach((slot, stacks) -> {
            List<ItemStack> old = was.getOrDefault(slot, List.of());
            for (int i = 0; i < stacks.size(); i++) {
                changed(player, slot, i, true, i < old.size() ? old.get(i) : ItemStack.EMPTY, stacks.get(i));
            }
        });
    }

    /** Keeps a watched player's remembered copy in step with a change already posted. */
    private static void remember(ServerPlayer player, String slot, int index, ItemStack stack) {
        synchronized (WATCHED) {
            Map<String, List<ItemStack>> m = WATCHED.get(player);
            if (m == null) return;
            List<ItemStack> l = m.get(slot);
            if (l != null && index < l.size()) l.set(index, stack.copy());
        }
    }

    /** Copies of every cosmetic twin, by slot type. */
    private static Map<String, List<ItemStack>> cosmetics(LivingEntity entity) {
        Map<String, List<ItemStack>> out = new java.util.HashMap<>();
        ICuriosItemHandler inventory = inventory(entity);
        if (inventory == null) return out;
        inventory.getCurios().forEach((id, h) -> {
            if (!h.hasCosmetic()) return;
            IDynamicStackHandler s = h.getCosmeticStacks();
            List<ItemStack> copies = new ArrayList<>(s.getSlots());
            for (int i = 0; i < s.getSlots(); i++) copies.add(s.getStackInSlot(i).copy());
            out.put(id, copies);
        });
        return out;
    }

    /** Sets a slot straight in Curios, posting nothing, as Curios' screen does; for the self test. */
    static boolean setQuietly(LivingEntity entity, String slot, int index, boolean cosmetic, ItemStack stack) {
        ICurioStacksHandler h = handler(entity, slot);
        if (h == null || cosmetic && !h.hasCosmetic()) return false;
        IDynamicStackHandler s = stacks(h, cosmetic);
        if (index < 0 || index >= s.getSlots()) return false;
        s.setStackInSlot(index, stack);
        return true;
    }

    // ---- Curios

    private static ICuriosItemHandler inventory(LivingEntity entity) {
        return CuriosApi.getCuriosInventoryOrNull(entity);
    }

    private static ICurioStacksHandler handler(LivingEntity entity, String slot) {
        ICuriosItemHandler inventory = inventory(entity);
        return inventory == null ? null : inventory.getCurios().get(slot);
    }

    private static IDynamicStackHandler stacks(ICurioStacksHandler handler, boolean cosmetic) {
        return cosmetic ? handler.getCosmeticStacks() : handler.getStacks();
    }

    private static int size(LivingEntity entity, String slot, boolean cosmetic) {
        ICurioStacksHandler h = handler(entity, slot);
        if (h == null || cosmetic && !h.hasCosmetic()) return 0;
        return stacks(h, cosmetic).getSlots();
    }

    private static ItemStack get(LivingEntity entity, String slot, int index, boolean cosmetic) {
        ICurioStacksHandler h = handler(entity, slot);
        if (h == null || cosmetic && !h.hasCosmetic()) return ItemStack.EMPTY;
        IDynamicStackHandler s = stacks(h, cosmetic);
        return index < 0 || index >= s.getSlots() ? ItemStack.EMPTY : s.getStackInSlot(index);
    }

    private static boolean set(LivingEntity entity, String slot, int index, boolean cosmetic, ItemStack stack) {
        ICurioStacksHandler h = handler(entity, slot);
        if (h == null || cosmetic && !h.hasCosmetic()) return false;
        IDynamicStackHandler s = stacks(h, cosmetic);
        if (index < 0 || index >= s.getSlots()) return false;
        ItemStack was = s.getStackInSlot(index).copy();
        s.setStackInSlot(index, stack);
        // Curios reports worn items as the entity ticks, but never cosmetic twins
        if (cosmetic) {
            changed(entity, slot, index, true, was, stack);
            if (entity instanceof ServerPlayer p) remember(p, slot, index, stack);
        }
        return true;
    }

    /** Every non-empty worn stack (not cosmetic twins), every slot type, in slot order. */
    private static List<Worn> all(LivingEntity entity) {
        ICuriosItemHandler inventory = inventory(entity);
        if (inventory == null) return List.of();
        List<Worn> out = new ArrayList<>();
        inventory.getCurios().forEach((id, h) -> {
            IDynamicStackHandler s = h.getStacks();
            for (int i = 0; i < s.getSlots(); i++) {
                ItemStack stack = s.getStackInSlot(i);
                if (!stack.isEmpty()) out.add(new Worn(id, i, false, stack));
            }
        });
        return out;
    }

    private static ItemStack first(List<ItemStack> stacks) {
        for (ItemStack s : stacks) {
            if (!s.isEmpty()) return s;
        }
        return ItemStack.EMPTY;
    }

    private Slots() { }
}
