package com.fundamentum.sky;

import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * The fractal skies in data ({@link FractalSky}), synced to clients, which draw them ({@link FractalSkiesClient}).
 * A sky that fails to read is logged and left out.
 */
public final class FractalSkies {
    private FractalSkies() { }

    private static volatile List<FractalSky> skies = List.of();

    public static final DataTable<JsonObject> TABLE = DataTables.registerJson(new ResourceLocation("fundamentum", "fractal_skies"),
            "fundamentum/fractal_skies")
        .synced()
        .onLoad(t -> {
            List<FractalSky> out = new ArrayList<>();
            t.rawEntries().forEach((id, json) -> {
                try {
                    out.add(FractalSky.parse(id, json));
                } catch (RuntimeException e) {
                    com.mojang.logging.LogUtils.getLogger().error("Fundamentum: fractal sky {} is broken: {}", id, e.toString());
                }
            });
            skies = List.copyOf(out);
        });

    /** Registers the table (Fundamentum's constructor). */
    public static void init() {
        TABLE.id();
    }

    public static List<FractalSky> all() {
        return skies;
    }
}
