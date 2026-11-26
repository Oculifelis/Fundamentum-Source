package com.fundamentum.religion;

import com.fundamentum.condition.Conditions;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.faction.FactionDef;
import com.fundamentum.faction.Factions;
import com.fundamentum.faction.Rewards;
import com.fundamentum.mana.Mana;
import com.fundamentum.mana.ManaSource;
import com.fundamentum.moon.Moons;
import com.fundamentum.multiblock.Multiblocks;
import com.fundamentum.port.FAttach;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FTags;
import com.fundamentum.port.FVanilla;
import com.fundamentum.quest.Quests;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;

/**
 * Religion: gods are factions with {@code "kind": "deity"} (see {@link Deity}), and devotion is their rep.
 * <ul>
 *   <li>Pantheons ({@code data/<ns>/fundamentum/pantheons/<path>.json}) group gods:
 *       {@code { "name": "...", "gods": ["mymod:sun", "mymod:moon"], "exclusive": true }}. In an exclusive pantheon a
 *       player follows one god at a time.</li>
 *   <li>Shrines ({@code fundamentum/shrines}): {@code { "deity": "mymod:sun", "blocks": ["minecraft:gold_block"] }} for a
 *       one-block shrine, or {@code "multiblock": "<pattern id>"} for a built one (pray at its controller). Optional
 *       {@code "power": 1.0}, {@code "nearby": { "blocks": [...], "radius": 4, "per": 0.1, "max": 1.0 }} (each matching
 *       block nearby adds {@code per} to the power, up to {@code max}) and {@code "desecrate": 50} (devotion lost for
 *       breaking it). Using a shrine with an empty hand prays; using it with an offering gives that item.</li>
 *   <li>Rituals ({@code fundamentum/rituals}): {@code { "deity": "...", "shrine": "<shrine id>", "items": [ { "item": "...",
 *       "count": 1 } ], "time": "night", "moon": [0], "min_rank": "...", "requires": [conditions], "cooldown": 24000,
 *       "devotion": 20, "favour": 50, "rewards": { ... } }}. Praying at the shrine while carrying the items performs
 *       the first ritual that fits instead of a plain prayer, and takes the items.</li>
 *   <li>Favour is a mana pool ({@code fundamentum:favour}, priority -1000, so a mana mod's own pool wins) holding the
 *       player's favour with their first patron god. Boons spend it.</li>
 * </ul>
 */
public final class Religion {
    private Religion() { }

    public record Pantheon(FId id, String name, List<FId> gods, boolean exclusive) { }

    public record Shrine(FId id, FId deity, List<String> blocks, FId multiblock, double power, List<String> nearby,
                         int radius, double per, double maxBonus, int desecrate) { }

    public record Ritual(FId id, FId deity, FId shrine, List<JsonObject> items, String time, List<Integer> moon,
                         String minRank, JsonElement requires, int cooldown, int devotion, double favour, JsonElement rewards) { }

