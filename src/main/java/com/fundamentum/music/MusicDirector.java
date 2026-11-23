package com.fundamentum.music;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.config.ConfigValue;
import com.fundamentum.config.FConfig;
import com.fundamentum.config.Timers;
import com.fundamentum.port.FCommands;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStreamCodec;
import com.fundamentum.port.FTags;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The music system: rules from {@code data/<ns>/fundamentum/music/}, synced to every client, pick a track for where
 * the player is and what is happening. The server only works out the parts a client can't see for itself (the boss
 * fight, combat and which named structures the player is in) and sends them when they change. It keeps each player's
 * answer and only works a part out again when something it depends on changed: the structures when the player moves to
 * another block (and only where the chunk has structure pieces at all), combat when the player hurts or is hurt by a mob,
 * when a mob takes or drops them as its target, and when the combat window runs out. A slow full check runs as a safety
 * net (the {@code fundamentum:performance} config's {@code music_recheck_seconds}). Boss tracks (from boss
 * files, through {@link BossMusic}) take part at priority {@link #BOSS_PRIORITY}. The client plays the winner with a
 * crossfade and keeps vanilla music quiet meanwhile. The same file on every version.
 */
public final class MusicDirector {
    private MusicDirector() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    public static final FId CONFIG_ID = FId.of("fundamentum", "music");
    public static final FId STATE_ID = FId.of("fundamentum", "music_state");
    public static final FId RULES_ID = FId.of("fundamentum", "music_rules");
    /** The priority a boss's own track plays at; a rule above this wins even during the fight. */
    public static final int BOSS_PRIORITY = 1000;
    private static final int CHECK_EVERY = 10;

    /** The track to play: the rule it came from (the boss type for a boss track), its sound, priority and fades. */
    public record Choice(FId source, FId sound, int priority, int fadeIn, int fadeOut, boolean boss) { }

    /** The rules as sent to clients: rule id to the rule file's text. */
    public record Rules(Map<String, String> files) {
        public static final FStreamCodec<Rules> CODEC = FStreamCodec.mapOf(FStreamCodec.STRING, FStreamCodec.STRING)
            .map(Rules::new, Rules::files);
    }

    private static final class PlayerMusic {
        FId bossSound;
        FId bossType;
        int bossFade = 40;
        MusicState sent;
        FId track;
        // the cached parts and what they were worked out from
        ServerLevel level;
        BlockPos pos;
        List<String> structures = List.of();
        boolean combat;
        int hurtBy = Integer.MIN_VALUE;
        int hurt = Integer.MIN_VALUE;
        int combatEnds = Integer.MAX_VALUE;
        boolean targetsChanged;
        final Set<Mob> targeters = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    }

    /** Which player each hostile mob targets, so a mob dropping its target can be taken off that player's list. */
    private static final Map<Mob, UUID> TARGETING = new WeakHashMap<>();
    private static ConfigValue<Integer> safetyNet;

    private static ConfigValue<Double> volume;
    private static ConfigValue<Boolean> enabled;
    private static ConfigValue<Boolean> stopVanilla;
    private static ConfigValue<Integer> combatTicks;
    private static ConfigValue<Integer> combatRange;

    private static volatile List<MusicRule> rules = List.of();
    private static volatile Map<String, String> ruleFiles = Map.of();
    private static volatile List<String> problems = List.of();
    private static volatile Set<String> watchedStructures = Set.of();
    private static final Map<UUID, PlayerMusic> PLAYERS = new ConcurrentHashMap<>();
    private static MinecraftServer server;
    private static int timer;

    private static volatile List<MusicRule> clientRules = List.of();
    private static volatile MusicState clientState = MusicState.NONE;
    private static boolean initialised;

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        FConfig config = FConfig.create(CONFIG_ID);
        enabled = config.bool("enabled", true).side(FConfig.Side.CLIENT);
        volume = config.decimal("volume", 1.0, 0.0, 1.0).side(FConfig.Side.CLIENT);
        stopVanilla = config.bool("stop_vanilla", true).side(FConfig.Side.CLIENT);
        combatTicks = config.integer("combat_ticks", 200, 20, 2400);
        combatRange = config.integer("combat_range", 16, 0, 64);
        FNet.register(STATE_ID, MusicState.class, MusicState.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) -> clientState = msg);
        FNet.register(RULES_ID, Rules.class, Rules.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) -> clientRules = parseAll(msg.files(), null));
        FLife.jsonData(FId.of("fundamentum", "music"), "fundamentum/music", MusicDirector::load);
        FLife.serverStarted(s -> {
            server = s;
            timer = 0;
        });
        FLife.serverStopped(s -> {
            server = null;
            PLAYERS.clear();
            synchronized (TARGETING) {
                TARGETING.clear();
            }
        });
        FLife.serverTick(MusicDirector::tick);
        FLife.targetChanged(MusicDirector::targetChanged);
        safetyNet = Timers.seconds("music_recheck_seconds", 30);
        FLife.playerLoggedIn(p -> {
            PLAYERS.remove(p.getUUID());
            FNet.toPlayer(p, new Rules(ruleFiles));
            send(p, true);
        });
        FCommands.listen(d -> d.register(Commands.literal("fundamentum").requires(FCommands.op())
            .then(Commands.literal("music").executes(MusicDirector::musicCommand))));
    }

    // ---- config ----

    public static boolean enabled() {
        return enabled == null || enabled.getOr(true);
    }

    /** The music volume, 0 to 1, on top of the game's own music slider. */
    public static double volume() {
        return volume == null ? 1 : volume.getOr(1.0);
    }

    public static boolean stopVanilla() {
        return stopVanilla == null || stopVanilla.getOr(true);
    }

    public static ConfigValue<Double> volumeValue() {
        return volume;
    }

    public static ConfigValue<Boolean> enabledValue() {
        return enabled;
    }

    public static ConfigValue<Boolean> stopVanillaValue() {
        return stopVanilla;
    }

    public static ConfigValue<Integer> combatTicksValue() {
        return combatTicks;
    }

    public static ConfigValue<Integer> combatRangeValue() {
        return combatRange;
    }

    // ---- rules ----

    /** Loads rule files: a broken one is left out and the log says why. */
    public static void load(Map<FId, List<JsonElement>> files) {
        Map<String, String> text = new LinkedHashMap<>();
        for (Map.Entry<FId, List<JsonElement>> e : new java.util.TreeMap<>(files).entrySet()) {
            List<JsonElement> stack = e.getValue();
            if (stack.isEmpty()) continue;
            text.put(e.getKey().toString(), stack.get(stack.size() - 1).toString());
        }
        List<String> errs = new ArrayList<>();
        List<MusicRule> parsed = parseAll(text, errs);
        Map<String, String> kept = new LinkedHashMap<>();
        for (MusicRule r : parsed) kept.put(r.id().toString(), text.get(r.id().toString()));
        rules = parsed;
        ruleFiles = kept;
        problems = List.copyOf(errs);
        Set<String> watch = new TreeSet<>();
        for (MusicRule r : parsed) watch.addAll(r.structures());
        watchedStructures = Set.copyOf(watch);
        if (parsed.size() > 0 || !errs.isEmpty()) LOG.info("Loaded {} music rules", parsed.size());
        if (server != null) FNet.toAll(new Rules(kept));
    }

    /** Parses rule texts (id to JSON), sorted by id; problems go to {@code errs} (or the log when it is null). */
    public static List<MusicRule> parseAll(Map<String, String> files, List<String> errs) {
        List<MusicRule> out = new ArrayList<>();
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(files).entrySet()) {
            try {
                JsonObject json = JsonParser.parseString(e.getValue()).getAsJsonObject();
                out.add(MusicRule.parse(FId.of(e.getKey()), json));
            } catch (Exception ex) {
                String why = "Music rule " + e.getKey() + " skipped: " + ex.getMessage();
                LOG.warn(why);
                if (errs != null) errs.add(why);
            }
        }
        return List.copyOf(out);
    }

    /** The rule files as sent to clients (rule id to its text). */
    public static Map<String, String> ruleFiles() {
        return ruleFiles;
    }

    public static List<MusicRule> rules() {
        return rules;
    }

    /** Why rule files were left out at the last load. */
    public static List<String> problems() {
        return problems;
    }

    /** The structure ids and {@code #tags} the rules name; only these are looked up for players. */
    public static Set<String> watchedStructures() {
        return watchedStructures;
    }

    /** The client's copy of the rules. */
    public static List<MusicRule> clientRules() {
        return clientRules;
    }

    /** Forgets the client's rules and state (on leaving a world). */
    public static void clientReset() {
        clientRules = List.of();
        clientState = MusicState.NONE;
    }

    /** What the server last told this client. */
    public static MusicState clientState() {
        return clientState;
    }

    /**
     * Picks the track: the highest priority among the rules that match and the boss track (if any). Ties go to the
     * boss track, then to the lowest rule id. Null when nothing should play.
     */
    public static Choice choose(List<MusicRule> from, MusicContext context, Choice boss) {
        Choice best = boss;
        for (MusicRule r : from) {
            if (!r.matches(context)) continue;
            if (best == null || r.priority() > best.priority()) {
                best = new Choice(r.id(), r.sound(), r.priority(), r.fadeIn(), r.fadeOut(), false);
            }
        }
        return best;
    }

    /** The boss track as a choice, from a state the server sent. */
    public static Choice bossChoice(MusicState state) {
        if (!state.bossFight()) return null;
        FId sound = FId.tryOf(state.bossSound());
        if (sound == null) return null;
        FId type = state.bossType().isEmpty() ? null : FId.tryOf(state.bossType());
        return new Choice(type == null ? sound : type, sound, BOSS_PRIORITY, state.bossFade(), state.bossFade(), true);
    }

    /** The context the server sees for a player, with structures, the boss fight and combat filled in. */
    public static MusicContext context(ServerPlayer player) {
        MusicState s = stateFor(player);
        return MusicContext.of(player, Set.copyOf(s.structures()), s.bossFight(),
            s.bossType().isEmpty() ? null : FId.of(s.bossType()), s.combat());
    }

    /** What would play for this player now, as the server sees it (the client decides for real). */
    public static Choice choiceFor(ServerPlayer player) {
        return choose(rules, context(player), bossChoice(stateFor(player)));
    }

    // ---- boss ----

    /** Starts (or crossfades to) a boss track for this player; {@code bossType} may be null. */
    public static void bossPlay(ServerPlayer player, FId sound, int fade, FId bossType) {
        PlayerMusic m = PLAYERS.computeIfAbsent(player.getUUID(), u -> new PlayerMusic());
        if (Objects.equals(m.bossSound, sound) && Objects.equals(m.bossType, bossType)) return;
        m.bossSound = sound;
        m.bossType = bossType;
        m.bossFade = Math.max(1, fade);
        send(player, false);
    }

    /** Ends this player's boss track, fading out over {@code fade} ticks. */
    public static void bossStop(ServerPlayer player, int fade) {
        PlayerMusic m = PLAYERS.get(player.getUUID());
        if (m == null || m.bossSound == null) return;
        m.bossSound = null;
        m.bossType = null;
        m.bossFade = Math.max(1, fade);
        send(player, false);
    }

    /** The boss track this player was last told to play, or null. */
    public static FId bossSound(ServerPlayer player) {
        PlayerMusic m = PLAYERS.get(player.getUUID());
        return m == null ? null : m.bossSound;
    }

    // ---- combat and structures ----

    /**
     * Whether the player is in a fight: hurt by a mob or hurt one within the last {@code combat_ticks}, or an enemy
     * within {@code combat_range} blocks has them as its target.
     */
    public static boolean inCombat(ServerPlayer player) {
        int window = combatTicks == null ? 200 : combatTicks.getOr(200);
        int now = player.tickCount;
        LivingEntity by = player.getLastHurtByMob();
        if (by != null && by.isAlive() && !(by instanceof Player) && now - player.getLastHurtByMobTimestamp() <= window) return true;
        LivingEntity hit = player.getLastHurtMob();
        if (hit != null && hit.isAlive() && !(hit instanceof Player) && now - player.getLastHurtMobTimestamp() <= window) return true;
        int range = combatRange == null ? 16 : combatRange.getOr(16);
        if (range <= 0 || !(player.level() instanceof ServerLevel level)) return false;
        AABB box = player.getBoundingBox().inflate(range);
        return !level.getEntitiesOfClass(Mob.class, box, m -> m instanceof Enemy && m.isAlive() && m.getTarget() == player).isEmpty();
    }

    /** Which of {@code keys} (structure ids or {@code #tags}) have a structure piece at this spot. */
    public static Set<String> structuresAt(ServerLevel level, BlockPos pos, Set<String> keys) {
        Set<String> out = new LinkedHashSet<>();
        if (keys.isEmpty()) return out;
        for (String k : keys) {
            try {
                if (k.startsWith("#")) {
                    if (level.structureManager().getStructureWithPieceAt(pos, FTags.of(Registries.STRUCTURE, FId.of(k.substring(1)))).isValid()) out.add(k);
                } else if (FVanilla.structurePieceAt(level, pos, FId.of(k))) {
                    out.add(k);
                }
            } catch (RuntimeException e) {
                // an unknown id or tag simply never matches
            }
        }
        return out;
    }

    /** The state the server would send this player now. */
    /**
     * The state as the event-driven path keeps it for the player: the cached parts brought up to date where something
     * changed, without a full recheck. Players online get this every few ticks; a test can call it for any player.
     */
    public static MusicState cachedStateFor(ServerPlayer player) {
        PlayerMusic m = PLAYERS.computeIfAbsent(player.getUUID(), u -> new PlayerMusic());
        refreshCache(player, m, false);
        return cachedState(m);
    }

    public static MusicState stateFor(ServerPlayer player) {
        PlayerMusic m = PLAYERS.get(player.getUUID());
        Set<String> structures = player.level() instanceof ServerLevel level
            ? structuresAt(level, player.blockPosition(), watchedStructures) : Set.of();
        boolean boss = m != null && m.bossSound != null;
        return new MusicState(boss ? m.bossSound.toString() : "", boss && m.bossType != null ? m.bossType.toString() : "",
            m == null ? 40 : m.bossFade, inCombat(player), List.copyOf(structures));
    }

    private static void targetChanged(Mob mob, LivingEntity target) {
        UUID now = target instanceof ServerPlayer p && mob instanceof Enemy ? p.getUUID() : null;
        UUID before;
        synchronized (TARGETING) {
            before = now == null ? TARGETING.remove(mob) : TARGETING.put(mob, now);
        }
        if (Objects.equals(before, now)) return;
        if (before != null) {
            PlayerMusic m = PLAYERS.get(before);
            if (m != null) {
                m.targeters.remove(mob);
                m.targetsChanged = true;
            }
        }
        if (now != null) {
            PlayerMusic m = PLAYERS.computeIfAbsent(now, u -> new PlayerMusic());
            m.targeters.add(mob);
            m.targetsChanged = true;
        }
    }

    /** {@link #inCombat} from the mobs known to target the player, without searching the area. */
    private static boolean combatFromCache(ServerPlayer player, PlayerMusic m) {
        int window = combatTicks == null ? 200 : combatTicks.getOr(200);
        int now = player.tickCount;
        m.combatEnds = Integer.MAX_VALUE;
        boolean fight = false;
        LivingEntity by = player.getLastHurtByMob();
        if (by != null && by.isAlive() && !(by instanceof Player) && now - player.getLastHurtByMobTimestamp() <= window) {
            fight = true;
            m.combatEnds = Math.min(m.combatEnds, player.getLastHurtByMobTimestamp() + window + 1);
        }
        LivingEntity hit = player.getLastHurtMob();
        if (hit != null && hit.isAlive() && !(hit instanceof Player) && now - player.getLastHurtMobTimestamp() <= window) {
            fight = true;
            m.combatEnds = Math.min(m.combatEnds, player.getLastHurtMobTimestamp() + window + 1);
        }
        if (fight) return true;
        int range = combatRange == null ? 16 : combatRange.getOr(16);
        if (range <= 0 || m.targeters.isEmpty()) return false;
        double r2 = (double) range * range;
        for (Mob mob : List.copyOf(m.targeters)) {
            if (!mob.isAlive() || mob.getTarget() != player) {
                m.targeters.remove(mob);
                continue;
            }
            if (mob.level() == player.level() && mob.distanceToSqr(player) <= r2) return true;
        }
        return false;
    }

    /** The structures at the player's block, skipping the lookups where the chunk holds no structure piece at all. */
    private static List<String> structuresFromCache(ServerPlayer player, ServerLevel level, BlockPos pos) {
        Set<String> watched = watchedStructures;
        if (watched.isEmpty()) return List.of();
        try {
            if (level.getChunkAt(pos).getAllReferences().isEmpty()) return List.of();
        } catch (RuntimeException e) {
            // fall through to the full lookup
        }
        return List.copyOf(structuresAt(level, pos, watched));
    }

    /** Brings the cached parts up to date where something changed; true when the state may differ. */
    private static boolean refreshCache(ServerPlayer player, PlayerMusic m, boolean full) {
        boolean dirty = full;
        if (!(player.level() instanceof ServerLevel level)) return false;
        BlockPos pos = player.blockPosition();
        if (full || level != m.level || !pos.equals(m.pos)) {
            m.level = level;
            m.pos = pos.immutable();
            m.structures = structuresFromCache(player, level, pos);
            dirty = true;
        }
        int hurtBy = player.getLastHurtByMobTimestamp();
        int hurt = player.getLastHurtMobTimestamp();
        boolean combatDirty = full || hurtBy != m.hurtBy || hurt != m.hurt || m.targetsChanged || player.tickCount >= m.combatEnds
            || !m.targeters.isEmpty();
        if (combatDirty) {
            m.hurtBy = hurtBy;
            m.hurt = hurt;
            m.targetsChanged = false;
            m.combat = combatFromCache(player, m);
            dirty = true;
        }
        return dirty;
    }

    private static MusicState cachedState(PlayerMusic m) {
        boolean boss = m.bossSound != null;
        return new MusicState(boss ? m.bossSound.toString() : "", boss && m.bossType != null ? m.bossType.toString() : "",
            m.bossFade, m.combat, m.structures);
    }

    private static void send(ServerPlayer player, boolean force) {
        PlayerMusic m = PLAYERS.computeIfAbsent(player.getUUID(), u -> new PlayerMusic());
        refreshCache(player, m, true);
        MusicState s = cachedState(m);
        sendState(player, m, s, force);
    }

    private static void sendState(ServerPlayer player, PlayerMusic m, MusicState s, boolean force) {
        if (!force && s.equals(m.sent)) return;
        m.sent = s;
        // fundamentum:music_changed, as far as the server can tell from the state
        Choice c = choose(rules, MusicContext.of(player, Set.copyOf(s.structures()), s.bossFight(),
            s.bossType().isEmpty() ? null : FId.of(s.bossType()), s.combat()), bossChoice(s));
        FId track = c == null ? null : c.sound();
        if (!java.util.Objects.equals(track, m.track)) {
            FId was = m.track;
            m.track = track;
            FEvents.post(new Events.MusicChanged(player, was, track));
        }
        if (player.connection == null) return;
        if (!(player.level() instanceof ServerLevel level) || level.getServer().getPlayerList().getPlayer(player.getUUID()) != player) return;
        FNet.toPlayer(player, s);
    }

    private static void tick(MinecraftServer s) {
        timer++;
        int full = Timers.ticks(safetyNet, 30);
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            // each player looks at its cached inputs every CHECK_EVERY ticks, on its own tick of the cycle
            boolean safety = Timers.due(full, s, p.getUUID());
            if (!safety && !Timers.due(CHECK_EVERY, s, p.getUUID())) continue;
            PlayerMusic m = PLAYERS.computeIfAbsent(p.getUUID(), u -> new PlayerMusic());
            if (refreshCache(p, m, safety)) sendState(p, m, cachedState(m), false);
        }
        if (timer % 200 == 0) {
            Set<UUID> online = new HashSet<>();
            for (ServerPlayer p : s.getPlayerList().getPlayers()) online.add(p.getUUID());
            PLAYERS.keySet().removeIf(u -> !online.contains(u));
        }
    }

    private static int musicCommand(CommandContext<CommandSourceStack> c) {
        if (!(c.getSource().getEntity() instanceof ServerPlayer p)) {
            c.getSource().sendFailure(Component.literal("Run this as a player."));
            return 0;
        }
        Choice choice = choiceFor(p);
        MusicState s = stateFor(p);
        String line = choice == null ? "No rule matches here; vanilla music plays."
            : "Playing " + choice.sound() + " (" + (choice.boss() ? "boss " : "rule ") + choice.source() + ", priority " + choice.priority() + ")";
        c.getSource().sendSuccess(() -> Component.literal(line), false);
        c.getSource().sendSuccess(() -> Component.literal(rules.size() + " rules, combat " + (s.combat() ? "yes" : "no")
            + (s.structures().isEmpty() ? "" : ", in " + String.join(", ", s.structures()))), false);
        return choice == null ? 0 : 1;
    }

    /** Sorts choices the way {@link #choose} breaks ties; used by tests and tools. */
    public static final Comparator<MusicRule> ORDER = Comparator.comparingInt(MusicRule::priority).reversed()
        .thenComparing(MusicRule::id);

    static FId typeOf(LivingEntity boss) {
        return boss == null ? null : FVanilla.idOf(boss.getType());
    }
}
