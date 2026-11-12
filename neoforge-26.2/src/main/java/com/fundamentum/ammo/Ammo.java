package com.fundamentum.ammo;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.slots.Slots;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingGetProjectileEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finding and using ammo, for any ranged weapon. {@link #find} looks through the shooter's offhand, main hand, the
 * {@code quiver} Curios slot (the quiver item itself if it is ammo, otherwise the contents of a quiver, see {@link Quivers}),
 * the hotbar and the rest of the inventory, in the order set by {@link #setOrder} or by
 * {@code data/<ns>/fundamentum/ammo/<name>.json} ({@code {"order": ["offhand", "quiver", "mainhand", "hotbar", "inventory"]}}).
 * {@link #consume} fires {@link AmmoConsumeEvent} and rolls {@code fundamentum:ammo_conservation} per unit. The vanilla bow
 * and crossbow (and any weapon built on {@code ProjectileWeaponItem}) go through both, so quivers work with them.
 *
 * <p>Static calls for scripts: {@code Ammo.find(player, bow, s => s.is('minecraft:arrow'))}.
 */
public final class Ammo {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");

    /** Where ammo can be found. Non-players only have hands and the quiver slot. */
    public enum Source { OFFHAND, MAINHAND, QUIVER, HOTBAR, INVENTORY }

    public static final List<Source> DEFAULT_ORDER = List.of(Source.OFFHAND, Source.MAINHAND, Source.QUIVER, Source.HOTBAR, Source.INVENTORY);
    private static volatile @Nullable List<Source> codeOrder;
    private static volatile List<Source> dataOrder = DEFAULT_ORDER;

    public static final DataTable<JsonObject> TABLE = DataTables.registerJson(Identifier.fromNamespaceAndPath("fundamentum", "ammo"),
            "fundamentum/ammo")
        .onLoad(t -> loadOrder(t.rawEntries()));

    /**
     * Ammo that was found. {@code slot} is the inventory slot (hotbar and inventory), or the Curios slot index (quiver);
     * {@code index} is the position inside the quiver, or -1 when {@code stack} is the item in the slot itself. For a quiver's
     * contents {@code stack} is a copy and {@code quiver} the live quiver stack; otherwise {@code stack} is the live stack.
     */
    public record Found(Source source, int slot, int index, @Nullable ItemStack quiver, ItemStack stack) {
        public boolean insideQuiver() {
            return this.quiver != null && this.index >= 0;
        }
    }

    // ---- order ------------------------------------------------------------------------------------------------------

    /** The search order in use. */
    public static List<Source> order() {
        List<Source> code = codeOrder;
        return code != null ? code : dataOrder;
    }

    /** Sets the search order from code (wins over data); null goes back to data or the default. */
    public static void setOrder(@Nullable List<Source> order) {
        codeOrder = order == null ? null : List.copyOf(order);
    }

    private static void loadOrder(Map<Identifier, JsonObject> files) {
        List<Source> found = DEFAULT_ORDER;
        for (Map.Entry<Identifier, JsonObject> e : files.entrySet()) {
            if (e.getValue().has("order")) {
                try {
                    List<Source> order = new ArrayList<>();
                    for (JsonElement s : e.getValue().getAsJsonArray("order")) {
                        order.add(Source.valueOf(s.getAsString().toUpperCase(Locale.ROOT)));
                    }
                    found = List.copyOf(order);
                } catch (RuntimeException ex) {
                    LOG.error("Fundamentum: ammo order in {} is broken: {}", e.getKey(), ex.getMessage());
                }
            }
        }
        dataOrder = found;
    }

    // ---- finding ----------------------------------------------------------------------------------------------------

    /** The first ammo matching {@code predicate}, by {@link #order}, or null. */
    public static @Nullable Found find(LivingEntity shooter, ItemStack weapon, Predicate<ItemStack> predicate) {
        return find(shooter, weapon, predicate, predicate);
    }

    /**
     * Like {@link #find(LivingEntity, ItemStack, Predicate)}, with a separate test for ammo held in a hand (vanilla lets a
     * crossbow fire a held firework but not one from the inventory).
     */
    public static @Nullable Found find(LivingEntity shooter, ItemStack weapon, Predicate<ItemStack> predicate, Predicate<ItemStack> held) {
        for (Source source : order()) {
            Found f = switch (source) {
                case OFFHAND -> hand(shooter, Source.OFFHAND, shooter.getOffhandItem(), weapon, held);
                case MAINHAND -> hand(shooter, Source.MAINHAND, shooter.getMainHandItem(), weapon, held);
                case QUIVER -> quiver(shooter, predicate);
                case HOTBAR -> shooter instanceof Player p ? inventory(p, Source.HOTBAR, 0, 9, predicate) : null;
                case INVENTORY -> shooter instanceof Player p ? inventory(p, Source.INVENTORY, 9, Inventory.INVENTORY_SIZE, predicate) : null;
            };
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    private static @Nullable Found hand(LivingEntity shooter, Source source, ItemStack stack, ItemStack weapon, Predicate<ItemStack> held) {
        return !stack.isEmpty() && stack != weapon && held.test(stack) ? new Found(source, -1, -1, null, stack) : null;
    }

    private static @Nullable Found inventory(Player player, Source source, int from, int to, Predicate<ItemStack> predicate) {
        Inventory inv = player.getInventory();
        for (int i = from; i < to; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return new Found(source, i, -1, null, stack);
            }
        }
        return null;
    }

    private static @Nullable Found quiver(LivingEntity shooter, Predicate<ItemStack> predicate) {
        List<ItemStack> worn = Slots.stacks(shooter, Slots.QUIVER);
        for (int s = 0; s < worn.size(); s++) {
            ItemStack q = worn.get(s);
            if (q.isEmpty()) {
                continue;
            }
            QuiverAccess access = Quivers.get(q);
            if (access == null) {
                if (predicate.test(q)) {
                    return new Found(Source.QUIVER, s, -1, q, q);
                }
                continue;
            }
            List<ItemStack> contents = access.contents(q);
            for (int i = 0; i < contents.size(); i++) {
                ItemStack stack = contents.get(i);
                if (!stack.isEmpty() && predicate.test(stack)) {
                    return new Found(Source.QUIVER, s, i, q, stack);
                }
            }
        }
        return null;
    }

    // ---- using ------------------------------------------------------------------------------------------------------

    /** The shooter's chance (0 to 1) to keep each unit of ammo. */
    public static double conservation(LivingEntity shooter) {
        AttributeInstance instance = shooter.getAttribute(StatAttributes.AMMO_CONSERVATION);
        return instance == null ? 0 : Math.max(0, Math.min(1, instance.getValue()));
    }

    /**
     * Uses {@code amount} of the found ammo: fires {@link AmmoConsumeEvent}, then keeps each unit with the conservation
     * chance. Creative players use nothing. Returns how many units were taken. Server side only (0 on a client).
     */
    public static int consume(LivingEntity shooter, ItemStack weapon, Found found, int amount) {
        if (shooter.level().isClientSide() || amount <= 0) {
            return 0;
        }
        if (shooter instanceof Player p && p.getAbilities().instabuild) {
            return 0;
        }
        AmmoConsumeEvent event = new AmmoConsumeEvent(shooter, weapon, found, amount, conservation(shooter));
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            return 0;
        }
        if (FEvents.post(new Events.AmmoDrawn(shooter, weapon, found.stack()))) {
            return 0;
        }
        int take = 0;
        for (int i = 0; i < event.getAmount(); i++) {
            if (event.getConservationChance() <= 0 || shooter.getRandom().nextDouble() >= event.getConservationChance()) {
                take++;
            }
        }
        return take <= 0 ? 0 : take(shooter, found, take);
    }

    /** Finds ammo matching {@code predicate} and uses {@code amount} of it; the found ammo (before use), or null. */
    public static @Nullable Found consume(LivingEntity shooter, ItemStack weapon, Predicate<ItemStack> predicate, int amount) {
        Found f = find(shooter, weapon, predicate);
        if (f != null) {
            consume(shooter, weapon, f, amount);
        }
        return f;
    }

    private static int take(LivingEntity shooter, Found found, int amount) {
        if (found.insideQuiver()) {
            QuiverAccess access = Quivers.get(found.quiver());
            if (access == null) {
                return 0;
            }
            List<ItemStack> contents = new ArrayList<>(access.contents(found.quiver()));
            if (found.index() >= contents.size() || !ItemStack.isSameItem(contents.get(found.index()), found.stack())) {
                return 0;
            }
            ItemStack slot = contents.get(found.index());
            int n = Math.min(amount, slot.getCount());
            slot.shrink(n);
            contents.set(found.index(), slot.isEmpty() ? ItemStack.EMPTY : slot);
            access.setContents(found.quiver(), contents);
            return n;
        }
        ItemStack live = found.stack();
        int n = Math.min(amount, live.getCount());
        live.shrink(n);
        return n;
    }

    // ---- the vanilla bow and crossbow -------------------------------------------------------------------------------

    /** Ammo handed to a vanilla weapon as a copy; what the weapon took from the copy is consumed for real at the end of the tick. */
    private record Pending(Player player, ItemStack weapon, Found found, ItemStack proxy, int before) {}

    private static final List<Pending> PENDING = new ArrayList<>();

    private static void onGetProjectile(LivingGetProjectileEvent event) {
        if (!(event.getEntity() instanceof Player player) || !(event.getProjectileWeaponItemStack().getItem() instanceof ProjectileWeaponItem weaponItem)) {
            return;
        }
        ItemStack weapon = event.getProjectileWeaponItemStack();
        Found found = find(player, weapon, weaponItem.getAllSupportedProjectiles(weapon), weaponItem.getSupportedHeldProjectiles(weapon));
        if (found == null) {
            return;
        }
        ItemStack proxy = found.stack().copy();
        event.setProjectileItemStack(proxy);
        if (!player.level().isClientSide()) {
            synchronized (PENDING) {
                PENDING.add(new Pending(player, weapon, found, proxy, proxy.getCount()));
            }
        }
    }

    private static void settle(ServerTickEvent.Post event) {
        List<Pending> todo;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) {
                return;
            }
            todo = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        for (Pending p : todo) {
            int used = p.before() - p.proxy().getCount();
            if (used > 0 && !p.player().isRemoved()) {
                consume(p.player(), p.weapon(), p.found(), used);
            }
        }
    }

    public static void init(IEventBus modBus) {
        TABLE.id();
        NeoForge.EVENT_BUS.addListener(Ammo::onGetProjectile);
        NeoForge.EVENT_BUS.addListener(Ammo::settle);
    }

    private Ammo() {}
}
