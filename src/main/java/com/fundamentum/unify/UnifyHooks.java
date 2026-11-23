package com.fundamentum.unify;

import com.fundamentum.port.FId;
import com.fundamentum.port.FReg;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.MapCodec;
import java.io.Reader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The version side of {@link Unify} (NeoForge 26.3): reading the datapack, the recipe hook (recipes load as a datapack
 * registry here, so {@code UnifyRegistryLoadMixin} catches the resource manager and {@code UnifyRecipeJsonMixin} hands
 * each recipe's JSON to {@link #recipeJson} as it is parsed), dropped items, creative tabs and the ore biome modifier. Hoppers and item handlers are swapped by {@code UnifyHopperMixin} and
 * {@code UnifyItemHandlerMixin}.
 */
public final class UnifyHooks {
    private UnifyHooks() { }

    private static final Logger LOG = LoggerFactory.getLogger("fundamentum/unify");
    /** Item tags folder in this version's datapacks. */
    public static final String TAG_FOLDER = "tags/item";
    /** The resource manager of the latest datapack registry load (registry elements are parsed on worker threads). */
    public static volatile ResourceManager LOADING;
    private static final java.util.concurrent.atomic.AtomicInteger REWRITTEN = new java.util.concurrent.atomic.AtomicInteger();

    public static final FReg.Ref<MapCodec<? extends BiomeModifier>> ORE_MODIFIER = FReg.<MapCodec<? extends BiomeModifier>>other(
        NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, FId.of("fundamentum", "unify_ores"), () -> UnifyOreModifier.CODEC);

    private static WeakReference<ResourceManager> lastManager = new WeakReference<>(null);
    private static UnifyMap lastMap;

    public static void init(IEventBus modBus) {
        Unify.init();
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, UnifyHooks::joined);
        NeoForge.EVENT_BUS.addListener(AddServerReloadListenersEvent.class,
            e -> e.addListener(FId.of("fundamentum", "unify").vanilla(), new Listener()));
        modBus.addListener(BuildCreativeModeTabContentsEvent.class, UnifyHooks::tabs);
    }

    /** The map for one datapack load, built once and shared by the recipe hook and the reload listener. */
    public static synchronized UnifyMap forManager(ResourceManager manager) {
        if (lastManager.get() == manager && lastMap != null) return lastMap;
        UnifyMap map;
        try {
            map = Unify.compute(folder -> read(manager, folder), TAG_FOLDER);
        } catch (RuntimeException e) {
            LOG.error("Unify: could not build the map", e);
            map = UnifyMap.EMPTY;
        }
        lastManager = new WeakReference<>(manager);
        lastMap = map;
        return map;
    }

    /** Every pack's copy of every JSON file under {@code data/<ns>/<folder>}, lowest priority first. */
    public static Map<FId, List<JsonElement>> read(ResourceManager manager, String folder) {
        FileToIdConverter lister = FileToIdConverter.json(folder);
        Map<FId, List<JsonElement>> out = new LinkedHashMap<>();
        for (Map.Entry<Identifier, List<Resource>> entry : new TreeMap<>(lister.listMatchingResourceStacks(manager)).entrySet()) {
            FId id = FId.from(lister.fileToId(entry.getKey()));
            List<JsonElement> copies = new ArrayList<>();
            for (Resource r : entry.getValue()) {
                try (Reader reader = r.openAsReader()) {
                    copies.add(JsonParser.parseReader(reader));
                } catch (Exception ex) {
                    LOG.error("Could not read {} from {}: {}", entry.getKey(), r.sourcePackId(), ex.toString());
                }
            }
            if (!copies.isEmpty()) out.put(id, copies);
        }
        return out;
    }

    /** The stack with another item, keeping its count and data. */
    public static ItemStack withItem(ItemStack stack, Item item) {
        return stack.transmuteCopy(item, stack.getCount());
    }

    /** Called with every registry element's JSON as it is parsed; rewrites recipes. Any thread. */
    public static JsonElement recipeJson(net.minecraft.resources.ResourceKey<?> key, JsonElement json) {
        if (!key.isFor(net.minecraft.core.registries.Registries.RECIPE)) return json;
        ResourceManager manager = LOADING;
        if (manager == null || !Unify.ENABLED.get() || !Unify.RECIPES.get()) return json;
        if (Unify.rewrite(forManager(manager), FId.from(key.identifier()), json)) REWRITTEN.incrementAndGet();
        return json;
    }

    private static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ItemEntity item)) return;
        if (!Unify.ENABLED.get() || !Unify.ITEM_ENTITIES.get()) return;
        ItemStack s = item.getItem();
        ItemStack c = Unify.convert(s);
        if (c != s) item.setItem(c);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (!Unify.ENABLED.get() || !Unify.HIDE_CREATIVE.get() || Unify.map().isEmpty()) return;
        event.getParentEntries().removeIf(Unify::replaced);
        event.getSearchEntries().removeIf(Unify::replaced);
    }

    /** Publishes the map once the whole datapack load has prepared. */
    private static final class Listener extends SimplePreparableReloadListener<UnifyMap> {
        @Override
        protected UnifyMap prepare(ResourceManager manager, ProfilerFiller profiler) {
            return forManager(manager);
        }

        @Override
        protected void apply(UnifyMap map, ResourceManager manager, ProfilerFiller profiler) {
            Unify.publish(map);
            int n = REWRITTEN.getAndSet(0);
            if (n > 0) LOG.info("Unify: {} recipes now give the kept item", n);
        }
    }

    // ---- for the self test

    /** The result item of a loaded shapeless recipe, or null. */
    public static FId shapelessResult(net.minecraft.server.MinecraftServer server, FId id) {
        return server.getRecipeManager().byKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, id.vanilla()))
            .map(h -> h.value() instanceof net.minecraft.world.item.crafting.ShapelessRecipe r && r.result() != null
                ? com.fundamentum.port.FVanilla.idOf(r.result().create().getItem()) : null)
            .orElse(null);
    }

    /** What a one-slot item handler holds after inserting this stack. */
    public static ItemStack handlerInsert(ItemStack stack) {
        net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler h = new net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler(1);
        try (net.neoforged.neoforge.transfer.transaction.Transaction tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
            h.insert(0, net.neoforged.neoforge.transfer.item.ItemResource.of(stack), stack.getCount(), tx);
            tx.commit();
        }
        return h.getResource(0).toStack((int) h.getAmountAsLong(0));
    }

    /** Whether the ore biome modifier loaded from data. */
    public static boolean oreModifierLoaded(net.minecraft.server.MinecraftServer server) {
        return server.registryAccess().lookupOrThrow(NeoForgeRegistries.Keys.BIOME_MODIFIERS).containsKey(FId.of("fundamentum", "unify_ores").vanilla());
    }

    /** Lets {@link BiomeModifier} code reach the map's ore answers by block. */
    static boolean oreRemoved(net.minecraft.world.level.block.Block block) {
        return Unify.map().oreRemoved(com.fundamentum.port.FVanilla.idOf(block.asItem()));
    }
}
