package com.fundamentum.unify;

import com.fundamentum.config.ConfigValue;
import com.fundamentum.config.FConfig;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStreamCodec;
import com.fundamentum.port.FVanilla;
import com.fundamentum.recipeview.RecipeViews;
import com.google.gson.JsonElement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Material unification: when several mods add the same ingot, dust, ore or plate, one of them is kept and the others are
 * pointed at it. The rules live in {@code data/<ns>/fundamentum/unify/priority.json} (which tags, the mod order, and
 * per-material overrides) and {@code fixes/<mod>.json} (where a mod's recipe types keep their output).
 * <p>
 * With the map built on every datapack load, recipe outputs are rewritten as recipes load, replaced items are hidden from
 * recipe viewers and creative tabs, duplicate ore worldgen is removed, and dropped items and items put into containers
 * are swapped for the kept item. Each of these has its own switch in {@code config/fundamentum/fundamentum/unify.json5}.
 * Pickup conversion (scanning player inventories) is off by default. Each swap at run time posts
 * {@link Events.MaterialUnified}. The same file on every version; the hooks are in {@link UnifyHooks}.
 */
public final class Unify {
    private Unify() { }

    private static final Logger LOG = LoggerFactory.getLogger("fundamentum/unify");
    public static final FId SYNC_ID = FId.of("fundamentum", "unify_sync");

    static final FConfig CFG = FConfig.create(FId.of("fundamentum", "unify"));
    /** The master switch. */
    public static final ConfigValue<Boolean> ENABLED = CFG.bool("enabled", true);
    /** Rewrite recipe outputs on load (takes effect on the next /reload). */
    public static final ConfigValue<Boolean> RECIPES = CFG.bool("recipes", true);
    /** Remove duplicate ores from worldgen (read when a world starts). */
    public static final ConfigValue<Boolean> ORES = CFG.bool("ores", true).side(FConfig.Side.STARTUP);
    /** Swap dropped item entities as they appear. */
    public static final ConfigValue<Boolean> ITEM_ENTITIES = CFG.bool("item_entities", true);
    /** Swap items as hoppers and item handlers insert them. */
    public static final ConfigValue<Boolean> CONTAINERS = CFG.bool("containers", true);
    /** Swap items in player inventories (checked when the inventory changes). */
    public static final ConfigValue<Boolean> PICKUP = CFG.bool("pickup", false);
    /** Hide replaced items from JEI, EMI and REI. */
    public static final ConfigValue<Boolean> HIDE_VIEWERS = CFG.bool("hide_viewers", true).side(FConfig.Side.CLIENT);
    /** Hide replaced items from creative tabs (applies the next time the tabs are built). */
    public static final ConfigValue<Boolean> HIDE_CREATIVE = CFG.bool("hide_creative", true).side(FConfig.Side.CLIENT);

    /** The replacements sent to players: replaced id to "kept id|tag". */
    public record Sync(Map<String, String> map) {
        public static final FStreamCodec<Sync> CODEC = FStreamCodec.mapOf(FStreamCodec.STRING, FStreamCodec.STRING).map(Sync::new, Sync::map);
    }

    private static volatile UnifyMap current = UnifyMap.EMPTY;
    private static volatile boolean dirty;
    private static int ticks;
    /** Players whose inventory changed this tick, converted once at the end of it. */
    private static final java.util.Set<java.util.UUID> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static boolean started;

    /** Called once while Fundamentum is constructed. */
    public static synchronized void init() {
        if (started) return;
        started = true;
        FNet.register(SYNC_ID, Sync.class, Sync.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) -> {
            current = UnifyMap.fromStrings(msg.map());
            RecipeViews.refresh();
        });
        RecipeViews.hide(stack -> HIDE_VIEWERS.get() && ENABLED.get() && replaced(stack));
        FLife.playerLoggedIn(p -> FNet.toPlayer(p, new Sync(current.toStrings())));
        FLife.serverTick(Unify::tick);
        FLife.inventoryChanged(p -> {
            if (ENABLED.get() && PICKUP.get() && !current.isEmpty()) PENDING.add(p.getUUID());
        });
        FLife.serverStopped(s -> {
            ticks = 0;
            PENDING.clear();
        });
    }

    // ---- building

    /**
     * Builds the map from a datapack reader: {@code read} returns every copy of every JSON file under a data folder, by
     * file id. {@code tagFolder} is the item tag folder of this version.
     */
    public static UnifyMap compute(Function<String, Map<FId, List<JsonElement>>> read, String tagFolder) {
        if (!ENABLED.get()) return UnifyMap.EMPTY;
        long start = System.nanoTime();
        Map<FId, List<JsonElement>> priority = new LinkedHashMap<>();
        read.apply("fundamentum/unify").forEach((id, copies) -> {
            if (id.path().equals("priority")) priority.put(id, copies);
        });
        UnifyRules rules = UnifyRules.read(priority);
        TagReader tags = new TagReader(read.apply(tagFolder), id -> FVanilla.item(id) != null);
        UnifyMap map = UnifyMap.build(rules, tags, read.apply("fundamentum/unify/fixes"), ORES.get());
        LOG.info("Unify: {} tags unified, {} items replaced, in {} ms", map.groups().size(), map.replacements().size(),
            (System.nanoTime() - start) / 1_000_000);
        return map;
    }

    /** Makes a freshly built map the one in use and sends it to players. Any thread. */
    public static void publish(UnifyMap map) {
        current = map == null ? UnifyMap.EMPTY : map;
        dirty = true;
    }

    /** The map in use (on a client: the one the server sent). */
    public static UnifyMap map() {
        return current;
    }

    // ---- asking

    /** The kept item's id for this item id, or the id itself when it is not replaced. */
    public static FId preferred(FId item) {
        if (!ENABLED.get()) return item;
        FId t = current.target(item);
        return t == null ? item : t;
    }

    /** The kept item for this item, or the item itself. */
    public static Item preferred(Item item) {
        if (!ENABLED.get()) return item;
        FId t = current.target(FVanilla.idOf(item));
        Item i = t == null ? null : FVanilla.item(t);
        return i == null ? item : i;
    }

    /**
     * The stack as the kept item (count and data copied), or the same stack when its item is not replaced. Does not post
     * an event; {@link #convert} does.
     */
    public static ItemStack preferred(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !ENABLED.get()) return stack;
        Item to = preferred(stack.getItem());
        return to == stack.getItem() ? stack : UnifyHooks.withItem(stack, to);
    }

    /** Whether this stack's item is replaced by another. */
    public static boolean replaced(ItemStack stack) {
        return stack != null && !stack.isEmpty() && current.replaced(FVanilla.idOf(stack.getItem()));
    }

    /** Whether this item is replaced by another. */
    public static boolean replaced(Item item) {
        return current.replaced(FVanilla.idOf(item));
    }

    /** {@link #preferred(ItemStack)} that also posts {@link Events.MaterialUnified} when it swaps. */
    public static ItemStack convert(ItemStack stack) {
        ItemStack out = preferred(stack);
        if (out != stack) {
            FId from = FVanilla.idOf(stack.getItem());
            FId tag = current.tagOf(from);
            try {
                FEvents.post(new Events.MaterialUnified(tag == null ? from : tag, from, FVanilla.idOf(out.getItem())));
            } catch (RuntimeException e) {
                LOG.warn("Unify: a material_unified listener failed: {}", e.toString());
            }
        }
        return out;
    }

    /** Swaps every replaced stack in a container. Returns how many slots changed. */
    public static int convertAll(Container container) {
        int n = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            ItemStack c = convert(s);
            if (c != s) {
                container.setItem(i, c);
                n++;
            }
        }
        return n;
    }

    /** The pickup conversion for one player: swaps every replaced stack in the inventory. */
    public static int convertInventory(ServerPlayer player) {
        return convertAll(player.getInventory());
    }

    /** Recipe hooks call this to rewrite one recipe's JSON in place. */
    public static boolean rewrite(UnifyMap map, FId id, JsonElement json) {
        if (!ENABLED.get() || !RECIPES.get() || map == null) return false;
        try {
            return map.rewriteRecipe(id, json);
        } catch (RuntimeException e) {
            LOG.warn("Unify: could not rewrite recipe {}: {}", id, e.toString());
            return false;
        }
    }

    // ---- upkeep

    private static void tick(MinecraftServer server) {
        if (dirty) {
            dirty = false;
            Sync msg = new Sync(current.toStrings());
            for (ServerPlayer p : List.copyOf(server.getPlayerList().getPlayers())) FNet.toPlayer(p, msg);
        }
        ticks++;
        if (PENDING.isEmpty()) return;
        List<java.util.UUID> work = List.copyOf(PENDING);
        PENDING.removeAll(work);
        if (!ENABLED.get() || !PICKUP.get() || current.isEmpty()) return;
        for (java.util.UUID id : work) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) convertInventory(p);
        }
    }
}
