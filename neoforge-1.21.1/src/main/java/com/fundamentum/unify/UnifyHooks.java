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
import net.minecraft.resources.ResourceLocation;
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
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The version side of {@link Unify} (NeoForge 1.21.1): reading the datapack, the recipe hook ({@code UnifyRecipeManagerMixin}
 * hands the recipe JSONs to {@link #recipes} before the recipe manager decodes them), dropped items, creative tabs and the
 * ore biome modifier. Hoppers and item handlers are swapped by {@code UnifyHopperMixin} and
 * {@code UnifyItemHandlerMixin}.
 */
public final class UnifyHooks {
    private UnifyHooks() { }

    private static final Logger LOG = LoggerFactory.getLogger("fundamentum/unify");
    /** Item tags folder in this version's datapacks. */
    public static final String TAG_FOLDER = "tags/item";

    public static final FReg.Ref<MapCodec<? extends BiomeModifier>> ORE_MODIFIER = FReg.<MapCodec<? extends BiomeModifier>>other(
        NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, FId.of("fundamentum", "unify_ores"), () -> UnifyOreModifier.CODEC);

    private static WeakReference<ResourceManager> lastManager = new WeakReference<>(null);
    private static UnifyMap lastMap;

    public static void init(IEventBus modBus) {
        Unify.init();
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, UnifyHooks::joined);
        NeoForge.EVENT_BUS.addListener(AddReloadListenerEvent.class, e -> e.addListener(new Listener()));
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
        for (Map.Entry<ResourceLocation, List<Resource>> entry : new TreeMap<>(lister.listMatchingResourceStacks(manager)).entrySet()) {
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

    /** Rewrites the recipe JSONs of one load in place, before they are decoded. */
    public static void recipes(Map<ResourceLocation, JsonElement> recipes, ResourceManager manager) {
        if (manager == null || !Unify.ENABLED.get() || !Unify.RECIPES.get()) return;
        UnifyMap map = forManager(manager);
        int n = 0;
        for (Map.Entry<ResourceLocation, JsonElement> e : recipes.entrySet()) {
            if (Unify.rewrite(map, FId.from(e.getKey()), e.getValue())) n++;
        }
        if (n > 0) LOG.info("Unify: {} recipes now give the kept item", n);
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
        }
    }

    // ---- for the self test

    /** The result item of a loaded shapeless recipe, or null. */
    public static FId shapelessResult(net.minecraft.server.MinecraftServer server, FId id) {
        return server.getRecipeManager().byKey(id.vanilla())
            .map(h -> h.value() instanceof net.minecraft.world.item.crafting.ShapelessRecipe r
                ? com.fundamentum.port.FVanilla.idOf(r.getResultItem(server.registryAccess()).getItem()) : null)
            .orElse(null);
    }

    /** What a one-slot item handler holds after inserting this stack. */
    public static ItemStack handlerInsert(ItemStack stack) {
        net.neoforged.neoforge.items.ItemStackHandler h = new net.neoforged.neoforge.items.ItemStackHandler(1);
        h.insertItem(0, stack, false);
        return h.getStackInSlot(0);
    }

    /** Whether the ore biome modifier loaded from data. */
    public static boolean oreModifierLoaded(net.minecraft.server.MinecraftServer server) {
        return server.registryAccess().registryOrThrow(NeoForgeRegistries.Keys.BIOME_MODIFIERS).containsKey(FId.of("fundamentum", "unify_ores").vanilla());
    }

    /** Lets {@link BiomeModifier} code reach the map's ore answers by block. */
    static boolean oreRemoved(net.minecraft.world.level.block.Block block) {
        return Unify.map().oreRemoved(com.fundamentum.port.FVanilla.idOf(block.asItem()));
    }
}
