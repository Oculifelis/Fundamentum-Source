package com.fundamentum.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.LoggerFactory;

/** One synced table, sent whole to a client as compressed JSON ({key: entry}). */
public record DataTablePayload(Identifier table, byte[] data) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DataTablePayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("fundamentum", "data_table"));
    public static final StreamCodec<FriendlyByteBuf, DataTablePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeIdentifier(p.table);
            buf.writeByteArray(p.data);
        },
        buf -> new DataTablePayload(buf.readIdentifier(), buf.readByteArray()));

    static DataTablePayload of(DataTable<?> table) {
        JsonObject all = new JsonObject();
        table.rawEntries().forEach((k, v) -> all.add(k.toString(), v));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Writer out = new OutputStreamWriter(new DeflaterOutputStream(bytes), StandardCharsets.UTF_8)) {
            out.write(all.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new DataTablePayload(table.id(), bytes.toByteArray());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DataTablePayload payload, IPayloadContext context) {
        DataTable<?> table = DataTables.get(payload.table);
        if (table == null) {
            return;
        }
        try (var in = new InputStreamReader(new InflaterInputStream(new ByteArrayInputStream(payload.data)), StandardCharsets.UTF_8)) {
            Map<Identifier, JsonObject> raw = new HashMap<>();
            JsonParser.parseReader(in).getAsJsonObject().entrySet()
                .forEach(e -> raw.put(Identifier.parse(e.getKey()), e.getValue().getAsJsonObject()));
            table.install(raw);
        } catch (IOException | RuntimeException e) {
            LoggerFactory.getLogger("fundamentum").error("data table {}: bad sync from server", payload.table, e);
        }
    }
}
