package com.fundamentum.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The registry of {@link DataTable}s: one reload listener loads them all on world load and /reload, and synced tables go
 * to clients on login and /reload. A mod registers its tables while it is constructed, e.g.
 * {@code DataTables.register(id("weapons"), "mymod/weapons", WeaponDef::fromJson).keyField("item").synced()}.
 */
public final class DataTables {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    private static final Map<ResourceLocation, DataTable<?>> TABLES = new ConcurrentHashMap<>();
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("fundamentum", "data_tables"), () -> VERSION, VERSION::equals, VERSION::equals);

    public static <T> DataTable<T> register(ResourceLocation id, String path, Function<JsonObject, T> decoder) {
        DataTable<T> table = new DataTable<>(id, path, decoder);
        if (TABLES.putIfAbsent(id, table) != null) {
            throw new IllegalStateException("data table " + id + " is already registered");
        }
        return table;
    }

    public static <T> DataTable<T> register(ResourceLocation id, String path, Codec<T> codec) {
        return register(id, path, DataTable.codec(codec));
    }

    /** A table of plain JSON objects, e.g. named numbers read with {@link DataTable#number}. */
    public static DataTable<JsonObject> registerJson(ResourceLocation id, String path) {
        return register(id, path, Function.identity());
    }

    public static DataTable<?> get(ResourceLocation id) {
        return TABLES.get(id);
    }

    public static void init() {
        CHANNEL.messageBuilder(DataTablePayload.class, 0, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(DataTablePayload::encode).decoder(DataTablePayload::decode).consumerMainThread(DataTablePayload::handle).add();
        MinecraftForge.EVENT_BUS.addListener(DataTables::reloadListeners);
        MinecraftForge.EVENT_BUS.addListener(DataTables::sync);
    }

    private static void reloadListeners(AddReloadListenerEvent event) {
        event.addListener(new Loader());
    }

    private static void sync(OnDatapackSyncEvent event) {
        List<ServerPlayer> players = event.getPlayer() != null ? List.of(event.getPlayer()) : event.getPlayerList().getPlayers();
        for (DataTable<?> table : TABLES.values()) {
            if (table.isSynced()) {
                var payload = DataTablePayload.of(table);
                players.forEach(p -> CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), payload));
            }
        }
    }

    /** Reads every table's files off-thread, then installs them all at once. */
    private static final class Loader extends SimplePreparableReloadListener<Map<DataTable<?>, Map<ResourceLocation, JsonObject>>> {
        @Override
        protected Map<DataTable<?>, Map<ResourceLocation, JsonObject>> prepare(ResourceManager manager, ProfilerFiller profiler) {
            Map<DataTable<?>, Map<ResourceLocation, JsonObject>> out = new LinkedHashMap<>();
            for (DataTable<?> table : TABLES.values()) {
                FileToIdConverter lister = FileToIdConverter.json(table.path());
                List<Map.Entry<ResourceLocation, JsonObject>> files = new ArrayList<>();
                for (var entry : new TreeMap<>(lister.listMatchingResources(manager)).entrySet()) {
                    try (Reader reader = entry.getValue().openAsReader()) {
                        files.add(Map.entry(lister.fileToId(entry.getKey()), JsonParser.parseReader(reader).getAsJsonObject()));
                    } catch (Exception e) {
                        LOG.error("data table {}: could not read {}", table.id(), entry.getKey(), e);
                    }
                }
                out.put(table, table.assemble(files));
            }
            return out;
        }

        @Override
        protected void apply(Map<DataTable<?>, Map<ResourceLocation, JsonObject>> prepared, ResourceManager manager, ProfilerFiller profiler) {
            prepared.forEach(DataTable::install);
            LOG.info("loaded {} data tables", prepared.size());
        }
    }

    private DataTables() {}
}
