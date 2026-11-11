package com.fundamentum.port;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wires the version-neutral layer (FAttach, FStackData, FReg, FNet, FLife) to this loader. Called once from the mod. */
public final class Port {
    private Port() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static boolean started;

    public static void init(IEventBus modBus) {
        if (started) return;
        started = true;
        modBus.addListener(RegisterEvent.class, event -> {
            FAttach.register(event);
            FStackData.register(event);
            FReg.register(event);
        });
        modBus.addListener(RegisterPayloadHandlersEvent.class, FNet::register);
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class, e -> FLife.ABOUT_TO_START.forEach(a -> a.accept(e.getServer())));
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> FLife.STARTED.forEach(a -> a.accept(e.getServer())));
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, e -> FLife.STOPPING.forEach(a -> a.accept(e.getServer())));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> FLife.STOPPED.forEach(a -> a.accept(e.getServer())));
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> FLife.TICK.forEach(a -> a.accept(e.getServer())));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) FLife.LOGGED_IN.forEach(a -> a.accept(p));
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                FId from = FId.from(e.getFrom().location());
                FId to = FId.from(e.getTo().location());
                FLife.DIMENSION.forEach(a -> a.changed(p, from, to));
            }
        });
        NeoForge.EVENT_BUS.addListener(AddReloadListenerEvent.class, e -> {
            for (FLife.JsonData d : FLife.JSON_DATA) e.addListener(new JsonLoader(d));
        });
    }

    /** One {@link FLife#jsonData} folder: every pack's copy of each file, lowest priority first. */
    private static final class JsonLoader extends SimplePreparableReloadListener<Map<FId, List<JsonElement>>> {
        private final FLife.JsonData data;

        JsonLoader(FLife.JsonData data) {
            this.data = data;
        }

        @Override
        protected Map<FId, List<JsonElement>> prepare(ResourceManager manager, ProfilerFiller profiler) {
            FileToIdConverter lister = FileToIdConverter.json(data.folder());
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

        @Override
        protected void apply(Map<FId, List<JsonElement>> prepared, ResourceManager manager, ProfilerFiller profiler) {
            data.apply().accept(prepared);
        }
    }
}
