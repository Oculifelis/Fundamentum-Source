package com.fundamentum.climate;

import com.fundamentum.config.ConfigEvent;
import com.fundamentum.config.ConfigValue;
import com.fundamentum.config.FConfig;
import com.fundamentum.config.Timers;
import com.fundamentum.event.FEvents;
import com.fundamentum.moon.Moons;
import com.fundamentum.port.FClimate;
import com.fundamentum.port.FCommands;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLevelData;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStreamCodec;
import com.google.gson.JsonElement;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seasons from {@code data/<ns>/fundamentum/seasons/*.json}, off until the {@code fundamentum:climate} config's
 * {@code seasons} is true. The year runs on the overworld's day count: the seasons follow each other by {@code order},
 * each lasting its {@code length} in days, and the cycle repeats. The config can override the lengths
 * ({@code season_length} for all, {@code season_lengths} per season, as "fundamentum:spring=10, fundamentum:winter=6")
 * and pick which seasons run ({@code season_list}, empty for all). The season is worked out again when the day number
 * changes, not on a timer. The current season applies in the dimensions the
 * config lists (the overworld by default). Changing season posts {@code fundamentum:season_changed}; clients are told
 * the season so they can tint grass and leaves.
 *
 * <p>With Serene Seasons installed (and the config's {@code serene_seasons} on) its season is used instead and
 * Fundamentum leaves crops and tints to it; see {@link SereneBridge}.
 */
public final class Seasons {
    private Seasons() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    public static final FId SEASON_CHANGED = FId.of("fundamentum", "season_changed");
    public static final FId CONFIG_ID = FId.of("fundamentum", "climate");
    private static final FId SYNC_ID = FId.of("fundamentum", "season_sync");
    private static final RandomSource RANDOM = RandomSource.create();

    /** What clients are told: the season and the values they use without the season data. {@code id} is "" for none. */
    public record Sync(String id, int day, int length, double temperature, double rainfall, int grass, int foliage, double tint) {
        public static final FStreamCodec<Sync> CODEC = FStreamCodec.of((buf, s) -> {
            buf.writeUtf(s.id);
            buf.writeVarInt(s.day);
            buf.writeVarInt(s.length);
            buf.writeDouble(s.temperature);
            buf.writeDouble(s.rainfall);
            buf.writeInt(s.grass);
            buf.writeInt(s.foliage);
            buf.writeDouble(s.tint);
        }, buf -> new Sync(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble(),
            buf.readInt(), buf.readInt(), buf.readDouble()));

        static final Sync NONE = new Sync("", 0, 0, 0, 0, -1, -1, 0);
    }

    private static FConfig config;
    private static ConfigValue<Boolean> enabled;
    private static ConfigValue<Boolean> cropGrowth;
    private static ConfigValue<Boolean> tint;
    private static ConfigValue<Boolean> serene;
    private static ConfigValue<List<FId>> dimensions;
    private static ConfigValue<Integer> seasonLength;
    private static ConfigValue<String> seasonLengths;
    private static ConfigValue<List<FId>> seasonList;
    private static ConfigValue<Integer> sereneTimer;
    private static String parsedLengthsText;
    private static Map<FId, Integer> parsedLengths = Map.of();
    private static long lastDay = Long.MIN_VALUE;

    private static final FLevelData<Long> OFFSET = FLevelData.of(FId.of("fundamentum", "season_offset"), Codec.LONG, () -> 0L);

    private static volatile List<Season> seasons = List.of();
    private static MinecraftServer server;
    private static FId lastSeen;
    private static int timer;
    private static Sync lastSent = Sync.NONE;
    private static volatile Sync client = Sync.NONE;
    private static volatile Runnable clientRefresh = () -> { };
    private static boolean initialised;

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        config = FConfig.create(CONFIG_ID);
        enabled = config.bool("seasons", false);
        cropGrowth = config.bool("crop_growth", true);
        tint = config.bool("tint", true);
        serene = config.bool("serene_seasons", true);
        dimensions = config.idList("dimensions", List.of(FId.of("minecraft", "overworld")));
        seasonLength = config.integer("season_length", 0, 0, 100000);
        seasonLengths = config.string("season_lengths", "");
        seasonList = config.idList("season_list", List.of());
        sereneTimer = Timers.seconds("seasons_recheck_seconds", 30);
        FEvents.register(SEASON_CHANGED, SeasonChanged.class, false);
        FNet.register(SYNC_ID, Sync.class, Sync.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) -> applySync(msg));
        FLife.jsonData(FId.of("fundamentum", "seasons"), "fundamentum/seasons", Seasons::load);
        FLife.jsonData(FId.of("fundamentum", "climate_blocks"), "fundamentum/climate_blocks", Climate::loadSources);
        FLife.serverStarted(s -> {
            server = s;
            lastSeen = idOf(currentSeason(s));
            lastSent = Sync.NONE;
            timer = 0;
            lastDay = Long.MIN_VALUE;
        });
        FLife.serverStopped(s -> {
            server = null;
            lastSeen = null;
        });
        FLife.serverTick(Seasons::tick);
        FLife.playerLoggedIn(p -> FNet.toPlayer(p, syncFor(p.level().getServer())));
        FEvents.listen(ConfigEvent.Changed.class, e -> {
            if (CONFIG_ID.equals(e.id()) && server != null) refresh(server);
        });
        FClimate.cropRule(Seasons::cropRule);
        FClimate.cropGrown(Seasons::cropGrown);
        FCommands.listen(d -> d.register(Commands.literal("fundamentum").requires(FCommands.op())
            .then(Commands.literal("climate").executes(Seasons::climateCommand))
            .then(Commands.literal("season").executes(Seasons::seasonCommand)
                .then(Commands.literal("set").then(Commands.argument("season", StringArgumentType.greedyString())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(seasons.stream().map(x -> x.id().toString()), b))
                    .executes(Seasons::setCommand))))));
    }

    /** Called by the client side once, so a tint change re-colours what is already drawn. */
    public static void clientRefresh(Runnable refresh) {
        clientRefresh = refresh == null ? () -> { } : refresh;
    }

    // ---- config ----

    /** True when Fundamentum's own seasons run (the config is on and Serene Seasons is not taking over). */
    public static boolean enabled() {
        return enabled != null && enabled.getOr(false) && !sereneActive();
    }

    /** True when Serene Seasons is installed and the config lets it supply the season. */
    public static boolean sereneActive() {
        return serene != null && serene.getOr(true) && SereneBridge.available();
    }

    public static ConfigValue<Boolean> enabledValue() {
        return enabled;
    }

    public static ConfigValue<Boolean> cropGrowthValue() {
        return cropGrowth;
    }

    public static ConfigValue<Boolean> tintValue() {
        return tint;
    }

    public static ConfigValue<Integer> seasonLengthValue() {
        return seasonLength;
    }

    public static ConfigValue<String> seasonLengthsValue() {
        return seasonLengths;
    }

    public static ConfigValue<List<FId>> seasonListValue() {
        return seasonList;
    }

    // ---- data ----

    /** Every loaded season in year order. */
    public static List<Season> all() {
        return seasons;
    }

    public static Season byId(FId id) {
        for (Season s : seasons) if (s.id().equals(id)) return s;
        return null;
    }

    /** The seasons that run, in year order: the config's {@code season_list} when it names any, else all of them. */
    public static List<Season> active() {
        List<FId> pick = seasonList == null ? List.of() : seasonList.getOr(List.of());
        List<Season> all = seasons;
        if (pick.isEmpty()) return all;
        List<Season> out = new ArrayList<>();
        for (Season s : all) if (pick.contains(s.id())) out.add(s);
        return out;
    }

    /**
     * A season's length in days: its entry in the config's {@code season_lengths}, else {@code season_length} when that
     * is above 0, else the length its file gives.
     */
    public static int lengthOf(Season s) {
        Integer own = lengths().get(s.id());
        if (own != null && own > 0) return own;
        int all = seasonLength == null ? 0 : seasonLength.getOr(0);
        return all > 0 ? all : s.length();
    }

    private static synchronized Map<FId, Integer> lengths() {
        String text = seasonLengths == null ? "" : seasonLengths.getOr("");
        if (text == null) text = "";
        if (text.equals(parsedLengthsText)) return parsedLengths;
        Map<FId, Integer> out = new java.util.HashMap<>();
        for (String part : text.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.lastIndexOf('=');
            FId id = eq <= 0 ? null : FId.tryOf(p.substring(0, eq).trim());
            if (id == null) {
                LOG.warn("season_lengths entry '{}' is not season=days", p);
                continue;
            }
            try {
                out.put(id, Math.max(1, Integer.parseInt(p.substring(eq + 1).trim())));
            } catch (NumberFormatException e) {
                LOG.warn("season_lengths entry '{}' has no whole number of days", p);
            }
        }
        parsedLengthsText = text;
        parsedLengths = Map.copyOf(out);
        return parsedLengths;
    }

    /** Days in one full year. */
    public static int yearLength() {
        int n = 0;
        for (Season s : active()) n += lengthOf(s);
        return n;
    }

    private static void load(Map<FId, List<JsonElement>> files) {
        List<Season> out = new ArrayList<>();
        new TreeMap<>(files).forEach((id, copies) -> {
            try {
                out.add(Season.parse(id, copies.get(copies.size() - 1).getAsJsonObject()));
            } catch (RuntimeException e) {
                LOG.error("Season file {} could not be read: {}", id, e.toString());
            }
        });
        out.sort(Comparator.comparingInt(Season::order).thenComparing(Season::id));
        seasons = List.copyOf(out);
        LOG.info("Loaded {} seasons", out.size());
        if (server != null) refresh(server);
    }

    // ---- the calendar ----

    /** The world's day count the year runs on (overworld days plus the offset {@link #set} keeps). */
    public static long day(MinecraftServer server) {
        return Math.floorDiv(Moons.dayTime(server.overworld()), 24000L) + OFFSET.get(server);
    }

    /** The season on a given day of the calendar, or null with no seasons loaded. */
    public static Season at(long day) {
        int year = yearLength();
        if (year == 0) return null;
        long d = Math.floorMod(day, (long) year);
        for (Season s : active()) {
            int len = lengthOf(s);
            if (d < len) return s;
            d -= len;
        }
        return null;
    }

    /** Days already gone in the season of that day (0 on its first day). */
    public static int dayInSeason(long day) {
        int year = yearLength();
        if (year == 0) return 0;
        long d = Math.floorMod(day, (long) year);
        for (Season s : active()) {
            int len = lengthOf(s);
            if (d < len) return (int) d;
            d -= len;
        }
        return 0;
    }

    /** The world-wide season now, ignoring dimensions; null while seasons are off. */
    public static Season currentSeason(MinecraftServer server) {
        if (sereneActive()) {
            String name = SereneBridge.season(server.overworld());
            return name == null ? null : byName(name);
        }
        if (!enabled()) return null;
        return at(day(server));
    }

    /**
     * The season in this level, or null while seasons are off or the dimension has none. On a client level, a season
     * built from what the server sent (flat offsets only).
     */
    public static Season current(Level level) {
        if (level instanceof ServerLevel s) {
            if (!dimensions.getOr(List.of()).contains(FClimate.dimensionId(s))) return null;
            return currentSeason(s.getServer());
        }
        Sync c = client;
        if (c.id.isEmpty()) return null;
        return new Season(FId.of(c.id), 0, Math.max(1, c.length), c.temperature, c.rainfall, List.of(), 1, List.of(),
            c.grass, c.foliage, c.tint);
    }

    /** Starts the season {@code id} today (moves the calendar, not the clock). False for an unknown season. */
    public static boolean set(MinecraftServer server, FId id) {
        long start = 0;
        for (Season s : active()) {
            if (s.id().equals(id)) {
                long now = day(server) - OFFSET.get(server);
                long base = Math.floorMod(now, (long) yearLength());
                OFFSET.set(server.overworld(), start - base);
                refresh(server);
                return true;
            }
            start += lengthOf(s);
        }
        return false;
    }

    /** Sets the calendar so today is that day of the year. */
    public static void setDay(MinecraftServer server, long dayOfYear) {
        long now = day(server) - OFFSET.get(server);
        OFFSET.set(server.overworld(), dayOfYear - now);
        refresh(server);
    }

    private static Season byName(String name) {
        for (Season s : seasons) if (s.id().path().equalsIgnoreCase(name)) return s;
        return null;
    }

    private static FId idOf(Season s) {
        return s == null ? null : s.id();
    }

    private static void tick(MinecraftServer s) {
        if (sereneActive()) {
            // Serene Seasons announces nothing, so its season is read on the slow timer
            int period = Timers.ticks(sereneTimer, 30);
            if (period > 0 && ++timer >= period) {
                timer = 0;
                refresh(s);
            }
            return;
        }
        if (!enabled()) {
            if (lastSeen != null || lastSent != Sync.NONE) refresh(s);
            return;
        }
        // the season can only move when the day number does (or the config or data changes, which refresh at once)
        long d = day(s);
        if (d == lastDay) return;
        lastDay = d;
        refresh(s);
    }

    /** Posts the change event when the season moved and tells clients what changed. */
    static void refresh(MinecraftServer s) {
        Season now = currentSeason(s);
        FId id = idOf(now);
        if (!java.util.Objects.equals(id, lastSeen)) {
            FId from = lastSeen;
            lastSeen = id;
            FEvents.post(new SeasonChanged(from, id, day(s)));
            LOG.info("Season changed from {} to {}", from, id);
        }
        Sync sync = syncFor(s);
        if (!sync.equals(lastSent)) {
            lastSent = sync;
            FNet.toAll(sync);
        }
    }

    /** What clients should know now. The day only goes out when the season changes, so this is the season's first-day view. */
    public static Sync syncFor(MinecraftServer s) {
        if (s == null || sereneActive()) return Sync.NONE;
        Season now = currentSeason(s);
        if (now == null) return Sync.NONE;
        boolean t = tint.getOr(true);
        return new Sync(now.id().toString(), 0, lengthOf(now), now.temperature(), now.rainfall(), t ? now.grass() : -1,
            t ? now.foliage() : -1, t ? now.tint() : 0);
    }

    // ---- the client copy ----

    /** Applies a sync message as the client would. Public for tests. */
    public static void applySync(Sync msg) {
        Sync old = client;
        client = msg;
        if (old.grass != msg.grass || old.foliage != msg.foliage || old.tint != msg.tint) clientRefresh.run();
    }

    /** What this client was told last. */
    public static Sync clientSync() {
        return client;
    }

    // ---- crops ----

    /** The growth multiplier for a crop here now (1 when seasons are off, the dimension has none or crop growth is off). */
    public static double cropMultiplier(ServerLevel level, BlockState state) {
        if (!cropGrowth.getOr(true) || sereneActive()) return 1;
        Season s = current(level);
        return s == null ? 1 : s.cropGrowthFor(state);
    }

    private static Boolean cropRule(ServerLevel level, BlockPos pos, BlockState state) {
        double m = cropMultiplier(level, state);
        if (m >= 1) return null;
        return RANDOM.nextDouble() < m ? null : Boolean.FALSE;
    }

    private static void cropGrown(ServerLevel level, BlockPos pos, BlockState before, BlockState after) {
        double m = cropMultiplier(level, after);
        if (m <= 1) return;
        double extra = m - 1;
        while (extra > 0) {
            if (RANDOM.nextDouble() >= Math.min(1, extra)) break;
            BlockState now = level.getBlockState(pos);
            BlockState next = ageUp(now);
            if (next == null) break;
            level.setBlock(pos, next, 2);
            extra -= 1;
        }
    }

    /** The state one age step on, or null when it has no {@code age} or is fully grown. */
    static BlockState ageUp(BlockState state) {
        for (Property<?> p : state.getProperties()) {
            if (p instanceof IntegerProperty ip && p.getName().equals("age")) {
                int v = state.getValue(ip);
                int max = v;
                for (Integer x : ip.getPossibleValues()) max = Math.max(max, x);
                return v < max ? state.setValue(ip, v + 1) : null;
            }
        }
        return null;
    }

    // ---- commands ----

    private static int climateCommand(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        Level level = src.getLevel();
        Vec3 p = src.getPosition();
        BlockPos pos = BlockPos.containing(p.x, p.y, p.z);
        Climate.Reading t = Climate.temperatureParts(level, pos);
        Climate.Reading h = Climate.humidityParts(level, pos);
        String text = String.format(java.util.Locale.ROOT,
            "Temperature %.2f (biome %.2f, season %+.2f, height %+.2f, blocks %+.2f, weather %+.2f); humidity %.2f",
            t.total(), t.biome(), t.season(), t.height(), t.blocks(), t.weather(), Climate.clamp01(h.total()));
        src.sendSuccess(() -> Component.literal(text), false);
        return (int) Math.round(t.total() * 100);
    }

    private static int seasonCommand(CommandContext<CommandSourceStack> c) {
        MinecraftServer s = c.getSource().getServer();
        String text;
        int result;
        if (sereneActive()) {
            text = "Serene Seasons supplies the season: " + SereneBridge.season(s.overworld());
            result = 1;
        } else if (!enabled()) {
            text = "Seasons are off (config fundamentum:climate, seasons)";
            result = 0;
        } else if (seasons.isEmpty()) {
            text = "No seasons are loaded";
            result = 0;
        } else {
            long day = day(s);
            Season now = at(day);
            text = now.id() + ", day " + (dayInSeason(day) + 1) + " of " + lengthOf(now) + " (year of " + yearLength() + " days)";
            result = seasons.indexOf(now) + 1;
        }
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return result;
    }

    private static int setCommand(CommandContext<CommandSourceStack> c) {
        String raw = StringArgumentType.getString(c, "season").trim();
        FId id = FId.tryOf(raw.contains(":") ? raw : "fundamentum:" + raw);
        MinecraftServer s = c.getSource().getServer();
        if (id == null || !set(s, id)) {
            c.getSource().sendFailure(Component.literal("Unknown season " + raw));
            return 0;
        }
        if (!enabled()) c.getSource().sendSuccess(() -> Component.literal("Calendar set to " + id + ", but seasons are off"), false);
        else c.getSource().sendSuccess(() -> Component.literal("Season set to " + id), true);
        return 1;
    }
}
