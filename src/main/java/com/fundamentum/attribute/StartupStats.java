package com.fundamentum.attribute;

import com.fundamentum.port.FId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stats declared at startup, which become real attributes (they work with {@code /attribute}, every stat screen and
 * other mods). Minecraft locks its attribute list before world data packs load, so these come from files read while the
 * game starts, and adding one needs a restart:
 * <ul>
 * <li>{@code config/fundamentum/stats/<ns>/<path>.json}: one stat per file, its id from the path (or an {@code "id"}
 * field, which a file straight in {@code stats/} needs);</li>
 * <li>{@code META-INF/fundamentum/stats.json} in any mod jar: a list of stats, or {@code {"stats": [...]}}, each with an
 * {@code "id"}.</li>
 * </ul>
 * Each stat is the same JSON as a data stat declaration ({@code name}, {@code default}, {@code min}, {@code max},
 * {@code type}, {@code icon}, {@code hud}): {@code default} is the attribute's base value, {@code name} its translation
 * key (default {@code attribute.name.<ns>.<path>}). Config files win over jars. Each one is also declared as a stat
 * reading that attribute, unless a data pack declares the same id itself.
 */
public final class StartupStats {
    private StartupStats() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");

    /** Where mod jars declare startup stats. */
    public static final String JAR_PATH = "META-INF/fundamentum/stats.json";

    /** One startup stat. */
    public record Entry(FId id, double base, double min, double max, String name, JsonObject json) { }

    private static volatile Map<FId, Entry> entries = Map.of();

    /** The startup stats read while the game started, by id. */
    public static Map<FId, Entry> entries() {
        return entries;
    }

    /** Reads every startup stat and keeps them as {@link #entries()}; called once while the attributes register. */
    public static Map<FId, Entry> init(Path configDir) {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        if (Thread.currentThread().getContextClassLoader() != null) loaders.add(Thread.currentThread().getContextClassLoader());
        loaders.add(StartupStats.class.getClassLoader());
        Map<FId, Entry> out = new LinkedHashMap<>(fromJars(new ArrayList<>(loaders)));
        out.putAll(fromConfig(configDir.resolve("fundamentum").resolve("stats")));
        entries = Collections.unmodifiableMap(out);
        if (!out.isEmpty()) LOG.info("Read {} startup stat(s)", out.size());
        return entries;
    }

    /** The stats in a config folder ({@code config/fundamentum/stats}); a missing folder gives none. */
    public static Map<FId, Entry> fromConfig(Path root) {
        Map<FId, Entry> out = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) return out;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            LOG.error("Startup stats in {} could not be listed: {}", root, e.toString());
            return out;
        }
        for (Path f : files) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                FId id;
                if (o.has("id")) {
                    id = FId.of(o.get("id").getAsString());
                } else {
                    Path rel = root.relativize(f);
                    if (rel.getNameCount() < 2) throw new IllegalArgumentException("a file straight in stats/ needs an \"id\"");
                    String ns = rel.getName(0).toString();
                    String path = rel.subpath(1, rel.getNameCount()).toString().replace('\\', '/');
                    id = FId.of(ns, path.substring(0, path.length() - ".json".length()));
                }
                Entry e = entry(id, o);
                out.put(e.id(), e);
            } catch (RuntimeException | IOException e) {
                LOG.error("Startup stat file {} is broken: {}", f, e.toString());
            }
        }
        return out;
    }

    /** The stats every {@link #JAR_PATH} these class loaders can see declares. */
    public static Map<FId, Entry> fromJars(List<ClassLoader> loaders) {
        Map<FId, Entry> out = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ClassLoader loader : loaders) {
            Enumeration<URL> urls;
            try {
                urls = loader.getResources(JAR_PATH);
            } catch (IOException e) {
                continue;
            }
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if (!seen.add(url.toString())) continue;
                try (InputStream in = url.openStream()) {
                    out.putAll(fromJson(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)), url.toString()));
                } catch (IOException | RuntimeException e) {
                    LOG.error("Startup stats in {} are broken: {}", url, e.toString());
                }
            }
        }
        return out;
    }

    /** The stats in one list ({@code [...]} or {@code {"stats": [...]}}), each with an id; broken ones are skipped. */
    public static Map<FId, Entry> fromJson(JsonElement json, String where) {
        Map<FId, Entry> out = new LinkedHashMap<>();
        JsonElement list = json.isJsonObject() && json.getAsJsonObject().has("stats") ? json.getAsJsonObject().get("stats") : json;
        if (!list.isJsonArray()) throw new IllegalArgumentException("expected a list of stats");
        for (JsonElement el : list.getAsJsonArray()) {
            try {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("id")) throw new IllegalArgumentException("a stat has no \"id\"");
                Entry e = entry(FId.of(o.get("id").getAsString()), o);
                out.put(e.id(), e);
            } catch (RuntimeException e) {
                LOG.error("A startup stat in {} is broken: {}", where, e.toString());
            }
        }
        return out;
    }

    /** Checks one stat's fields. */
    public static Entry entry(FId id, JsonObject o) {
        if ("minecraft".equals(id.namespace())) throw new IllegalArgumentException(id + ": startup stats cannot use the minecraft namespace");
        if (!id.path().matches("[a-z0-9_./-]+") || !id.namespace().matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException(id + " is not a valid id");
        }
        double base = o.has("default") ? o.get("default").getAsDouble() : 0;
        double min = o.has("min") ? o.get("min").getAsDouble() : -1024;
        double max = o.has("max") ? o.get("max").getAsDouble() : 1024;
        if (min > max || base < min || base > max) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, "%s: default %s must lie between min %s and max %s", id, base, min, max));
        }
        String name = o.has("name") ? o.get("name").getAsString() : "attribute.name." + id.namespace() + "." + id.path().replace('/', '.');
        return new Entry(id, base, min, max, name, o.deepCopy());
    }

    /** The stat declaration for a startup stat: its own JSON reading the attribute, with the attribute's name. */
    public static JsonObject declaration(Entry e) {
        JsonObject o = e.json().deepCopy();
        o.remove("id");
        o.addProperty("name", e.name());
        o.addProperty("default", e.base());
        o.addProperty("min", e.min());
        o.addProperty("max", e.max());
        if (!o.has("scope")) o.addProperty("scope", "entity");
        JsonObject src = new JsonObject();
        src.addProperty("type", "attribute");
        src.addProperty("id", e.id().toString());
        o.add("source", src);
        return o;
    }
}
