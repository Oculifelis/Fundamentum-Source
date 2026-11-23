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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.RegisterEvent;
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
        FNet.createChannel();
        FNet.register(FId.of("fundamentum", "port_attach_sync"), FAttach.SyncMessage.class, FAttach.SYNC_CODEC, FNet.Side.TO_CLIENT, FAttach::receive);
        modBus.addListener((RegisterEvent event) -> {
            FStackData.freeze();
            FReg.register(event);
        });
        modBus.addListener((RegisterCapabilitiesEvent event) -> FAttach.registerCapability(event));
        modBus.addListener((FMLCommonSetupEvent event) -> FNet.register());

        IEventBus bus = MinecraftForge.EVENT_BUS;
        bus.addGenericListener(Entity.class, (AttachCapabilitiesEvent<Entity> e) -> FAttach.attach(e));
        bus.addGenericListener(LevelChunk.class, (AttachCapabilitiesEvent<LevelChunk> e) -> FAttach.attach(e));
        bus.addGenericListener(Level.class, (AttachCapabilitiesEvent<Level> e) -> FAttach.attach(e));
        bus.addGenericListener(BlockEntity.class, (AttachCapabilitiesEvent<BlockEntity> e) -> FAttach.attach(e));
        bus.addListener((PlayerEvent.Clone e) -> FAttach.clone(e));
        bus.addListener((ServerStartedEvent e) -> FLife.fireDataReloaded(e.getServer()));
        bus.addListener((OnDatapackSyncEvent e) -> {
            // no player means a /reload finished for everyone
            if (e.getPlayer() == null) FLife.fireDataReloaded(e.getPlayerList().getServer());
        });
        bus.addListener((AdvancementEvent.AdvancementEarnEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) FLife.fireAdvancementEarned(p, FId.from(e.getAdvancement().getId()));
        });
        bus.addListener((MobEffectEvent.Added e) -> FLife.fireEffectsChanged(e.getEntity()));
        bus.addListener((MobEffectEvent.Remove e) -> FLife.fireEffectsChanged(e.getEntity()));
        bus.addListener((MobEffectEvent.Expired e) -> FLife.fireEffectsChanged(e.getEntity()));
        bus.addListener((LivingEquipmentChangeEvent e) -> {
            if (e.getSlot().getType() == EquipmentSlot.Type.HAND && e.getEntity() instanceof ServerPlayer p) FLife.fireHandChanged(p);
        });
        bus.addListener((ChunkWatchEvent.Watch e) -> FLife.fireChunkWatched(e.getPlayer(), e.getLevel(), e.getPos().x, e.getPos().z));
        bus.addListener((PlayerEvent.StartTracking e) -> FAttach.startTracking(e));
        bus.addListener((PlayerEvent.PlayerRespawnEvent e) -> FAttach.playerReady(e.getEntity()));
        bus.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            FAttach.playerReady(e.getEntity());
            if (e.getEntity() instanceof ServerPlayer p) {
                FId from = FId.from(e.getFrom().location());
                FId to = FId.from(e.getTo().location());
                FLife.DIMENSION.forEach(a -> a.changed(p, from, to));
            }
        });
        bus.addListener((ServerAboutToStartEvent e) -> FLife.ABOUT_TO_START.forEach(a -> a.accept(e.getServer())));
        bus.addListener((ServerStartedEvent e) -> FLife.STARTED.forEach(a -> a.accept(e.getServer())));
        bus.addListener((ServerStoppingEvent e) -> FLife.STOPPING.forEach(a -> a.accept(e.getServer())));
        bus.addListener((ServerStoppedEvent e) -> FLife.STOPPED.forEach(a -> a.accept(e.getServer())));
        bus.addListener((TickEvent.ServerTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) FLife.TICK.forEach(a -> a.accept(e.getServer()));
        });
        bus.addListener((AddReloadListenerEvent e) -> {
            for (FLife.JsonData d : FLife.JSON_DATA) e.addListener(new JsonLoader(d));
        });
        bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            FAttach.playerReady(e.getEntity());
            if (e.getEntity() instanceof ServerPlayer p) FLife.LOGGED_IN.forEach(a -> a.accept(p));
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
