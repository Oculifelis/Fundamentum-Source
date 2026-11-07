package com.fundamentum.moon;

import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * Night events, shared by every mod, beside {@link Moons}: their data ({@link NightEvent}), the one happening now (at
 * most one per world, rolled at overworld dusk after tonight's moon, or raised by {@link #raise} or
 * {@code /fundamentum event raise <id> [ticks]}), its fog and sky tints and title on the clients, and the start, end,
 * every-two-seconds and kill listeners. Like moons it is kept with the overworld and happens in its own dimension
 * only. An event with no "duration" runs from dusk until dawn (only while it is night there); one with a duration runs
 * that many ticks from when it starts, day or night. Flags are the moons' ({@link Moons#registerFlag}). Server side,
 * except the tints, which {@link MoonsClient} lays over the moon's.
 */
public final class NightEvents {
    /** An event's start and end and what happens during it. Runs on the server thread. */
    public interface Listener {
        /** The event started on this level (or a player's level came under it after a restart). */
        default void onStart(ServerLevel level, NightEvent event) {}

        /**
         * It ended. {@code outcome} is {@link #DAWN}, {@link #TIME} (a timed one ran out), {@link #CLEARED},
         * {@link #REPLACED}, or what a mod passed to {@link #end}.
         */
        default void onEnd(ServerLevel level, NightEvent event, String outcome) {}

        /** Every two seconds while it runs on this level. */
        default void whileUp(ServerLevel level, NightEvent event) {}

        /** A player killed a creature (any living one, hostile or not) during it. */
        default void onKill(ServerLevel level, ServerPlayer player, LivingEntity dead, NightEvent event) {}
    }

    /** Scales an event's chance tonight (return {@code chance} unchanged for events that are not yours). */
    public interface Chance {
        double apply(ServerLevel level, NightEvent event, double chance);
    }

    public static final String DAWN = "dawn";
    public static final String TIME = "time";
    public static final String CLEARED = "cleared";
    public static final String REPLACED = "replaced";

    public static final DataTable<JsonObject> EVENTS = DataTables.registerJson(Identifier.fromNamespaceAndPath("fundamentum", "night_events"),
        "fundamentum/night_events").onLoad(t -> load(t.rawEntries()));

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    /** As for moons: a listener that throws is logged against its mod, the others still run, it goes after three. */
    private static final ListenerGuard<Listener> GUARD = new ListenerGuard<>("night event", LISTENERS, Owner::of,
        (message, cause) -> com.mojang.logging.LogUtils.getLogger().error(message, cause));
    private static final List<Chance> CHANCES = new CopyOnWriteArrayList<>();
    /** Per dimension, the event last seen there (for start and end). */
    private static final Map<String, String> SEEN = new HashMap<>();
    /** What each client was last told. */
    private static final Map<UUID, String> TOLD = new HashMap<>();
    private static Map<String, NightEvent> byId = Map.of();
    /** Why the running event is about to end, set by end/clear/raise and read once the end is seen. */
    private static @Nullable String ending;

    /**
     * A listener that throws is logged as an ERROR naming its mod and the event, the others still run, and after
     * three failures it is dropped until the game restarts.
     */
    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static void registerChance(Chance chance) {
        CHANCES.add(chance);
    }

    private static void load(Map<Identifier, JsonObject> files) {
        Map<String, NightEvent> map = new LinkedHashMap<>();
        files.forEach((id, json) -> {
            try {
                map.put(id.toString(), NightEvent.parse(id.toString(), json));
            } catch (Exception e) {
                com.mojang.logging.LogUtils.getLogger().error("Fundamentum: bad night event {}: {}", id, e.getMessage());
            }
        });
        byId = map;
    }

    public static Collection<NightEvent> all() {
        return byId.values();
    }

    public static Optional<NightEvent> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    // ------------------------------------------------------------------------------------------------ the event now
    /** The event happening on this level now, if any. */
    public static Optional<NightEvent> current(Level level) {
        if (!(level instanceof ServerLevel sl)) {
            return Optional.empty();
        }
        Data d = data(sl);
        NightEvent e = byId.get(d.event);
        if (e == null || !e.dimension().equals(sl.dimension().identifier().toString())) {
            return Optional.empty();
        }
        if (d.end >= 0) {
            return sl.getServer().overworld().getGameTime() < d.end ? Optional.of(e) : Optional.empty();
        }
        return d.night == Moons.tonight(sl) && Moons.isNight(sl) ? Optional.of(e) : Optional.empty();
    }

    public static boolean isUp(Level level, String id) {
        return current(level).map(e -> e.id().equals(id)).orElse(false);
    }

