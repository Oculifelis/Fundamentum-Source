package com.fundamentum.config;

import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStreamCodec;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configs for every mod, with one file format, one screen and server-to-client sync.
 *
 * <p>A config is declared in code ({@link #create}) or in data ({@code data/<ns>/fundamentum/config/<path>.json}).
 * Its values live in {@code config/fundamentum/<ns>/<path>.json5}; a per-world copy in
 * {@code <world>/serverconfig/fundamentum/<ns>/<path>.json5} wins over it, and datapacks may change the defaults
 * underneath both. {@link #of} gives typed handles, whoever declared the config.
 */
public final class FConfig {
    /** Where an entry's value lives. */
    public enum Side {
        /** Set on the server and sent to every player (default). */
        SERVER,
        /** Each player's own, never sent. */
        CLIENT,
        /** Read once; a change shows "needs restart" until the game restarts. */
        STARTUP
    }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    static final String DATA_FOLDER = "fundamentum/config";
    private static final Map<FId, FConfig> ALL = new ConcurrentSkipListMap<>();
    private static final Map<String, Map<String, String>> LANG = new ConcurrentHashMap<>();
    private static final int WATCH_TICKS = 40;

    static volatile boolean serverRunning;
    private static volatile boolean live;
    private static MinecraftServer server;
    private static Path worldRoot;
    private static int watchTimer;
    private static boolean initialised;

    private final FId id;
    final Map<String, ConfigEntry> entries = new LinkedHashMap<>();
    boolean code;
    boolean data;
    boolean remote;
    private boolean loaded;
    private JsonObject fileRaw = new JsonObject();
    private JsonObject worldRaw = new JsonObject();
    private String fileStamp;
    private String worldStamp;

    private FConfig(FId id) {
        this.id = id;
    }

    // ---- lookup and declaration ----

    /** Declares a config in code. Call while the mod loads; entries are added with the typed builders. */
    public static synchronized FConfig create(FId id) {
        FConfig c = ALL.computeIfAbsent(id, FConfig::new);
        if (c.code) throw new IllegalStateException("Config " + id + " declared twice");
        c.code = true;
        return c;
    }

    /** The config with this id, declared in code or data. Handles taken before the data loads resolve later. */
    public static FConfig of(FId id) {
        return ALL.computeIfAbsent(id, FConfig::new);
    }

    public static FConfig of(String id) {
        return of(FId.of(id));
    }

    /** Every config that has entries, sorted by id. */
    public static List<FConfig> all() {
        List<FConfig> out = new ArrayList<>();
        for (FConfig c : ALL.values()) {
            if (!c.entries.isEmpty()) out.add(c);
        }
        return out;
    }

    /** True while a server runs in this game (dedicated or the singleplayer one). */
    public static boolean serverRunning() {
        return serverRunning;
    }

    public FId id() {
        return this.id;
    }

    /** True when the config was declared by a datapack rather than in code. */
    public boolean dataDeclared() {
        return this.data || this.remote;
    }

    public ConfigEntry entry(String key) {
        return this.entries.get(key);
    }

    public Collection<ConfigEntry> entries() {
        return this.entries.values();
    }

    public Path globalFile() {
        return FVanilla.configDir().resolve("fundamentum").resolve(this.id.namespace()).resolve(this.id.path() + ".json5");
    }

    /** The per-world file, or null while no server runs here. */
    public Path worldFile() {
        Path root = worldRoot;
        return root == null ? null : root.resolve("serverconfig").resolve("fundamentum").resolve(this.id.namespace()).resolve(this.id.path() + ".json5");
    }

    // ---- typed builders (code declarations) ----

    public ConfigValue<Boolean> bool(String key, boolean def) {
        add(key, ConfigEntry.Type.BOOL, def);
        return bool(key);
    }

    public ConfigValue<Integer> integer(String key, int def, int min, int max) {
        ConfigEntry e = add(key, ConfigEntry.Type.INT, def);
        e.min = (double) min;
        e.max = (double) max;
        checkDefault(e);
        return integer(key);
    }

    public ConfigValue<Double> decimal(String key, double def, double min, double max) {
        ConfigEntry e = add(key, ConfigEntry.Type.DOUBLE, def);
        e.min = min;
        e.max = max;
        checkDefault(e);
        return decimal(key);
    }

    public ConfigValue<String> string(String key, String def) {
        add(key, ConfigEntry.Type.STRING, def);
        return string(key);
    }

    public <E extends Enum<E>> ConfigValue<E> enumValue(String key, Class<E> type, E def) {
        ConfigEntry e = add(key, ConfigEntry.Type.ENUM, def.name());
        List<String> names = new ArrayList<>();
        for (E v : type.getEnumConstants()) names.add(v.name());
        e.values = List.copyOf(names);
        return enumValue(key, type);
    }

    public ConfigValue<FId> id(String key, FId def) {
        add(key, ConfigEntry.Type.ID, def);
        return id(key);
    }

    public ConfigValue<List<FId>> idList(String key, List<FId> def) {
        add(key, ConfigEntry.Type.ID_LIST, List.copyOf(def));
        return idList(key);
    }

    /** A colour as 0xAARRGGBB (written #RRGGBB when the alpha is 0). */
    public ConfigValue<Integer> colour(String key, int def) {
        add(key, ConfigEntry.Type.COLOUR, def);
        return colour(key);
    }

    // ---- typed lookups (entries declared anywhere) ----

    public ConfigValue<Boolean> bool(String key) {
        return new ConfigValue<>(this, key, o -> (Boolean) o, v -> v);
    }

    public ConfigValue<Integer> integer(String key) {
        return new ConfigValue<>(this, key, o -> ((Number) o).intValue(), v -> v);
    }

    public ConfigValue<Double> decimal(String key) {
        return new ConfigValue<>(this, key, o -> ((Number) o).doubleValue(), v -> v);
    }

    /** Text, or an enum entry's name. */
    public ConfigValue<String> string(String key) {
        return new ConfigValue<>(this, key, o -> (String) o, v -> v);
    }

    public <E extends Enum<E>> ConfigValue<E> enumValue(String key, Class<E> type) {
        return new ConfigValue<>(this, key, o -> {
            for (E v : type.getEnumConstants()) {
                if (v.name().equalsIgnoreCase((String) o)) return v;
            }
            throw new IllegalStateException("Config " + this.id + " " + key + ": " + o + " is not a " + type.getSimpleName());
        }, Enum::name);
    }

    public ConfigValue<FId> id(String key) {
        return new ConfigValue<>(this, key, o -> (FId) o, v -> v);
    }

    @SuppressWarnings("unchecked")
    public ConfigValue<List<FId>> idList(String key) {
        return new ConfigValue<>(this, key, o -> (List<FId>) o, v -> List.copyOf(v));
    }

    public ConfigValue<Integer> colour(String key) {
        return new ConfigValue<>(this, key, o -> (Integer) o, v -> v);
    }

    private synchronized ConfigEntry add(String key, ConfigEntry.Type type, Object def) {
        if (!this.code) throw new IllegalStateException("Config " + this.id + " was not declared with FConfig.create");
        if (this.entries.containsKey(key)) throw new IllegalStateException("Config " + this.id + " declares " + key + " twice");
        ConfigEntry e = new ConfigEntry(this, key, type, def);
        this.entries.put(key, e);
        return e;
    }

    private static void checkDefault(ConfigEntry e) {
        e.fromJson(e.toJson(e.codeDefault));
    }

    // ---- lifecycle ----

    /** Wires configs to the game. Called once while Fundamentum loads. */
    public static void init() {
        if (initialised) return;
        initialised = true;
        FNet.register(FId.of("fundamentum", "config_sync"), Sync.class, Sync.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) -> applySync(msg));
        FNet.register(FId.of("fundamentum", "config_edit"), Edit.class, Edit.CODEC, FNet.Side.TO_SERVER, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer p) handleEdit(p, msg);
        });
        FLife.jsonData(FId.of("fundamentum", "config"), DATA_FOLDER, map -> {
            Runnable load = () -> {
                applyData(map);
                for (FConfig c : ALL.values()) c.reloadFiles();
            };
            // the first load, while the world is still starting, is not a change anyone needs to hear about
            if (live) change(load);
            else load.run();
        });
        FLife.serverAboutToStart(s -> {
            server = s;
            serverRunning = true;
            worldRoot = s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            for (FConfig c : ALL.values()) c.reloadFiles();
        });
        FLife.serverStarted(s -> live = true);
        FLife.serverStopped(s -> {
            live = false;
            serverRunning = false;
            server = null;
            worldRoot = null;
            applyData(Map.of());
            for (FConfig c : ALL.values()) c.reloadFiles();
        });
        FLife.serverTick(s -> {
            if (++watchTimer < WATCH_TICKS) return;
            watchTimer = 0;
            checkFiles();
        });
        FLife.playerLoggedIn(p -> {
            for (FConfig c : all()) {
                if (c.hasServerEntries()) FNet.toPlayer(p, c.sync());
            }
        });
    }

    /** Re-reads every config file whose time stamp or size changed. Runs every 2 seconds on the server thread. */
    public static void checkFiles() {
        List<FConfig> changed = new ArrayList<>();
        for (FConfig c : all()) {
            if (!Objects.equals(stamp(c.globalFile()), c.fileStamp) || !Objects.equals(stamp(c.worldFile()), c.worldStamp)) changed.add(c);
        }
        if (changed.isEmpty()) return;
        change(() -> changed.forEach(FConfig::reloadFiles));
    }

    /** The player left the server: forget its values and the configs only it declared. */
    public static void clientDisconnected() {
        if (serverRunning) return;
        change(() -> {
            for (FConfig c : ALL.values()) {
                for (ConfigEntry e : c.entries.values()) e.synced = null;
                if (c.remote && !c.code) {
                    c.remote = false;
                    c.entries.clear();
                }
            }
        });
    }

    void ensureLoaded() {
        if (!this.loaded) reloadFiles();
    }

    synchronized void reloadFiles() {
        this.loaded = true;
        Path global = globalFile();
        this.fileRaw = readFile(global, this.fileRaw);
        this.fileStamp = stamp(global);
        Path world = worldFile();
        if (world == null) {
            this.worldRaw = new JsonObject();
            this.worldStamp = null;
        } else {
            this.worldRaw = readFile(world, this.worldRaw);
            this.worldStamp = stamp(world);
        }
        applyLayers();
        if (!this.entries.isEmpty()) ensureFile(global);
    }

    private void applyLayers() {
        for (ConfigEntry e : this.entries.values()) {
            e.fileValue = layer(e, this.fileRaw, "config");
            e.worldValue = e.side == Side.CLIENT ? null : layer(e, this.worldRaw, "world config");
        }
    }

    private Object layer(ConfigEntry e, JsonObject raw, String what) {
        JsonElement j = raw.get(e.key);
        if (j == null || j.isJsonNull()) return null;
        try {
            return e.fromJson(j);
        } catch (IllegalArgumentException ex) {
            LOG.warn("Config {} {}: {} in the {} file is not used ({})", this.id, e.key, Json5.write(j), what, ex.getMessage());
            return null;
        }
    }

    private JsonObject readFile(Path path, JsonObject previous) {
        if (path == null || !Files.isRegularFile(path)) return new JsonObject();
        try {
            JsonElement j = Json5.parse(Files.readString(path, StandardCharsets.UTF_8));
            if (j.isJsonObject()) return j.getAsJsonObject();
            LOG.error("Config file {} is not an object; keeping the values read before", path);
        } catch (IOException | IllegalArgumentException ex) {
            LOG.error("Config file {} could not be read ({}); keeping the values read before", path, ex.getMessage());
        }
        return previous;
    }

    private static String stamp(Path path) {
        if (path == null) return null;
        try {
            if (!Files.isRegularFile(path)) return null;
            return Files.getLastModifiedTime(path).toMillis() + "/" + Files.size(path);
        } catch (IOException e) {
            return null;
        }
    }

    // ---- writing ----

    /** Writes a value (null = remove it so the default applies) into the files and applies it. */
    void write(String key, Object value) {
        ConfigEntry e = this.entries.get(key);
        if (e == null) throw new IllegalArgumentException("Config " + this.id + " has no entry " + key);
        if (value != null) e.fromJson(e.toJson(value));
        change(() -> writeNow(e, value));
    }

    private synchronized void writeNow(ConfigEntry e, Object value) {
        Path world = e.side == Side.CLIENT ? null : worldFile();
        boolean worldExists = world != null && Files.isRegularFile(world);
        if (value == null) {
            this.fileRaw.remove(e.key);
            this.worldRaw.remove(e.key);
            writeFile(globalFile(), this.fileRaw);
            if (worldExists) writeFile(world, this.worldRaw);
        } else if (worldExists) {
            this.worldRaw.add(e.key, e.toJson(value));
            writeFile(world, this.worldRaw);
        } else {
            this.fileRaw.add(e.key, e.toJson(value));
            writeFile(globalFile(), this.fileRaw);
        }
        this.fileStamp = stamp(globalFile());
        this.worldStamp = stamp(worldFile());
        applyLayers();
    }

    private void ensureFile(Path path) {
        String text = null;
        try {
            if (Files.isRegularFile(path)) text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return;
        }
        if (text != null) {
            boolean complete = true;
            for (String key : this.entries.keySet()) {
                if (!text.contains("\"" + key + "\"")) complete = false;
            }
            if (complete) return;
        }
        writeFile(path, this.fileRaw);
        this.fileStamp = stamp(path);
    }

    private void writeFile(Path path, JsonObject raw) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, render(raw), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOG.error("Could not write config file {}: {}", path, ex.toString());
        }
    }

    /** The file text: every entry with its comment; entries not set are written commented out with their default. */
    String render(JsonObject raw) {
        Map<String, String> lang = lang(this.id.namespace());
        StringBuilder b = new StringBuilder();
        b.append("// ").append(this.id).append('\n');
        b.append("// Lines starting with // are defaults. Remove the // and change the value to set one.\n");
        b.append("{\n");
        String category = null;
        for (ConfigEntry e : this.entries.values()) {
            if (!e.category.equals(category)) {
                category = e.category;
                b.append("  // ---- ").append(category).append(" ----\n");
            }
            String comment = lang.get(e.comment());
            if (comment != null) {
                for (String line : comment.split("\n")) b.append("  // ").append(line).append('\n');
            }
            b.append("  // ").append(e.describe()).append('\n');
            JsonElement set = raw.get(e.key);
            if (set != null) {
                b.append("  ").append(Json5.write(new com.google.gson.JsonPrimitive(e.key))).append(": ").append(Json5.write(set)).append(",\n");
            } else {
                b.append("  // ").append(Json5.write(new com.google.gson.JsonPrimitive(e.key))).append(": ").append(Json5.write(e.toJson(e.defaultValue()))).append(",\n");
            }
            b.append('\n');
        }
        for (Map.Entry<String, JsonElement> x : raw.entrySet()) {
            if (this.entries.containsKey(x.getKey())) continue;
            b.append("  // not declared by any loaded mod or datapack\n");
            b.append("  ").append(Json5.write(new com.google.gson.JsonPrimitive(x.getKey()))).append(": ").append(Json5.write(x.getValue())).append(",\n");
        }
        b.append("}\n");
        return b.toString();
    }

    /** The en_us lang file of a namespace, from every jar and folder on the class path. */
    static Map<String, String> lang(String namespace) {
        return LANG.computeIfAbsent(namespace, ns -> {
            Map<String, String> out = new HashMap<>();
            String path = "assets/" + ns + "/lang/en_us.json";
            Set<String> seen = new LinkedHashSet<>();
            for (ClassLoader loader : new ClassLoader[] {FConfig.class.getClassLoader(), Thread.currentThread().getContextClassLoader()}) {
                if (loader == null) continue;
                try {
                    Enumeration<URL> urls = loader.getResources(path);
                    while (urls.hasMoreElements()) {
                        URL url = urls.nextElement();
                        if (!seen.add(url.toString())) continue;
                        try (InputStream in = url.openStream(); Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                            JsonElement j = JsonParser.parseReader(r);
                            if (j.isJsonObject()) {
                                for (Map.Entry<String, JsonElement> x : j.getAsJsonObject().entrySet()) {
                                    if (x.getValue().isJsonPrimitive()) out.put(x.getKey(), x.getValue().getAsString());
                                }
                            }
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // no lang file: comments fall back to the type line only
                }
            }
            return out;
        });
    }

    // ---- datapacks ----

    /**
     * Applies the datapack files of {@code data/<ns>/fundamentum/config/}, every pack's copy lowest priority first.
     * The lowest copy of a config not declared in code declares it ({@code "entries"}); every other copy only changes
     * defaults, through {@code "defaults": {key: value}} or an entry's {@code "default"}.
     */
    static synchronized void applyData(Map<FId, List<JsonElement>> files) {
        for (FConfig c : ALL.values()) {
            for (ConfigEntry e : c.entries.values()) e.dataDefault = null;
            if (c.data && !c.code) c.entries.clear();
            c.data = false;
        }
        for (Map.Entry<FId, List<JsonElement>> f : files.entrySet()) {
            FConfig c = of(f.getKey());
            List<JsonElement> copies = f.getValue();
            for (int i = 0; i < copies.size(); i++) {
                try {
                    if (!copies.get(i).isJsonObject()) throw new IllegalArgumentException("not a JSON object");
                    JsonObject o = copies.get(i).getAsJsonObject();
                    boolean declares = i == 0 && !c.code;
                    if (declares) {
                        c.remote = false;
                        c.entries.clear();
                        c.data = true;
                        if (o.has("entries")) {
                            for (Map.Entry<String, JsonElement> x : o.getAsJsonObject("entries").entrySet()) {
                                c.entries.put(x.getKey(), ConfigEntry.declared(c, x.getKey(), x.getValue().getAsJsonObject()));
                            }
                        }
                    } else if (o.has("entries")) {
                        for (Map.Entry<String, JsonElement> x : o.getAsJsonObject("entries").entrySet()) {
                            JsonObject eo = x.getValue().getAsJsonObject();
                            if (eo.has("default")) c.dataDefault(x.getKey(), eo.get("default"));
                        }
                    }
                    if (o.has("defaults")) {
                        for (Map.Entry<String, JsonElement> x : o.getAsJsonObject("defaults").entrySet()) c.dataDefault(x.getKey(), x.getValue());
                    }
                } catch (RuntimeException ex) {
                    LOG.error("Config datapack file {} (copy {}) is not valid: {}", f.getKey(), i + 1, ex.getMessage());
                }
            }
        }
    }

    private void dataDefault(String key, JsonElement value) {
        ConfigEntry e = this.entries.get(key);
        if (e == null) {
            LOG.warn("Config datapack file {} sets a default for unknown entry {}", this.id, key);
            return;
        }
        try {
            e.dataDefault = e.fromJson(value);
        } catch (IllegalArgumentException ex) {
            LOG.warn("Config datapack file {}: default {} for {} is not used ({})", this.id, Json5.write(value), key, ex.getMessage());
        }
    }

    // ---- change tracking ----

    /**
     * Runs a change and then fires {@link ConfigEvent.Changed} for every config whose values moved, and sends the
     * server entries again to every player when they changed. Before the server has started nothing fires.
     */
    static void change(Runnable action) {
        Map<FId, Map<String, Object>> before = snapshot();
        Map<FId, String> declBefore = declarations();
        action.run();
        Map<FId, Map<String, Object>> after = snapshot();
        Map<FId, String> declAfter = declarations();
        boolean fire = live || !serverRunning;
        for (FConfig c : ALL.values()) {
            Map<String, Object> b = before.getOrDefault(c.id, Map.of());
            Map<String, Object> a = after.getOrDefault(c.id, Map.of());
            List<String> keys = new ArrayList<>();
            Set<String> all = new LinkedHashSet<>(b.keySet());
            all.addAll(a.keySet());
            boolean serverSide = false;
            for (String k : all) {
                if (!Objects.equals(b.get(k), a.get(k))) {
                    keys.add(k);
                    ConfigEntry e = c.entries.get(k);
                    if (e == null || e.side == Side.SERVER) serverSide = true;
                }
            }
            if (fire && !keys.isEmpty()) ConfigEvent.fire(new ConfigEvent.Changed(c.id, List.copyOf(keys)));
            boolean declChanged = !Objects.equals(declBefore.get(c.id), declAfter.get(c.id));
            MinecraftServer s = server;
            if (live && s != null && (serverSide || declChanged) && c.hasServerEntries()) {
                Sync msg = c.sync();
                for (ServerPlayer p : s.getPlayerList().getPlayers()) FNet.toPlayer(p, msg);
            }
        }
    }

    private static Map<FId, Map<String, Object>> snapshot() {
        Map<FId, Map<String, Object>> out = new HashMap<>();
        for (FConfig c : ALL.values()) {
            Map<String, Object> m = new HashMap<>();
            for (ConfigEntry e : c.entries.values()) {
                if (e.side != Side.STARTUP) m.put(e.key, e.current());
            }
            out.put(c.id, m);
        }
        return out;
    }

    private static Map<FId, String> declarations() {
        Map<FId, String> out = new HashMap<>();
        for (FConfig c : ALL.values()) out.put(c.id, c.declarationJson());
        return out;
    }

    boolean hasServerEntries() {
        for (ConfigEntry e : this.entries.values()) {
            if (e.side == Side.SERVER) return true;
        }
        return false;
    }

    String declarationJson() {
        JsonObject entries = new JsonObject();
        for (ConfigEntry e : this.entries.values()) entries.add(e.key, e.declaration());
        JsonObject o = new JsonObject();
        o.add("entries", entries);
        return Json5.write(o);
    }

    // ---- sync ----

    private static final FStreamCodec<String> TEXT = FStreamCodec.BYTES.map(b -> new String(b, StandardCharsets.UTF_8), s -> s.getBytes(StandardCharsets.UTF_8));

    /** Server to client: a config's declaration and the values of its {@code server} entries. */
    public record Sync(FId id, String declaration, Map<String, String> values) {
        static final FStreamCodec<Sync> CODEC = FStreamCodec.composite(
            FStreamCodec.ID, Sync::id, TEXT, Sync::declaration, FStreamCodec.mapOf(FStreamCodec.STRING, TEXT), Sync::values, Sync::new);
    }

    /** Client to server: an operator sets (or with an empty value resets) one entry. */
    public record Edit(FId id, String key, Optional<String> value) {
        static final FStreamCodec<Edit> CODEC = FStreamCodec.composite(
            FStreamCodec.ID, Edit::id, FStreamCodec.STRING, Edit::key, TEXT.optional(), Edit::value, Edit::new);
    }

    /** The sync message for this config as the server sees it now. */
    public Sync sync() {
        Map<String, String> values = new LinkedHashMap<>();
        for (ConfigEntry e : this.entries.values()) {
            if (e.side == Side.SERVER) values.put(e.key, Json5.write(e.toJson(e.effective())));
        }
        return new Sync(this.id, declarationJson(), values);
    }

    /** Takes in the server's values (client side). A config the client does not know is declared from the message. */
    public static void applySync(Sync msg) {
        change(() -> {
            FConfig c = of(msg.id());
            synchronized (c) {
                if (!c.code && !c.data) {
                    boolean fresh = !c.remote;
                    try {
                        JsonObject decl = JsonParser.parseString(msg.declaration()).getAsJsonObject().getAsJsonObject("entries");
                        Map<String, ConfigEntry> entries = new LinkedHashMap<>();
                        for (Map.Entry<String, JsonElement> x : decl.entrySet()) {
                            entries.put(x.getKey(), ConfigEntry.declared(c, x.getKey(), x.getValue().getAsJsonObject()));
                        }
                        c.entries.clear();
                        c.entries.putAll(entries);
                        c.remote = true;
                    } catch (RuntimeException ex) {
                        LOG.error("Config {} from the server could not be read: {}", msg.id(), ex.getMessage());
                        return;
                    }
                    if (fresh || !c.loaded) c.reloadFiles();
                    else c.applyLayers();
                }
                for (ConfigEntry e : c.entries.values()) {
                    if (e.side != Side.SERVER) continue;
                    String v = msg.values().get(e.key);
                    try {
                        e.synced = v == null ? null : e.fromJson(JsonParser.parseString(v));
                    } catch (RuntimeException ex) {
                        LOG.warn("Config {} {}: server value {} not used ({})", msg.id(), e.key, v, ex.getMessage());
                        e.synced = null;
                    }
                }
            }
        });
    }

    /**
     * An edit from a player. Only operators (permission level 2) may change server values; every attempt is logged.
     * Returns true when the edit was applied.
     */
    public static boolean handleEdit(ServerPlayer player, Edit msg) {
        String who = player.getName().getString();
        FConfig c = ALL.get(msg.id());
        ConfigEntry e = c == null ? null : c.entries.get(msg.key());
        if (!FVanilla.isOp(player)) {
            LOG.warn("Refused config edit from {} (not an operator): {} {}", who, msg.id(), msg.key());
            if (c != null && c.hasServerEntries()) FNet.toPlayer(player, c.sync());
            return false;
        }
        if (e == null || e.side == Side.CLIENT) {
            LOG.warn("Refused config edit from {}: {} has no server entry {}", who, msg.id(), msg.key());
            return false;
        }
        Object value;
        try {
            value = msg.value().isPresent() ? e.fromJson(Json5.parse(msg.value().get())) : null;
        } catch (IllegalArgumentException ex) {
            LOG.warn("Refused config edit from {}: {} {} = {} ({})", who, msg.id(), msg.key(), msg.value().get(), ex.getMessage());
            return false;
        }
        LOG.info("Config edit by {}: {} {} = {}", who, msg.id(), msg.key(), value == null ? "(default)" : e.toText(value));
        c.write(msg.key(), value);
        return true;
    }
}
