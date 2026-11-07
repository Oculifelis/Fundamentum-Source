package com.fundamentum.moon;

import com.fundamentum.data.Unknown;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * Special moons, shared by every mod (Terraria's Blood Moon kind of night): their data ({@link Moon}), tonight's moon
 * (rolled at overworld dusk, or called by an item, by {@link #raise} or by {@code /fundamentum moon}), what it does
 * while it shines (effects, spawns, bonus loot, fog on the clients) and the moonrise and moonset listeners. Tonight's
 * moon is kept with the overworld and shines on its own dimension only, while it is night there.
 *
 * <p>Mods hook in from code: {@link #addListener} (rise, set, every two seconds while up, kills under it),
 * {@link #registerFlag} (world flags data can require), {@link #registerChance} (scale or switch off a moon's chance,
 * e.g. from a config), {@link #registerSpawner} (place a moon's creatures their own way) and {@link #registerNight}
 * (dimensions that are always night). Server side, except {@link MoonsClient}.
 */
public final class Moons {
    /** Moonrise, moonset and what happens under a moon. Runs on the server thread. */
    public interface Listener {
        /** The moon now shines on this level. */
        default void onRise(ServerLevel level, Moon moon) {}

        /** It set (dawn, cleared or replaced). */
        default void onSet(ServerLevel level, Moon moon) {}

        /** Every two seconds while it shines on this level. */
        default void whileUp(ServerLevel level, Moon moon) {}

        /** A player killed a hostile creature under it. */
        default void onKill(ServerLevel level, ServerPlayer player, LivingEntity dead, Moon moon) {}
    }

    /** Scales a moon's chance tonight (return {@code chance} unchanged for moons that are not yours). */
    public interface Chance {
        double apply(ServerLevel level, Moon moon, double chance);
    }

    /** Brings a moon's creature down near a player; true when it handled it (spawned or chose not to). */
    public interface Spawner {
        boolean spawn(ServerLevel level, ServerPlayer near, EntityType<?> type, Moon moon);
    }

    /** Says why a moon is switched off (e.g. by a config), or null when it may rise. Commands respect it too. */
    public interface Switch {
        @Nullable Component off(ServerLevel level, Moon moon);
    }

    public static final DataTable<JsonObject> MOONS = DataTables.registerJson(Identifier.fromNamespaceAndPath("fundamentum", "moons"), "fundamentum/moons")
        .onLoad(t -> load(t.rawEntries()));

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    /** Other mods' listeners run one at a time: one that throws is logged against its mod and cannot stop the tick. */
    private static final ListenerGuard<Listener> GUARD = new ListenerGuard<>("moon", LISTENERS, Owner::of,
        (message, cause) -> com.mojang.logging.LogUtils.getLogger().error(message, cause));
    private static final List<Chance> CHANCES = new CopyOnWriteArrayList<>();
    private static final List<Switch> SWITCHES = new CopyOnWriteArrayList<>();
    private static final List<Spawner> SPAWNERS = new CopyOnWriteArrayList<>();
    private static final List<Predicate<Level>> NIGHTS = new CopyOnWriteArrayList<>();
    private static final Map<String, Predicate<ServerLevel>> FLAGS = new ConcurrentHashMap<>();
    /** Per dimension, the moon last seen shining there (for moonrise and moonset). */
    private static final Map<String, String> SEEN = new HashMap<>();
    /** What each client was last told, so it is only told when the moon over it changes. */
    private static final Map<UUID, String> TOLD = new HashMap<>();
    private static Map<String, Moon> byId = Map.of();
    private static Map<String, String> bySummonItem = Map.of();

    /**
     * A listener that throws is logged as an ERROR naming its mod and the moon, the others still run, and after
     * three failures it is dropped until the game restarts.
     */
    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** A world flag moon data can name in "requires", e.g. {@code "mymod:dragon_slain"}. */
    public static void registerFlag(String id, Predicate<ServerLevel> test) {
        FLAGS.put(id, test);
    }

    public static void registerChance(Chance chance) {
        CHANCES.add(chance);
    }

    /**
     * A switch that keeps a moon from rising at all, by chance or by {@code /fundamentum moon start} (its reason is
     * printed). For a config off switch; register a {@link Chance} too if it should not roll either.
     */
    public static void registerSwitch(Switch sw) {
        SWITCHES.add(sw);
    }

    /** Why this moon is switched off, or empty when nothing keeps it from rising. */
    public static Optional<Component> switchedOff(ServerLevel level, Moon moon) {
        for (Switch sw : SWITCHES) {
            Component why = sw.off(level, moon);
            if (why != null) {
                return Optional.of(why);
            }
        }
        return Optional.empty();
    }

    public static void registerSpawner(Spawner spawner) {
        SPAWNERS.add(spawner);
    }

    /** Levels where it is always night (moons shine there whatever the time). */
    public static void registerNight(Predicate<Level> night) {
        NIGHTS.add(night);
    }

    // ------------------------------------------------------------------------------------------------ data
    private static void load(Map<Identifier, JsonObject> files) {
        Map<String, Moon> map = new LinkedHashMap<>();
        Map<String, String> items = new HashMap<>();
        files.forEach((id, json) -> {
            try {
                Moon m = Moon.parse(id.toString(), json);
                map.put(m.id(), m);
                if (!m.summonItem().isEmpty()) {
                    items.put(m.summonItem(), m.id());
                }
            } catch (Exception e) {
                com.mojang.logging.LogUtils.getLogger().error("Fundamentum: bad moon {}: {}", id, e.getMessage());
            }
        });
        byId = map;
        bySummonItem = items;
    }

    public static Collection<Moon> all() {
        return byId.values();
    }

    public static Optional<Moon> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The moon this item calls, if any. */
    public static Optional<Moon> summonedBy(ItemStack stack) {
        String id = stack.isEmpty() ? null : bySummonItem.get(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        return id == null ? Optional.empty() : get(id);
    }

    /** Whether a world flag is set (unknown flags are not). */
    public static boolean flag(ServerLevel level, String id) {
        Predicate<ServerLevel> f = FLAGS.get(id);
        return f != null && f.test(level);
    }

    // ------------------------------------------------------------------------------------------------ tonight's moon
    public static long dayTime(ServerLevel level) {
        return level.getDefaultClockTime();
    }

    /** Vanilla's moon phase: 0 is the full moon. */
    public static int moonPhase(long dayTime) {
        return (int) Math.floorMod(dayTime / 24000L, 8L);
    }

    /** The night a day time belongs to (night 0 is the first day's). */
    public static long night(long dayTime) {
        return Math.floorDiv(dayTime, 24000L);
    }

    /** Tonight's night number, by the overworld clock. */
    public static long tonight(ServerLevel level) {
        return night(dayTime(level.getServer().overworld()));
    }

    /** Whether it is night on this level, for moons. */
    public static boolean isNight(Level level) {
        for (Predicate<Level> n : NIGHTS) {
            if (n.test(level)) {
                return true;
            }
        }
        return level.isDarkOutside();
    }

    /** The moon shining over this level now: tonight's moon, if it belongs to this dimension and it is night here. */
    public static Optional<Moon> current(Level level) {
        if (!(level instanceof ServerLevel sl)) {
            return Optional.empty();
        }
        Data d = data(sl);
        if (d.moon.isEmpty() || d.night != tonight(sl)) {
            return Optional.empty();
        }
        Moon m = byId.get(d.moon);
        if (m == null || !m.dimension().equals(sl.dimension().identifier().toString()) || !isNight(sl)) {
            return Optional.empty();
        }
        return Optional.of(m);
    }

    /** Tonight's moon by id, whether or not it shines yet ("" for none): what night events are rolled against. */
    public static String tonightsMoon(ServerLevel level) {
        Data d = data(level);
        return d.night == tonight(level) ? d.moon : "";
    }

    public static boolean isUp(Level level, String id) {
        return current(level).map(m -> m.id().equals(id)).orElse(false);
    }

    /** Makes {@code id} tonight's moon (replacing any other). False if no such moon is loaded. */
    public static boolean raise(ServerLevel level, String id) {
        if (!byId.containsKey(id)) {
            return false;
        }
        Data d = data(level);
        d.moon = id;
        d.night = tonight(level);
        d.setDirty();
        update(level.getServer());
        return true;
    }

    /** No moon tonight. */
    public static void clear(ServerLevel level) {
        Data d = data(level);
        d.moon = "";
        d.night = -1;
        d.setDirty();
        update(level.getServer());
    }

    /** A moon's chance tonight: its data, through every registered {@link Chance}, at most 1. */
    public static double chanceTonight(ServerLevel level, Moon m) {
        double c = m.chance();
        for (Chance ch : CHANCES) {
            c = ch.apply(level, m, c);
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
        Optional<Moon> moon = current(level);
        if (moon.isEmpty()) {
            return;
        }
        Moon m = moon.get();
        if (t % 40 == 0) {
            shine(level, m);
            GUARD.fire("while-up", m.id(), li -> li.whileUp(level, m));
        }
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            return;
        }
        for (Moon.Spawn s : m.spawns()) {
            if (t % s.interval() != 0) {
                continue;
            }
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(s.entity())).orElse(null);
            if (type == null) {
                Unknown.warn("entity", s.entity(), "moon " + m.id());
                continue;
            }
            for (ServerPlayer p : level.players()) {
                if (p.isSpectator()) {
                    continue;
                }
                int near = level.getEntities(type, p.getBoundingBox().inflate(48.0), e -> true).size();
                if (near < s.cap() && level.getRandom().nextFloat() < s.chance()) {
                    descend(level, p, type, m);
                }
            }
        }
    }

    /** The moon's effects on every player in its level (refreshed every two seconds while it shines). */
    private static void shine(ServerLevel level, Moon m) {
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || !p.isAlive()) {
                continue;
            }
            for (Moon.Effect e : m.effects()) {
                BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(e.effect())).ifPresentOrElse(
                    h -> p.addEffect(new MobEffectInstance(h, 100, e.amplifier(), true, false, true)),
                    () -> Unknown.warn("effect", e.effect(), "moon " + m.id()));
            }
        }
    }

    /** One of the moon's creatures comes down near a player: a registered spawner's way, or on the ground 20-36 blocks off. */
    private static void descend(ServerLevel level, ServerPlayer p, EntityType<?> type, Moon m) {
        for (Spawner s : SPAWNERS) {
            if (s.spawn(level, p, type, m)) {
                return;
            }
        }
        var random = level.getRandom();
        float angle = random.nextFloat() * Mth.TWO_PI;
        int dist = 20 + random.nextInt(17);
        int x = Mth.floor(p.getX() + Mth.cos(angle) * dist), z = Mth.floor(p.getZ() + Mth.sin(angle) * dist);
        if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
            return;
        }
        BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        type.spawn(level, at, EntitySpawnReason.EVENT);
    }

    /** At dusk tonight's moon is decided, once per night. */
    private static void roll(ServerLevel level) {
        long dayTime = dayTime(level);
        int tod = (int) Math.floorMod(dayTime, 24000L);
        long night = night(dayTime);
        Data d = data(level);
        if (tod < 13000 || tod >= 14000 || d.lastRoll == night) {
            return;
        }
        d.lastRoll = night;
        d.setDirty();
        if (d.night == night && !d.moon.isEmpty() || NightEvents.holdsMoonsOff(level)) {
            return;
        }
        Moon m = Moon.roll(List.copyOf(byId.values()), Moon.OVERWORLD, f -> flag(level, f), moonPhase(dayTime), def -> chanceTonight(level, def),
            level.getRandom()::nextDouble);
        if (m != null) {
            d.moon = m.id();
            d.night = night;
        }
    }

    /** Moonrise and moonset in every dimension: announce, tell the listeners and the clients. */
    private static void update(MinecraftServer server) {
        Data d = data(server.overworld());
        for (ServerLevel l : server.getAllLevels()) {
            String dim = l.dimension().identifier().toString();
            Optional<Moon> cur = current(l);
            String now = cur.map(Moon::id).orElse("");
            String was = SEEN.getOrDefault(dim, "");
            if (!now.equals(was)) {
                SEEN.put(dim, now);
                Moon old = byId.get(was);
                if (old != null) {
                    for (ServerPlayer p : l.players()) {
                        say(p, old, old.set());
                    }
                    GUARD.fire("set", old.id(), li -> li.onSet(l, old));
                }
                if (cur.isPresent()) {
                    Moon m = cur.get();
                    if (d.announced != d.night) {
                        d.announced = d.night;
                        d.setDirty();
                        for (ServerPlayer p : l.players()) {
                            say(p, m, m.rise());
                            hear(p, m.riseSound());
                        }
                    }
                    GUARD.fire("rise", m.id(), li -> li.onRise(l, m));
                }
            }
            for (ServerPlayer p : l.players()) {
                tell(p, cur.orElse(null));
            }
        }
    }

    private static void tell(ServerPlayer p, @Nullable Moon m) {
        String id = m == null ? "" : m.id();
        if (id.equals(TOLD.get(p.getUUID()))) {
            return;
        }
        TOLD.put(p.getUUID(), id);
        PacketDistributor.sendToPlayer(p, MoonPayload.of(m));
    }

    /** One of a moon's messages to a player (none when its key is ""). */
    private static void say(ServerPlayer p, Moon m, Moon.Message msg) {
        if (msg.isEmpty()) {
            return;
        }
        Component c = Component.translatable(msg.key()).withStyle(st -> st.withColor(m.color(msg)).withBold(msg.bold()).withItalic(msg.italic()));
        if (msg.overlay()) {
            p.sendOverlayMessage(c);
        } else {
            p.sendSystemMessage(c);
        }
    }

    /** A sound by id, where the player stands, for that player alone (none when its id is ""). */
    static void hear(ServerPlayer p, Moon.Sound sound) {
        if (sound.isEmpty()) {
            return;
        }
        p.connection.send(new ClientboundSoundPacket(Holder.direct(soundEvent(sound)), SoundSource.AMBIENT, p.getX(), p.getY(), p.getZ(),
            sound.volume(), sound.pitch(), p.getRandom().nextLong()));
    }

    private static SoundEvent soundEvent(Moon.Sound sound) {
        return SoundEvent.createVariableRangeEvent(Identifier.parse(sound.id()));
    }

    /** Join, respawn and dimension change: tell the client the moon over its new level. */
    private static void sync(PlayerEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            TOLD.remove(p.getUUID());
            tell(p, current(p.level()).orElse(null));
        }
    }

    // ------------------------------------------------------------------------------------------------ items and loot
    /** Using a moon's summon item at night, in its dimension, with no other moon up, calls it ({@link Moon.Summon}). */
    private static void use(PlayerInteractEvent.RightClickItem event) {
        Optional<Moon> moon = summonedBy(event.getItemStack());
        if (moon.isEmpty()) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (!(event.getEntity() instanceof ServerPlayer p)) {
            return;
        }
        Moon m = moon.get();
        ServerLevel level = p.level();
        String refusal = null;
        if (!m.dimension().equals(level.dimension().identifier().toString())) {
            refusal = "elsewhere";
        } else if (!isNight(level)) {
            refusal = "daylight";
        } else if (current(level).isPresent() || NightEvents.holdsMoonsOff(level)) {
            refusal = "already";
        }
        if (refusal != null) {
            p.sendOverlayMessage(Component.translatable(m.summon().refusal(refusal)).withStyle(ChatFormatting.GRAY));
            return;
        }
        raise(level, m.id());
        ItemStack stack = event.getItemStack();
        Moon.Summon summon = m.summon();
        if (summon.cooldown() > 0) {
            p.getCooldowns().addCooldown(stack, summon.cooldown());
        }
        if (summon.consume() > 0) {
            stack.consume(summon.consume(), p);
        }
        if (!summon.sound().isEmpty()) {
            level.playSound(null, p.blockPosition(), soundEvent(summon.sound()), SoundSource.PLAYERS, summon.sound().volume(), summon.sound().pitch());
        }
    }

    /** A player killed something hostile under a moon: the listeners hear of it, and its bonus loot is rolled. */
    private static void death(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        DamageSource source = event.getSource();
        if (!(dead.level() instanceof ServerLevel level) || !(dead instanceof Enemy) || !(source.getEntity() instanceof ServerPlayer p)) {
            return;
        }
        Optional<Moon> moon = current(level);
        if (moon.isEmpty()) {
            return;
        }
        GUARD.fire("kill", moon.get().id(), li -> li.onKill(level, p, dead, moon.get()));
        if (moon.get().bonusLoot().isEmpty()) {
            return;
        }
        LootTable table = level.getServer().reloadableRegistries().getLootTable(
            ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(moon.get().bonusLoot())));
        LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.THIS_ENTITY, dead)
            .withParameter(LootContextParams.ORIGIN, dead.position()).withParameter(LootContextParams.DAMAGE_SOURCE, source)
            .withOptionalParameter(LootContextParams.ATTACKING_ENTITY, p).withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, source.getDirectEntity())
            .withParameter(LootContextParams.LAST_DAMAGE_PLAYER, p).withLuck(p.getLuck()).create(LootContextParamSets.ENTITY);
        table.getRandomItems(params, s -> dead.spawnAtLocation(level, s));
    }

    // ------------------------------------------------------------------------------------------------ command
    /**
     * {@code /fundamentum moon start <moon or night event> | stop | list | raise <moon> | clear | query} (operators).
     * {@code start} takes any loaded moon or night event from any mod or datapack and makes it night first if it must;
     * {@code raise} only sets tonight's moon. Both respect a moon's {@link Switch} and say why it did not rise.
     */
    private static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fundamentum")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("moon")
                .then(Commands.literal("start").then(Commands.argument("id", StringArgumentType.greedyString())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(startable(), b))
                    .executes(c -> start(c.getSource(), StringArgumentType.getString(c, "id")))))
                .then(Commands.literal("stop").executes(c -> stop(c.getSource())))
                .then(Commands.literal("list").executes(c -> list(c.getSource())))
                .then(Commands.literal("raise").then(Commands.argument("id", StringArgumentType.greedyString())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(byId.keySet(), b))
                    .executes(Moons::raiseCommand)))
                .then(Commands.literal("clear").executes(c -> {
                    clear(c.getSource().getLevel());
                    c.getSource().sendSuccess(() -> Component.translatable("fundamentum.moon.cleared"), true);
                    return 1;
                }))
                .then(Commands.literal("query").executes(c -> {
                    String id = current(c.getSource().getLevel()).map(Moon::id).orElse("-");
                    c.getSource().sendSuccess(() -> Component.translatable("fundamentum.moon.query", id), false);
                    return 1;
                }))));
    }

    /** Every moon and night event {@code start} takes, moons first. */
    private static List<String> startable() {
        List<String> ids = new ArrayList<>(byId.keySet());
        NightEvents.all().forEach(e -> ids.add(e.id()));
        return ids;
    }

    /**
     * Starts a moon (in its own dimension) or a night event (where the command runs), making it night first when that
     * is the overworld's day. Returns 1 when it started, 0 (with the reason printed) when it did not.
     */
    public static int start(CommandSourceStack source, String id) {
        MinecraftServer server = source.getServer();
        Moon moon = byId.get(id);
        if (moon == null) {
            if (NightEvents.get(id).isEmpty()) {
                source.sendFailure(Component.translatable("fundamentum.moon.unknown_any", id));
                return 0;
            }
            ServerLevel level = source.getLevel();
            makeNight(source, level);
            if (!NightEvents.raise(level, id)) {
                source.sendFailure(Component.translatable("fundamentum.event.unknown", id));
                return 0;
            }
            source.sendSuccess(() -> Component.translatable("fundamentum.event.raised", id), true);
            return 1;
        }
        ServerLevel home = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(moon.dimension())));
        if (home == null) {
            source.sendFailure(Component.translatable("fundamentum.moon.no_dimension", id, moon.dimension()));
            return 0;
        }
        Optional<Component> off = switchedOff(home, moon);
        if (off.isPresent()) {
            source.sendFailure(Component.translatable("fundamentum.moon.switched_off", id, off.get()));
            return 0;
        }
        makeNight(source, home);
        raise(home, id);
        source.sendSuccess(() -> Component.translatable("fundamentum.moon.started", id, moon.dimension()), true);
        return 1;
    }

    /** Ends tonight's moon and the night event where the command runs. */
    public static int stop(CommandSourceStack source) {
        clear(source.getLevel());
        NightEvents.clear(source.getLevel());
        source.sendSuccess(() -> Component.translatable("fundamentum.moon.stopped"), true);
        return 1;
    }

    /** Lists every loaded moon and night event, marking the ones up here now and the ones switched off. */
    public static int list(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        source.sendSuccess(() -> Component.translatable("fundamentum.moon.list_moons", byId.size()), false);
        for (Moon m : byId.values()) {
            ServerLevel home = source.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(m.dimension())));
            String mark = isUp(level, m.id()) ? " [up]" : home != null && switchedOff(home, m).isPresent() ? " [off]" : "";
            source.sendSuccess(() -> Component.literal("  " + m.id() + " (" + m.dimension() + ")" + mark), false);
        }
        source.sendSuccess(() -> Component.translatable("fundamentum.moon.list_events", NightEvents.all().size()), false);
        for (NightEvent e : NightEvents.all()) {
            String mark = NightEvents.isUp(level, e.id()) ? " [up]" : "";
            source.sendSuccess(() -> Component.literal("  " + e.id() + " (" + e.dimension() + ")" + mark), false);
        }
        return byId.size() + NightEvents.all().size();
    }

    /** "time set midnight" when it is day in the overworld and {@code level} keeps the overworld's clock. */
    private static void makeNight(CommandSourceStack source, ServerLevel level) {
        if (!isNight(level) && level.dimension() == Level.OVERWORLD) {
            source.getServer().getCommands().performPrefixedCommand(source.withSuppressedOutput(), "time set midnight");
        }
    }

    private static int raiseCommand(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "id");
        Moon moon = byId.get(id);
        Optional<Component> off = moon == null ? Optional.empty() : switchedOff(c.getSource().getLevel(), moon);
        if (off.isPresent()) {
            c.getSource().sendFailure(Component.translatable("fundamentum.moon.switched_off", id, off.get()));
            return 0;
        }
        if (!raise(c.getSource().getLevel(), id)) {
            c.getSource().sendFailure(Component.translatable("fundamentum.moon.unknown", id));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.translatable("fundamentum.moon.raised", id), true);
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ saved state
    private static Data data(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(Data.TYPE);
    }

    /** Tonight's moon, the night it belongs to, the last night rolled and the last night announced; kept with the overworld. */
    static final class Data extends SavedData {
        static final SavedDataType<Data> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath("fundamentum", "moons"), Data::new,
            CompoundTag.CODEC.xmap(Data::load, d -> d.save(new CompoundTag())));

        String moon = "";
        long night = -1;
        long lastRoll = -1;
        long announced = -1;

        static Data load(CompoundTag tag) {
            Data d = new Data();
            d.moon = tag.getStringOr("moon", "");
            d.night = tag.getLongOr("night", -1L);
            d.lastRoll = tag.getLongOr("last_roll", -1L);
            d.announced = tag.getLongOr("announced", -1L);
            return d;
        }

        CompoundTag save(CompoundTag tag) {
            tag.putString("moon", this.moon);
            tag.putLong("night", this.night);
            tag.putLong("last_roll", this.lastRoll);
            tag.putLong("announced", this.announced);
            return tag;
        }
    }

    // ------------------------------------------------------------------------------------------------ network
    /** The moon over a client's level: its id ("" for none) and its fog and sky tints. */
    public record MoonPayload(String moon, Moon.Tint fog, Moon.Tint sky) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<MoonPayload> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("fundamentum", "moon"));
        public static final StreamCodec<FriendlyByteBuf, MoonPayload> STREAM_CODEC = StreamCodec.ofMember(MoonPayload::encode, MoonPayload::decode);

        static MoonPayload of(@Nullable Moon m) {
            return m == null ? new MoonPayload("", Moon.Tint.NONE, Moon.Tint.NONE) : new MoonPayload(m.id(), m.fog(), m.sky());
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(this.moon, 256);
            this.fog.write(buf);
            this.sky.write(buf);
        }

        static MoonPayload decode(FriendlyByteBuf buf) {
            return new MoonPayload(buf.readUtf(256), Moon.Tint.read(buf), Moon.Tint.read(buf));
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void init(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("4").optional()
            .playToClient(MoonPayload.TYPE, MoonPayload.STREAM_CODEC, (payload, context) -> MoonsClient.set(payload.moon(), payload.fog(), payload.sky())));
        NeoForge.EVENT_BUS.addListener(Moons::tick);
        NeoForge.EVENT_BUS.addListener(Moons::use);
        NeoForge.EVENT_BUS.addListener(Moons::death);
        NeoForge.EVENT_BUS.addListener(Moons::commands);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> sync(e));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent e) -> sync(e));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> sync(e));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> TOLD.remove(e.getEntity().getUUID()));
    }

    private Moons() {}
}