    /** Ticks left of a timed event, or -1 for one that runs until dawn (or none). */
    public static long ticksLeft(ServerLevel level) {
        Data d = data(level);
        return d.end < 0 ? -1 : Math.max(0, d.end - level.getServer().overworld().getGameTime());
    }

    /** Where the running event was started by {@link #start} (e.g. at a table), if it was. */
    public static Optional<BlockPos> pos(ServerLevel level) {
        Data d = data(level);
        return d.pos == Long.MIN_VALUE || current(level).isEmpty() ? Optional.empty() : Optional.of(BlockPos.of(d.pos));
    }

    /** Starts {@code id} now for its data's duration (replacing any other). False if no such event is loaded. */
    public static boolean raise(ServerLevel level, String id) {
        NightEvent e = byId.get(id);
        return e != null && raise(level, id, e.duration());
    }

    /** Starts {@code id} now for {@code ticks} (0 = tonight, until dawn), replacing any other. For commands and tests. */
    public static boolean raise(ServerLevel level, String id, int ticks) {
        return begin(level, id, ticks, null);
    }

    /**
     * Why {@code id} can't be started by a player here and now, or empty when it can: "unknown", "elsewhere" (wrong
     * dimension), "daylight" (a dusk-to-dawn event in the day), "taken" (another event runs) or "moon" (tonight's moon
     * excludes it: any special moon when its "with_moon" is false, or one of its "not_moons"; or not one of its "moons").
     */
    public static Optional<String> refusal(ServerLevel level, String id) {
        NightEvent e = byId.get(id);
        if (e == null) {
            return Optional.of("unknown");
        }
        if (!e.dimension().equals(level.dimension().identifier().toString())) {
            return Optional.of("elsewhere");
        }
        if (e.duration() <= 0 && !Moons.isNight(level)) {
            return Optional.of("daylight");
        }
        if (current(level).isPresent()) {
            return Optional.of("taken");
        }
        String moon = Moons.tonightsMoon(level);
        if (!moon.isEmpty() && (!e.withMoon() || e.notMoons().contains(moon)) || !e.moons().isEmpty() && !e.moons().contains(moon)) {
            return Optional.of("moon");
        }
        return Optional.empty();
    }

    /**
     * Starts {@code id} at {@code pos} unless something refuses it ({@link #refusal}): for events a player starts,
     * like a feast laid at a table. Returns the refusal, or empty when it started.
     */
    public static Optional<String> start(ServerLevel level, String id, BlockPos pos) {
        Optional<String> no = refusal(level, id);
        if (no.isEmpty()) {
            begin(level, id, byId.get(id).duration(), pos);
        }
        return no;
    }

    private static boolean begin(ServerLevel level, String id, int ticks, @Nullable BlockPos pos) {
        if (!byId.containsKey(id)) {
            return false;
        }
        Data d = data(level);
        if (current(level).isPresent()) {
            ending = REPLACED;
        }
        d.event = id;
        d.night = Moons.tonight(level);
        d.end = ticks > 0 ? level.getServer().overworld().getGameTime() + ticks : -1;
        d.pos = pos == null ? Long.MIN_VALUE : pos.asLong();
        d.started++;
        d.lastRun.put(id, d.night);
        d.setDirty();
        update(level.getServer());
        return true;
    }

    /** Ends the running event now, telling the listeners {@code outcome} (the mod's own word, e.g. "won", or {@link #CLEARED}). */
    public static void end(ServerLevel level, String outcome) {
        if (current(level).isEmpty()) {
            return;
        }
        Data d = data(level);
        ending = outcome;
        d.event = "";
        d.end = -1;
        d.pos = Long.MIN_VALUE;
        d.setDirty();
        update(level.getServer());
    }

    public static void clear(ServerLevel level) {
        end(level, CLEARED);
    }

    /** Whether the running event keeps special moons away (its "with_moon" is false): none rolls or is called by an item. */
    public static boolean holdsMoonsOff(ServerLevel level) {
        return current(level).map(e -> !e.withMoon()).orElse(false);
    }

    /** An event's chance tonight: its data, through every registered {@link Chance}, at most 1. */
    public static double chanceTonight(ServerLevel level, NightEvent e) {
        double c = e.chance();
        for (Chance ch : CHANCES) {
            c = ch.apply(level, e, c);
        }
        return Math.min(1.0, c);
    }

