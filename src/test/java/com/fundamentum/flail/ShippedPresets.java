package com.fundamentum.flail;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** The presets the mod ships, read from the source tree as the datapack loader would, keyed by id. */
final class ShippedPresets {
    static final Path DATA = Path.of("src/main/resources/data/fundamentum/fundamentum");
    private static Map<String, JsonObject> materials, kinds;

    private ShippedPresets() {}

    static Map<String, JsonObject> materials() {
        if (materials == null) {
            materials = read("flail_materials");
        }
        return materials;
    }

    static Map<String, JsonObject> kinds() {
        if (kinds == null) {
            kinds = read("flail_kinds");
        }
        return kinds;
    }

    static Map<String, JsonObject> read(String folder) {
        Map<String, JsonObject> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(DATA.resolve(folder))) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString();
                if (name.endsWith(".json")) {
                    out.put("fundamentum:" + name.substring(0, name.length() - 5), JsonParser.parseString(Files.readString(f)).getAsJsonObject());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