    /** A player's patron gods, favour per god and cooldowns ("pray|god", "boon|god|boon", "ritual|id", "wrath|god"). */
    public record State(List<String> patrons, Map<String, Double> favour, Map<String, Long> cooldowns) {
        static final State EMPTY = new State(List.of(), Map.of(), Map.of());
        public static final Codec<State> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.listOf().optionalFieldOf("patrons", List.of()).forGetter(State::patrons),
            Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("favour", Map.of()).forGetter(State::favour),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("cooldowns", Map.of()).forGetter(State::cooldowns)
        ).apply(i, State::new));

        State withPatrons(List<String> p) {
            return new State(List.copyOf(p), favour, cooldowns);
        }

        State withFavour(String god, double v) {
            Map<String, Double> m = new HashMap<>(favour);
            m.put(god, v);
            return new State(patrons, m, cooldowns);
        }

        State withCooldown(String key, long until) {
            Map<String, Long> m = new HashMap<>(cooldowns);
            m.put(key, until);
            return new State(patrons, favour, m);
        }
    }

    public static final FAttach<State> PLAYER = FAttach.builder(FId.of("fundamentum", "religion"), () -> State.EMPTY)
        .codec(State.CODEC).copyOnDeath().build();

    public static final ResourceLocation FAVOUR = new ResourceLocation("fundamentum", "favour");

    private static volatile Map<FId, Pantheon> pantheons = Map.of();
    private static volatile Map<FId, Shrine> shrines = Map.of();
    private static volatile Map<FId, Ritual> rituals = Map.of();
    private static volatile Map<FId, Deity> deityCache = Map.of();
    private static volatile Map<FId, FactionDef> deitySource = Map.of();
    /** The player placing a block while shrine multiblocks are checked, for {@code shrine_built}. */
    private static final ThreadLocal<ServerPlayer> PLACER = new ThreadLocal<>();

    private static boolean initialised;

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        FLife.jsonData(FId.of("fundamentum", "pantheons"), "fundamentum/pantheons", Religion::loadPantheons);
        FLife.jsonData(FId.of("fundamentum", "shrines"), "fundamentum/shrines", Religion::loadShrines);
        FLife.jsonData(FId.of("fundamentum", "rituals"), "fundamentum/rituals", Religion::loadRituals);
        FLife.serverTick(Religion::tick);
        MinecraftForge.EVENT_BUS.addListener(Religion::onBlockUse);
        MinecraftForge.EVENT_BUS.addListener(Religion::onBreak);
        MinecraftForge.EVENT_BUS.addListener(Religion::onPlace);
        MinecraftForge.EVENT_BUS.addListener(Religion::onDeath);
        FEvents.listen(Events.MultiblockFormed.class, Religion::onFormed);
        Mana.register(new FavourSource());
        Miracles.init();
        registerConditions();
        registerObjectives();
        ReligionCommand.init();
    }

    // ---- data

    private static JsonObject top(List<JsonElement> list) {
        JsonElement t = list.get(list.size() - 1);
        return t.isJsonObject() ? t.getAsJsonObject() : null;
    }

    private static void loadPantheons(Map<FId, List<JsonElement>> map) {
        Map<FId, Pantheon> out = new LinkedHashMap<>();
        map.forEach((id, list) -> {
            JsonObject o = top(list);
            if (o == null || !Deity.bool(o, "enabled", true)) return;
            List<FId> gods = new ArrayList<>();
            for (String g : Deity.strings(o.get("gods"))) {
                FId f = FId.tryOf(g);
                if (f != null) gods.add(f);
            }
            out.put(id, new Pantheon(id, Deity.s(o, "name", id.toString()), List.copyOf(gods), Deity.bool(o, "exclusive", false)));
        });
        pantheons = Map.copyOf(out);
    }

    private static void loadShrines(Map<FId, List<JsonElement>> map) {
        Map<FId, Shrine> out = new LinkedHashMap<>();
        map.forEach((id, list) -> {
            JsonObject o = top(list);
            if (o == null || !Deity.bool(o, "enabled", true)) return;
            FId deity = Deity.id(o, "deity");
            if (deity == null) return;
            JsonObject n = Deity.obj(o, "nearby");
            out.put(id, new Shrine(id, deity, Deity.strings(o.get("blocks")), Deity.id(o, "multiblock"),
                Math.max(0, Deity.d(o, "power", 1)), Deity.strings(n.get("blocks")), Math.max(0, Math.min(8, Deity.i(n, "radius", 4))),
                Deity.d(n, "per", 0.1), Math.max(0, Deity.d(n, "max", 1)), Math.max(0, Deity.i(o, "desecrate", 50))));
        });
        shrines = Map.copyOf(out);
    }

    private static void loadRituals(Map<FId, List<JsonElement>> map) {
        Map<FId, Ritual> out = new LinkedHashMap<>();
        map.forEach((id, list) -> {
            JsonObject o = top(list);
            if (o == null || !Deity.bool(o, "enabled", true)) return;
            FId deity = Deity.id(o, "deity");
            if (deity == null) return;
            List<JsonObject> items = new ArrayList<>();
            if (o.has("items") && o.get("items").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("items")) {
                    if (e.isJsonObject() && e.getAsJsonObject().has("item")) items.add(e.getAsJsonObject());
                }
            }
            List<Integer> moon = new ArrayList<>();
            if (o.has("moon")) {
                for (String m : Deity.strings(o.get("moon"))) moon.add(Integer.parseInt(m));
            }
            out.put(id, new Ritual(id, deity, Deity.id(o, "shrine"), List.copyOf(items), Deity.s(o, "time", "any"),
                List.copyOf(moon), Deity.s(o, "min_rank", null), o.get("requires"), Math.max(0, Deity.i(o, "cooldown", 24000)),
                Deity.i(o, "devotion", 0), Math.max(0, Deity.d(o, "favour", 0)), o.get("rewards")));
        });
        rituals = Map.copyOf(out);
    }

    /** Every deity: the factions with {@code "kind": "deity"}. */
    public static Map<FId, Deity> deities() {
        Map<FId, FactionDef> all = Factions.all();
        if (all != deitySource) {
            Map<FId, Deity> out = new LinkedHashMap<>();
            for (FactionDef f : all.values()) {
                if (f.deity()) out.put(f.id(), Deity.parse(f));
            }
            deityCache = Map.copyOf(out);
            deitySource = all;
        }
        return deityCache;
    }

    public static Deity deity(FId id) {
        return id == null ? null : deities().get(id);
    }

    public static Map<FId, Pantheon> pantheons() {
        return pantheons;
    }

    public static Map<FId, Shrine> shrines() {
        return shrines;
    }

    public static Map<FId, Ritual> rituals() {
        return rituals;
    }

    /** The pantheons a god is in: those that list it, plus the one its own {@code pantheon} field names. */
    public static List<Pantheon> pantheonsOf(FId god) {
        List<Pantheon> out = new ArrayList<>();
        Deity d = deity(god);
        for (Pantheon p : pantheons.values()) {
            if (p.gods().contains(god) || d != null && p.id().equals(d.pantheon())) out.add(p);
        }
        return out;
    }

    // ---- devotion and following

    public static int devotion(ServerPlayer player, FId god) {
        return Factions.rep(player, god);
    }

    public static void addDevotion(ServerPlayer player, FId god, int delta) {
        Factions.addRep(player, god, delta);
    }

    static State state(ServerPlayer player) {
        return PLAYER.get(player);
    }

    public static List<FId> patrons(ServerPlayer player) {
        List<FId> out = new ArrayList<>();
        for (String s : state(player).patrons()) {
            FId f = FId.tryOf(s);
            if (f != null && deity(f) != null) out.add(f);
        }
        return out;
    }

    public static boolean follows(ServerPlayer player, FId god) {
        return state(player).patrons().contains(god.toString());
    }

    private static boolean rankAtLeast(ServerPlayer player, Deity d, String rank) {
        if (rank == null) return true;
        FactionDef.Rank need = d.faction().rank(rank);
        return need == null || devotion(player, d.id()) >= need.min();
    }

    /** Why a player cannot follow a god now, or null when they can. */
    public static String cannotFollow(ServerPlayer player, FId god) {
        Deity d = deity(god);
        if (d == null) return "unknown god";
        if (follows(player, god)) return "already follows";
        if (!rankAtLeast(player, d, d.followRank())) return "devotion too low";
        if (d.followRequires() != null && !Conditions.all(d.followRequires(), Conditions.Context.of(player))) return "requirements not met";
        for (Pantheon p : pantheonsOf(god)) {
            if (!p.exclusive()) continue;
            for (FId other : patrons(player)) {
                if (!other.equals(god) && pantheonsOf(other).contains(p)) return "already follows " + deity(other).displayName();
            }
        }
        return null;
    }

    public static String follow(ServerPlayer player, FId god) {
        String why = cannotFollow(player, god);
        if (why != null) return why;
        List<String> p = new ArrayList<>(state(player).patrons());
        p.add(god.toString());
        PLAYER.update(player, s -> s.withPatrons(p));
        return null;
    }

    /** Stops following a god; its {@code follow.forsake} devotion change applies. */
    public static boolean forsake(ServerPlayer player, FId god) {
        if (!follows(player, god)) return false;
        List<String> p = new ArrayList<>(state(player).patrons());
        p.remove(god.toString());
        PLAYER.update(player, s -> s.withPatrons(p));
        Deity d = deity(god);
        if (d != null && d.forsake() != 0) addDevotion(player, god, d.forsake());
        return true;
    }

    // ---- favour

    public static double favour(ServerPlayer player, FId god) {
        return state(player).favour().getOrDefault(god.toString(), 0.0);
    }

    public static double favourMax(FId god) {
        Deity d = deity(god);
        return d == null ? 0 : d.favourMax();
    }

    public static void setFavour(ServerPlayer player, FId god, double value) {
        double v = Math.max(0, Math.min(favourMax(god), value));
        PLAYER.update(player, s -> s.withFavour(god.toString(), v));
    }

    /** Adds favour up to the god's maximum; returns how much was added (negative when taken). */
    public static double addFavour(ServerPlayer player, FId god, double delta) {
        double from = favour(player, god);
        setFavour(player, god, from + delta);
        return favour(player, god) - from;
    }

    /** The patron god whose favour the mana pool shows, or null. */
    public static FId favourGod(ServerPlayer player) {
        for (FId g : patrons(player)) {
            if (favourMax(g) > 0) return g;
        }
        return null;
    }

    private static final class FavourSource implements ManaSource {
        @Override
        public ResourceLocation id() {
            return FAVOUR;
        }

        @Override
        public int priority() {
            return -1000;
        }

        @Override
        public boolean handles(LivingEntity e) {
            return e instanceof ServerPlayer p && favourGod(p) != null;
        }

        @Override
        public double get(LivingEntity e) {
            FId g = e instanceof ServerPlayer p ? favourGod(p) : null;
            return g == null ? 0 : favour((ServerPlayer) e, g);
        }

        @Override
        public double max(LivingEntity e) {
            FId g = e instanceof ServerPlayer p ? favourGod(p) : null;
            return g == null ? 0 : favourMax(g);
        }

        @Override
        public boolean spend(LivingEntity e, double amount) {
            FId g = e instanceof ServerPlayer p ? favourGod(p) : null;
            if (g == null || favour((ServerPlayer) e, g) < amount) return false;
            addFavour((ServerPlayer) e, g, -amount);
            return true;
        }

        @Override
        public double restore(LivingEntity e, double amount) {
            FId g = e instanceof ServerPlayer p ? favourGod(p) : null;
            return g == null ? 0 : addFavour((ServerPlayer) e, g, amount);
        }

        @Override
        public double regenPerTick(LivingEntity e) {
            FId g = e instanceof ServerPlayer p ? favourGod(p) : null;
            Deity d = deity(g);
            return d == null ? 0 : d.regen() / 1200.0;
        }
    }

    // ---- cooldowns

    private static long now(ServerPlayer p) {
        return p.level().getGameTime();
    }

    public static boolean cooling(ServerPlayer player, String key) {
        Long until = state(player).cooldowns().get(key);
        return until != null && until > now(player);
    }

    private static void cool(ServerPlayer player, String key, int ticks) {
        if (ticks > 0) PLAYER.update(player, s -> s.withCooldown(key, now(player) + ticks));
    }

    /** Clears one cooldown (operators and tests). */
    public static void clearCooldown(ServerPlayer player, String key) {
        PLAYER.update(player, s -> s.withCooldown(key, 0L));
    }

    // ---- shrines

    static boolean matches(String spec, BlockState state) {
        if (spec.startsWith("#")) {
            FId t = FId.tryOf(spec.substring(1));
            return t != null && state.is(FTags.block(t));
        }
        return FVanilla.idOf(state.getBlock()).toString().equals(spec);
    }

    static boolean matches(String spec, ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (spec.startsWith("#")) {
            FId t = FId.tryOf(spec.substring(1));
            return t != null && stack.is(FTags.item(t));
        }
        return FVanilla.idOf(stack.getItem()).toString().equals(spec);
    }

    /** The shrine at a block: a one-block shrine of that block, or a built multiblock shrine whose controller it is. */
    public static Shrine shrineAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        for (Shrine s : shrines.values()) {
            if (deity(s.deity()) == null) continue;
            if (s.multiblock() != null) {
                if (Multiblocks.match(level, pos, s.multiblock()) != null) return s;
            } else {
                for (String b : s.blocks()) {
                    if (matches(b, state)) return s;
                }
            }
        }
        return null;
    }

    /** A shrine's power at a spot: its base power plus the bonus from matching blocks nearby. */
    public static double power(ServerLevel level, BlockPos pos, Shrine s) {
        if (s.nearby().isEmpty() || s.radius() == 0 || s.per() == 0) return s.power();
        int found = 0;
        int r = s.radius();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    m.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
                    BlockState st = level.getBlockState(m);
                    for (String b : s.nearby()) {
                        if (matches(b, st)) {
                            found++;
                            break;
                        }
                    }
                }
            }
        }
        return s.power() * (1 + Math.min(s.maxBonus(), found * s.per()));
    }

    /**
     * Prays at a shrine: performs the first ritual that fits, or else gives the prayer's devotion and favour times the
     * shrine's power. Returns null on success, or why not.
     */
    public static String pray(ServerPlayer player, BlockPos pos) {
        ServerLevel level = (ServerLevel) player.level();
        Shrine s = shrineAt(level, pos);
        if (s == null) return "not a shrine";
        Deity d = deity(s.deity());
        Ritual r = ritualFor(player, s, pos);
        if (r != null) return perform(player, r, pos) ? null : "refused";
        String key = "pray|" + d.id();
        if (cooling(player, key)) return "prayed too recently";
        if (FEvents.post(new Events.Prayed(player, d.id(), pos))) return "refused";
        double power = power(level, pos, s);
        cool(player, key, d.prayCooldown());
        int dev = (int) Math.round(d.prayDevotion() * power);
        if (dev != 0) addDevotion(player, d.id(), dev);
        addFavour(player, d.id(), d.prayFavour() * power);
        Quests.signal(player, "pray", d.id(), 1);
        player.sendSystemMessage(Component.literal("You pray to " + d.displayName() + ".").withStyle(ChatFormatting.GOLD));
        return null;
    }

    /** Offers the stack in hand at a shrine. Returns null when one item was taken, or why not. */
    public static String offer(ServerPlayer player, BlockPos pos, ItemStack stack) {
        ServerLevel level = (ServerLevel) player.level();
        Shrine s = shrineAt(level, pos);
        if (s == null) return "not a shrine";
        Deity d = deity(s.deity());
        Deity.Offering o = null;
        for (Deity.Offering x : d.offerings()) {
            if (matches(x.item(), stack)) {
                o = x;
                break;
            }
        }
        if (o == null) return "not wanted";
        double power = power(level, pos, s);
        stack.shrink(1);
        int dev = (int) Math.round(o.devotion() * power);
        if (dev != 0) addDevotion(player, d.id(), dev);
        if (o.favour() > 0) addFavour(player, d.id(), o.favour() * power);
        Quests.signal(player, "offer", d.id(), 1);
        player.sendSystemMessage(Component.literal(d.displayName() + " accepts the offering.").withStyle(ChatFormatting.GOLD));
        return null;
    }

    private static void onBlockUse(PlayerInteractEvent.RightClickBlock e) {
        if (e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p) || p.isShiftKeyDown()) return;
        if (shrines.isEmpty()) return;
        ServerLevel level = (ServerLevel) p.level();
        Shrine s = shrineAt(level, e.getPos());
        if (s == null) return;
        ItemStack held = p.getMainHandItem();
        String why;
        if (held.isEmpty()) {
            why = pray(p, e.getPos());
        } else {
            why = offer(p, e.getPos(), held);
            if ("not wanted".equals(why)) return; // let the item be used as normal
        }
        if (why != null && !"refused".equals(why)) p.sendSystemMessage(Component.literal(why).withStyle(ChatFormatting.GRAY));
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
    }

    private static void onBreak(BlockEvent.BreakEvent e) {
        if (!(e.getPlayer() instanceof ServerPlayer p) || shrines.isEmpty()) return;
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        Shrine s = shrineAt(level, e.getPos());
        if (s == null || s.desecrate() == 0) return;
        addDevotion(p, s.deity(), -s.desecrate());
        Deity d = deity(s.deity());
        if (d != null) p.sendSystemMessage(Component.literal(d.displayName() + " is angered.").withStyle(ChatFormatting.DARK_RED));
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel level) || shrines.isEmpty()) return;
        BlockPos pos = e.getPos();
        BlockState placed = e.getPlacedBlock();
        for (Shrine s : shrines.values()) {
            if (deity(s.deity()) == null) continue;
            if (s.multiblock() == null) {
                for (String b : s.blocks()) {
                    if (matches(b, placed)) {
                        FEvents.post(new Events.ShrineBuilt(p, s.id(), s.deity(), pos.immutable()));
                        return;
                    }
                }
            }
        }
        // A built shrine counts once its controller is matched; check the placed spot and its neighbours.
        PLACER.set(p);
        try {
            for (BlockPos c : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) {
                for (Shrine s : shrines.values()) {
                    if (s.multiblock() != null && Multiblocks.formed(level, c) == null && Multiblocks.match(level, c, s.multiblock()) != null) {
                        Multiblocks.check(level, c.immutable());
                    }
                }
            }
        } finally {
            PLACER.remove();
        }
    }

    private static void onFormed(Events.MultiblockFormed e) {
        for (Shrine s : shrines.values()) {
            if (e.structure().equals(s.multiblock())) {
                FEvents.post(new Events.ShrineBuilt(PLACER.get(), s.id(), s.deity(), e.controller()));
                return;
            }
        }
    }

    // ---- rituals

    /** The first ritual a player can perform at this shrine now, or null. */
    public static Ritual ritualFor(ServerPlayer player, Shrine s, BlockPos pos) {
        for (Ritual r : rituals.values()) {
            if (r.shrine() != null && !r.shrine().equals(s.id())) continue;
            if (!r.deity().equals(s.deity())) continue;
            if (cannotPerform(player, r) == null) return r;
        }
        return null;
    }

    /** Why a player cannot perform a ritual now (ignoring where), or null. */
    public static String cannotPerform(ServerPlayer player, Ritual r) {
        Deity d = deity(r.deity());
        if (d == null) return "unknown god";
        if (cooling(player, "ritual|" + r.id())) return "performed too recently";
        if (!rankAtLeast(player, d, r.minRank())) return "devotion too low";
        ServerLevel level = (ServerLevel) player.level();
        boolean night = Moons.isNight(level);
        if ("night".equals(r.time()) && !night || "day".equals(r.time()) && night) return "wrong time";
        if (!r.moon().isEmpty() && !r.moon().contains(Moons.moonPhase(Moons.dayTime(level.getServer().overworld())))) return "wrong moon";
        if (r.requires() != null && !Conditions.all(r.requires(), Conditions.Context.of(player))) return "requirements not met";
        for (JsonObject i : r.items()) {
            if (count(player, i.get("item").getAsString()) < Math.max(1, Deity.i(i, "count", 1))) return "missing items";
        }
        return null;
    }

    private static int count(ServerPlayer player, String spec) {
        int n = 0;
        for (ItemStack st : player.getInventory().items) {
            if (matches(spec, st)) n += st.getCount();
        }
        return n;
    }

    private static void take(ServerPlayer player, String spec, int count) {
        for (ItemStack st : player.getInventory().items) {
            if (count <= 0) return;
            if (!matches(spec, st)) continue;
            int t = Math.min(count, st.getCount());
            st.shrink(t);
            count -= t;
        }
    }

    /** Performs a ritual: takes its items and gives its devotion, favour and rewards. False when refused. */
    public static boolean perform(ServerPlayer player, Ritual r, BlockPos pos) {
        if (FEvents.post(new Events.RitualStarted(player, r.id(), pos))) return false;
        for (JsonObject i : r.items()) take(player, i.get("item").getAsString(), Math.max(1, Deity.i(i, "count", 1)));
        cool(player, "ritual|" + r.id(), r.cooldown());
        if (r.devotion() != 0) addDevotion(player, r.deity(), r.devotion());
        if (r.favour() > 0) addFavour(player, r.deity(), r.favour());
        Rewards.give(player, r.rewards());
        Quests.signal(player, "ritual", r.id(), 1);
        Quests.signal(player, "pray", r.deity(), 1);
        FEvents.post(new Events.RitualFinished(player, r.id(), pos, true));
        Deity d = deity(r.deity());
        player.sendSystemMessage(Component.literal("The ritual is complete. " + d.displayName() + " is pleased.").withStyle(ChatFormatting.GOLD));
        return true;
    }

    // ---- boons

    /** Why a player cannot be granted a boon now, or null. */
    public static String cannotBoon(ServerPlayer player, FId god, String boon) {
        Deity d = deity(god);
        if (d == null) return "unknown god";
        Deity.Boon b = d.boons().get(boon);
        if (b == null) return "unknown boon";
        if (!follows(player, god)) return "not a follower";
        if (!rankAtLeast(player, d, b.minRank())) return "devotion too low";
        if (cooling(player, "boon|" + god + "|" + boon)) return "not ready";
        if (favour(player, god) < b.cost()) return "not enough favour";
        return null;
    }

    /** Spends favour on a boon and gives it. Returns null on success, or why not. */
    public static String boon(ServerPlayer player, FId god, String boon) {
        String why = cannotBoon(player, god, boon);
        if (why != null) return why;
        Deity.Boon b = deity(god).boons().get(boon);
        if (FEvents.post(new Events.BoonGranted(player, god, boon))) return "refused";
        addFavour(player, god, -b.cost());
        cool(player, "boon|" + god + "|" + boon, b.cooldown());
        Rewards.give(player, b.rewards());
        return null;
    }

    private static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        for (FId god : patrons(p)) {
            Deity d = deity(god);
            for (Deity.Boon b : d.boons().values()) {
                if (!b.revive() || cannotBoon(p, god, b.id()) != null) continue;
                if (boon(p, god, b.id()) != null) continue;
                e.setCanceled(true);
                if (p.getHealth() <= 0) p.setHealth(Math.max(1f, p.getMaxHealth() / 2));
                p.sendSystemMessage(Component.literal(d.displayName() + " spares you.").withStyle(ChatFormatting.GOLD));
                return;
            }
        }
    }

    // ---- wrath and regen

    private static void tick(MinecraftServer server) {
        if (server.getTickCount() % 1200 != 0) return;
        if (deities().isEmpty()) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) minute(p);
    }

    /** Once a minute: favour regen for patrons, and wrath from gods that hate the player. */
    public static void minute(ServerPlayer player) {
        for (FId g : patrons(player)) {
            Deity d = deity(g);
            if (d.regen() > 0 && devotion(player, g) >= 0) addFavour(player, g, d.regen());
        }
        for (Deity d : deities().values()) wrath(player, d, false);
    }

    /** Sends a god's wrath when it is on, devotion is below its threshold, and the dice and cooldown allow it. */
    public static boolean wrath(ServerPlayer player, Deity d, boolean sure) {
        Deity.Wrath w = d.wrath();
        if (!w.enabled() || devotion(player, d.id()) >= w.below()) return false;
        String key = "wrath|" + d.id();
        if (cooling(player, key)) return false;
        if (!sure && player.getRandom().nextDouble() >= w.chance()) return false;
        cool(player, key, w.cooldown());
        Rewards.give(player, w.rewards());
        player.sendSystemMessage(Component.literal(d.displayName() + " is wrathful.").withStyle(ChatFormatting.DARK_RED));
        return true;
    }

    // ---- conditions and quest objectives

    private static void registerConditions() {
        Conditions.register("devotion", (j, c) -> {
            FId g = Conditions.id(j, "deity");
            return g != null && c.player() != null && Conditions.inRange(devotion(c.player(), g), j);
        });
        Conditions.register("patron", (j, c) -> {
            if (c.player() == null) return false;
            FId g = Conditions.id(j, "deity");
            return g == null ? !patrons(c.player()).isEmpty() : follows(c.player(), g);
        });
        Conditions.register("favour", (j, c) -> {
            if (c.player() == null) return false;
            FId g = Conditions.id(j, "deity");
            if (g == null) g = favourGod(c.player());
            return g != null && Conditions.inRange(favour(c.player(), g), j);
        });
    }

    private static void registerObjectives() {
        Quests.Objective byDeity = new Quests.Objective() {
            @Override
            public boolean matches(ServerPlayer player, JsonObject o, Object ctx) {
                return !o.has("deity") || o.get("deity").getAsString().equals(String.valueOf(ctx));
            }
        };
        Quests.register("pray", byDeity);
        Quests.register("offer", byDeity);
        Quests.register("ritual", new Quests.Objective() {
            @Override
            public boolean matches(ServerPlayer player, JsonObject o, Object ctx) {
                return !o.has("ritual") || o.get("ritual").getAsString().equals(String.valueOf(ctx));
            }
        });
    }

    /** Nearest monster to an entity within a radius, for smiting. */
    static Entity nearestEnemy(ServerPlayer player, double radius) {
        AABB box = player.getBoundingBox().inflate(radius);
        Entity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : player.level().getEntities(player, box, x -> x instanceof Enemy && x.isAlive())) {
            double dd = e.distanceToSqr(player);
            if (dd < bd) {
                bd = dd;
                best = e;
            }
        }
        return best;
    }

    static void lightning(ServerLevel level, double x, double y, double z, ServerPlayer cause, float damage) {
        LightningBolt bolt = FVanilla.create(EntityType.LIGHTNING_BOLT, level);
        if (bolt == null) return;
        bolt.setPos(x, y, z);
        bolt.setDamage(damage);
        if (cause != null) bolt.setCause(cause);
        level.addFreshEntity(bolt);
    }
}
