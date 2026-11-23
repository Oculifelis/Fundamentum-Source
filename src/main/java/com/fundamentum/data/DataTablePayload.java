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
import java.util.function.Supplier;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.LoggerFactory;

/** One synced table, sent whole to a client as compressed JSON ({key: entry}). */
public record DataTablePayload(ResourceLocation table, byte[] data) {
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

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(this.table);
        buf.writeByteArray(this.data);
    }

    public static DataTablePayload decode(FriendlyByteBuf buf) {
        return new DataTablePayload(buf.readResourceLocation(), buf.readByteArray());
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        DataTable<?> table = DataTables.get(this.table);
        if (table == null) {
            return;
        }
        try (var in = new InputStreamReader(new InflaterInputStream(new ByteArrayInputStream(this.data)), StandardCharsets.UTF_8)) {
            Map<ResourceLocation, JsonObject> raw = new HashMap<>();
            JsonParser.parseReader(in).getAsJsonObject().entrySet()
                .forEach(e -> raw.put(new ResourceLocation(e.getKey()), e.getValue().getAsJsonObject()));
            table.install(raw);
        } catch (IOException | RuntimeException e) {
            LoggerFactory.getLogger("fundamentum").error("data table {}: bad sync from server", this.table, e);
        }
    }
}
