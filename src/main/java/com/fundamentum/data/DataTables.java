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
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The registry of {@link DataTable}s: one reload listener loads them all on world load and /reload, and synced tables go
 * to clients on login and /reload. A mod registers its tables while it is constructed, e.g.
 * {@code DataTables.register(id("weapons"), "mymod/weapons", WeaponDef::fromJson).keyField("item").synced()}.
 */
public final class DataTables {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    private static final Map<Identifier, DataTable<?>> TABLES = new ConcurrentHashMap<>();

    public static <T> DataTable<T> register(Identifier id, String path, Function<JsonObject, T> decoder) {
        DataTable<T> table = new DataTable<>(id, path, decoder);
        if (TABLES.putIfAbsent(id, table) != null) {
            throw new IllegalStateException("data table " + id + " is already registered");
        }
        return table;
    }

    public static <T> DataTable<T> register(Identifier id, String path, Codec<T> codec) {
        return register(id, path, DataTable.codec(codec));
    }

    /** A table of plain JSON objects, e.g. named numbers read with {@link DataTable#number}. */
    public static DataTable<JsonObject> registerJson(Identifier id, String path) {
        return register(id, path, Function.identity());
    }

    public static DataTable<?> get(Identifier id) {
        return TABLES.get(id);
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(DataTables::payloads);
        NeoForge.EVENT_BUS.addListener(DataTables::reloadListeners);
        NeoForge.EVENT_BUS.addListener(DataTables::sync);
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(DataTablePayload.TYPE, DataTablePayload.STREAM_CODEC, DataTablePayload::handle);
    }

    private static void reloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath("fundamentum", "data_tables"), new Loader());
    }

    private static void sync(OnDatapackSyncEvent event) {
        for (DataTable<?> table : TABLES.values()) {
            if (table.isSynced()) {
                var payload = DataTablePayload.of(table);
                event.getRelevantPlayers().forEach(p -> PacketDistributor.sendToPlayer(p, payload));
            }
        }
    }

    /** Reads every table's files off-thread, then installs them all at once. */
    private static final class Loader extends SimplePreparableReloadListener<Map<DataTable<?>, Map<Identifier, JsonObject>>> {
        @Override
        protected Map<DataTable<?>, Map<Identifier, JsonObject>> prepare(ResourceManager manager, ProfilerFiller profiler) {
            Map<DataTable<?>, Map<Identifier, JsonObject>> out = new LinkedHashMap<>();
            for (DataTable<?> table : TABLES.values()) {
                FileToIdConverter lister = FileToIdConverter.json(table.path());
                List<Map.Entry<Identifier, JsonObject>> files = new ArrayList<>();
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
        protected void apply(Map<DataTable<?>, Map<Identifier, JsonObject>> prepared, ResourceManager manager, ProfilerFiller profiler) {
            prepared.forEach(DataTable::install);
            LOG.info("loaded {} data tables", prepared.size());
        }
    }

    private DataTables() {}
}
