package com.fundamentum.transfer;

import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.fundamentum.port.FId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One table of how much power each aspect is worth, shared by every essentia dynamo. Read from
 * {@code data/<ns>/fundamentum/aspect_power/*.json}, one file per aspect:
 * {@code {"aspect": "thaumaturge:ignis", "power": 900, "flux": 0.04}}. {@code power} is energy per point of essentia and
 * {@code flux} the taint a point leaves behind. Entries are keyed by the aspect, so a pack in any namespace can change one.
 * Each dynamo scales these numbers with its own data. Aspect ids are plain ids, so the table loads and does nothing
 * without Thaumaturge.
 */
public final class AspectPower {
    private AspectPower() { }

    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");

    /** One aspect's worth. */
    public record Entry(FId aspect, int power, float flux) { }

    private static volatile Map<FId, Entry> entries = Map.of();

    public static final DataTable<JsonObject> TABLE = DataTables.registerJson(FId.of("fundamentum", "aspect_power").vanilla(),
            "fundamentum/aspect_power")
        .keyField("aspect")
        .synced()
        .onLoad(t -> load(t.rawEntries().values()));

    /** Touches the table so it registers while Fundamentum loads. */
    public static void init() { }

    private static void load(Iterable<JsonObject> raw) {
        Map<FId, Entry> m = new LinkedHashMap<>();
        for (JsonObject o : raw) {
            try {
                FId aspect = FId.of(o.get("aspect").getAsString());
                JsonElement p = o.get("power");
                JsonElement f = o.get("flux");
                int power = p == null || p.isJsonNull() ? 0 : p.getAsInt();
                float flux = f == null || f.isJsonNull() ? 0f : f.getAsFloat();
                if (power < 0 || flux < 0) throw new IllegalArgumentException("negative power or flux");
                m.put(aspect, new Entry(aspect, power, flux));
            } catch (RuntimeException e) {
                LOG.warn("Fundamentum aspect_power: skipped {} ({})", o, e.getMessage());
            }
        }
        entries = Collections.unmodifiableMap(m);
    }

    /** Power per point of an aspect, 0 when it is not in the table. */
    public static int power(FId aspect) {
        Entry e = entries.get(aspect);
        return e == null ? 0 : e.power();
    }

    /** Flux per point of an aspect, 0 when it is not in the table. */
    public static float flux(FId aspect) {
        Entry e = entries.get(aspect);
        return e == null ? 0f : e.flux();
    }

    public static boolean has(FId aspect) {
        return entries.containsKey(aspect);
    }

    /** Every aspect in the table. */
    public static Map<FId, Entry> all() {
        return entries;
    }
}