    private static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        long t = level.getGameTime();
        if (level.dimension() == Level.OVERWORLD && t % 20 == 0) {
            roll(level);
            update(level.getServer());
        }
        if (t % 40 == 0) {
            current(level).ifPresent(e -> {
                GUARD.fire("while-up", e.id(), li -> li.whileUp(level, e));
            });
        }
    }

    /** At dusk, once per night and after tonight's moon is decided, tonight's event is rolled. */
    private static void roll(ServerLevel level) {
        long dayTime = Moons.dayTime(level);
        int tod = (int) Math.floorMod(dayTime, 24000L);
        long night = Moons.night(dayTime);
        Data d = data(level);
        if (tod < 13000 || tod >= 14000 || d.lastRoll == night) {
            return;
        }
        d.lastRoll = night;
        d.setDirty();
        if (current(level).isPresent() || d.night == night && !d.event.isEmpty()) {
            return;
        }
        String moon = Moons.tonightsMoon(level);
        List<NightEvent> order = new ArrayList<>(byId.values());
        order.sort((a, b) -> a.priority() != b.priority() ? Integer.compare(b.priority(), a.priority()) : a.id().compareTo(b.id()));
        for (NightEvent e : order) {
            if (!e.eligible(e.dimension(), f -> Moons.flag(level, f), night, d.lastRun.getOrDefault(e.id(), -1L), moon)) {
                continue;
            }
            double c = chanceTonight(level, e);
            if (c > 0 && level.getRandom().nextDouble() < c) {
                raise(level, e.id());
                return;
            }
        }
    }

    /** Starts and ends in every dimension: announce, tell the listeners and the clients. */
    private static void update(MinecraftServer server) {
        Data d = data(server.overworld());
        String why = ending;
        ending = null;
        for (ServerLevel l : server.getAllLevels()) {
            String dim = l.dimension().identifier().toString();
            Optional<NightEvent> cur = current(l);
            String now = cur.map(e -> e.id() + "#" + d.started).orElse("");
            String was = SEEN.getOrDefault(dim, "");
            if (!now.equals(was)) {
                SEEN.put(dim, now);
                NightEvent old = was.isEmpty() ? null : byId.get(was.substring(0, was.lastIndexOf('#')));
                if (old != null) {
                    for (ServerPlayer p : l.players()) {
                        say(p, old, old.end());
                        Moons.hear(p, old.endSound());
                    }
                    String outcome = why != null ? why : Moons.isNight(l) ? TIME : DAWN;
                    GUARD.fire("end", old.id(), li -> li.onEnd(l, old, outcome));
                }
                if (cur.isPresent()) {
                    NightEvent e = cur.get();
                    if (d.announced != d.started) {
                        d.announced = d.started;
                        d.setDirty();
                        for (ServerPlayer p : l.players()) {
                            title(p, e);
                            say(p, e, e.start());
                            Moons.hear(p, e.startSound());
                        }
                    }
                    GUARD.fire("start", e.id(), li -> li.onStart(l, e));
                }
            }
            for (ServerPlayer p : l.players()) {
                tell(p, cur.orElse(null));
            }
        }
    }

    private static void tell(ServerPlayer p, @Nullable NightEvent e) {
        String id = e == null ? "" : e.id();
        if (id.equals(TOLD.get(p.getUUID()))) {
            return;
        }
        TOLD.put(p.getUUID(), id);
        PacketDistributor.sendToPlayer(p, EventPayload.of(e));
    }

    private static void say(ServerPlayer p, NightEvent e, Moon.Message msg) {
        if (msg.isEmpty()) {
            return;
        }
        Component c = Component.translatable(msg.key()).withStyle(st -> st.withColor(e.color(msg.color())).withBold(msg.bold()).withItalic(msg.italic()));
        if (msg.overlay()) {
            p.sendOverlayMessage(c);
        } else {
            p.sendSystemMessage(c);
        }
    }

    private static void title(ServerPlayer p, NightEvent e) {
        NightEvent.Title t = e.title();
        if (t.isEmpty()) {
            return;
        }
        int color = e.color(t.color());
        p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
        if (!t.subtitle().isEmpty()) {
            p.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable(t.subtitle()).withColor(color)));
        }
        p.connection.send(new ClientboundSetTitleTextPacket(t.title().isEmpty() ? Component.empty() : Component.translatable(t.title()).withColor(color)));
    }

    private static void sync(PlayerEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            TOLD.remove(p.getUUID());
            tell(p, current(p.level()).orElse(null));
        }
    }

    private static void death(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.level() instanceof ServerLevel level && event.getSource().getEntity() instanceof ServerPlayer p) {
            current(level).ifPresent(e -> {
                GUARD.fire("kill", e.id(), li -> li.onKill(level, p, dead, e));
            });
        }
    }

    // ------------------------------------------------------------------------------------------------ command
    /** {@code /fundamentum event raise <id> [ticks] | clear | query} (operators). */
    private static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fundamentum")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("event")
                .then(Commands.literal("raise").then(Commands.argument("id", StringArgumentType.string())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(byId.keySet().stream().map(s -> "\"" + s + "\""), b))
                    .executes(c -> raiseCommand(c, -1))
                    .then(Commands.argument("ticks", IntegerArgumentType.integer(0))
                        .executes(c -> raiseCommand(c, IntegerArgumentType.getInteger(c, "ticks"))))))
                .then(Commands.literal("clear").executes(c -> {
                    clear(c.getSource().getLevel());
                    c.getSource().sendSuccess(() -> Component.translatable("fundamentum.event.cleared"), true);
                    return 1;
                }))
                .then(Commands.literal("query").executes(c -> {
                    ServerLevel level = c.getSource().getLevel();
                    String id = current(level).map(NightEvent::id).orElse("-");
                    long left = ticksLeft(level);
                    c.getSource().sendSuccess(() -> Component.translatable("fundamentum.event.query", id, left < 0 ? "dawn" : left + " ticks"), false);
                    return 1;
                }))));
    }

    private static int raiseCommand(CommandContext<CommandSourceStack> c, int ticks) {
        String id = StringArgumentType.getString(c, "id");
        ServerLevel level = c.getSource().getLevel();
        if (!(ticks < 0 ? raise(level, id) : raise(level, id, ticks))) {
            c.getSource().sendFailure(Component.translatable("fundamentum.event.unknown", id));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.translatable("fundamentum.event.raised", id), true);
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ saved state
    private static Data data(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(Data.TYPE);
    }

    /**
     * The event, the night it belongs to, when a timed one ends (overworld game time, -1 = at dawn), how many have
     * started (so a restart is told apart from a new one), the last night rolled and announced, and the night each
     * event last happened (for cooldowns). Kept with the overworld.
     */
    static final class Data extends SavedData {
        static final SavedDataType<Data> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath("fundamentum", "night_events"), Data::new,
            CompoundTag.CODEC.xmap(Data::load, d -> d.save(new CompoundTag())));

        String event = "";
        long night = -1;
        long end = -1;
        long started;
        long lastRoll = -1;
        long announced = -1;
        long pos = Long.MIN_VALUE;
        final Map<String, Long> lastRun = new HashMap<>();

        static Data load(CompoundTag tag) {
            Data d = new Data();
            d.event = tag.getStringOr("event", "");
            d.night = tag.getLongOr("night", -1L);
            d.end = tag.getLongOr("end", -1L);
            d.started = tag.getLongOr("started", 0L);
            d.lastRoll = tag.getLongOr("last_roll", -1L);
            d.announced = tag.getLongOr("announced", -1L);
            d.pos = tag.getLongOr("pos", Long.MIN_VALUE);
            CompoundTag runs = tag.getCompoundOrEmpty("last_run");
            for (String k : runs.keySet()) {
                d.lastRun.put(k, runs.getLongOr(k, -1L));
            }
            return d;
        }

        CompoundTag save(CompoundTag tag) {
            tag.putString("event", this.event);
            tag.putLong("night", this.night);
            tag.putLong("end", this.end);
            tag.putLong("started", this.started);
            tag.putLong("last_roll", this.lastRoll);
            tag.putLong("announced", this.announced);
            tag.putLong("pos", this.pos);
            CompoundTag runs = new CompoundTag();
            this.lastRun.forEach(runs::putLong);
            tag.put("last_run", runs);
            return tag;
        }
    }

    // ------------------------------------------------------------------------------------------------ network
    /** The event over a client's level: its id ("" for none) and its fog and sky tints. */
    public record EventPayload(String event, Moon.Tint fog, Moon.Tint sky) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<EventPayload> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("fundamentum", "night_event"));
        public static final StreamCodec<FriendlyByteBuf, EventPayload> STREAM_CODEC = StreamCodec.ofMember(EventPayload::encode, EventPayload::decode);

        static EventPayload of(@Nullable NightEvent e) {
            return e == null ? new EventPayload("", Moon.Tint.NONE, Moon.Tint.NONE) : new EventPayload(e.id(), e.fog(), e.sky());
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(this.event, 256);
            this.fog.write(buf);
            this.sky.write(buf);
        }

        static EventPayload decode(FriendlyByteBuf buf) {
            return new EventPayload(buf.readUtf(256), Moon.Tint.read(buf), Moon.Tint.read(buf));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void init(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("2").optional()
            .playToClient(EventPayload.TYPE, EventPayload.STREAM_CODEC, (payload, context) -> MoonsClient.setEvent(payload.event(), payload.fog(), payload.sky())));
        NeoForge.EVENT_BUS.addListener(NightEvents::tick);
        NeoForge.EVENT_BUS.addListener(NightEvents::death);
        NeoForge.EVENT_BUS.addListener(NightEvents::commands);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> sync(e));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent e) -> sync(e));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> sync(e));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> TOLD.remove(e.getEntity().getUUID()));
    }

    private NightEvents() {}
}
