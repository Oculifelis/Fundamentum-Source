package com.fundamentum.weight;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.config.ConfigValue;
import com.fundamentum.config.FConfig;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FStreamCodec;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Weight, off by default, in two parts with a config switch each.
 * <p><b>Equipment load</b> ({@code equipment_load}): worn armour and the held items, against the
 * {@code fundamentum:load_capacity} attribute, give a light, medium or heavy load. The load scales dodge distance,
 * the dodge's invulnerable window and its hunger cost.
 * <p><b>Inventory weight</b> ({@code inventory_weight}): everything carried, against {@code fundamentum:carry_capacity}.
 * Each overload step slows walking and lowers jumps. Nothing is ever blocked: the slowdown is capped.
 * <p>Item weights come from {@code data/<ns>/fundamentum/item_weights/*.json} ({@code {"values": {"minecraft:anvil": 30,
 * "#c:ingots": 0.5}}}); an exact item wins over a tag. Items without an entry fall back to the rules in
 * {@code data/<ns>/fundamentum/weight_rules/}: armour by its armour points and toughness, then a block or item default.
 * The rules also hold the load and overload steps. The {@code count_containers} config decides whether shulker box,
 * bundle and backpack contents count.
 */
public final class Weights {
    private Weights() { }

    public static final FId CONFIG_ID = FId.of("fundamentum", "weight");
    public static final FId STATE = FId.of("fundamentum", "weight_state");
    private static final FId DEFAULT_RULES = FId.of("fundamentum", "default");

    /** How the client shows the meter: one bar beside the dodge bars, or a row of weights above the food. */
    public enum Meter { BAR, ICONS }

    /** One equipment load step: below this load fraction it applies; multipliers on dodge distance, i-frames and cost. */
    public record LoadStep(String name, double below, double distance, double iframes, double cost) {
        public static final LoadStep NONE = new LoadStep("none", Double.MAX_VALUE, 1, 1, 1);
    }

    /** One overload step: above this carried fraction, walking speed and jump change by these fractions. */
    public record OverloadStep(double over, double speed, double jump) { }

    /** The rules from {@code weight_rules}. */
    public record Rules(double defaultItem, double defaultBlock, double perArmor, double perToughness,
                        List<LoadStep> load, List<OverloadStep> overload) {
        public static final Rules DEFAULT = new Rules(0.05, 0.25, 1.5, 2.0,
            List.of(new LoadStep("light", 0.34, 1, 1, 1), new LoadStep("medium", 0.67, 0.85, 0.85, 1.3),
                new LoadStep("heavy", Double.MAX_VALUE, 0.65, 0.65, 1.75)),
            List.of(new OverloadStep(1.0, -0.1, -0.1), new OverloadStep(1.25, -0.25, -0.25), new OverloadStep(1.5, -0.45, -0.5)));
    }

    /**
     * Server to client: which parts are on, the load fraction, load step and step count, the carried fraction, the
     * overload step (0 = not over) and the overload step count.
     */
    public record WeightState(boolean loadOn, boolean invOn, float load, int loadStep, int loadSteps,
                              float inv, int invStep, int invSteps) {
        public static final FStreamCodec<WeightState> CODEC = FStreamCodec.of((buf, s) -> {
            buf.writeBoolean(s.loadOn);
            buf.writeBoolean(s.invOn);
            buf.writeFloat(s.load);
            buf.writeVarInt(s.loadStep);
            buf.writeVarInt(s.loadSteps);
            buf.writeFloat(s.inv);
            buf.writeVarInt(s.invStep);
            buf.writeVarInt(s.invSteps);
        }, buf -> new WeightState(buf.readBoolean(), buf.readBoolean(), buf.readFloat(), buf.readVarInt(), buf.readVarInt(),
            buf.readFloat(), buf.readVarInt(), buf.readVarInt()));
        public static final WeightState NONE = new WeightState(false, false, 0, 0, 0, 0, 0, 0);

        /** True when either part is on. */
        public boolean on() {
            return loadOn || invOn;
        }
    }

    private static final class Track {
        /** The player object last given the slowdown; a respawn makes a new one without it. */
        ServerPlayer player;
        LoadStep step = LoadStep.NONE;
        double load, carried, speed, jump;
        int invStep;
        WeightState sent;
    }

    private static ConfigValue<Boolean> equipmentLoad;
    private static ConfigValue<Boolean> inventoryWeight;
    private static ConfigValue<Boolean> countContainers;
    private static ConfigValue<Meter> meter;
    private static ConfigValue<Boolean> hud;

    private static volatile Map<FId, Double> exact = Map.of();
    private static volatile List<Map.Entry<TagKey<Item>, Double>> tags = List.of();
    private static volatile Rules rules = Rules.DEFAULT;
    private static final Map<Item, Double> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, Track> TRACK = new ConcurrentHashMap<>();
    private static volatile WeightState client = WeightState.NONE;
    private static long clock;
    private static boolean initialised;

    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
        FConfig c = FConfig.create(CONFIG_ID);
        equipmentLoad = c.bool("equipment_load", false);
        inventoryWeight = c.bool("inventory_weight", false);
        countContainers = c.bool("count_containers", true);
        meter = c.enumValue("meter", Meter.class, Meter.BAR).side(FConfig.Side.CLIENT);
        hud = c.bool("hud", true).side(FConfig.Side.CLIENT);
        FNet.register(STATE, WeightState.class, WeightState.CODEC, FNet.Side.TO_CLIENT, (msg, ctx) -> client = msg);
        FLife.jsonData(FId.of("fundamentum", "item_weights"), "fundamentum/item_weights", Weights::loadWeights);
        FLife.jsonData(FId.of("fundamentum", "weight_rules"), "fundamentum/weight_rules", Weights::loadRules);
        FLife.dataReloaded(s -> CACHE.clear());
        FLife.serverTick(Weights::tick);
        FLife.serverStopped(s -> {
            TRACK.clear();
            CACHE.clear();
        });
        WeightPort.init();
    }

    // ---- config ----

    public static boolean equipmentLoad() {
        return equipmentLoad != null && equipmentLoad.getOr(false);
    }

    public static boolean inventoryWeight() {
        return inventoryWeight != null && inventoryWeight.getOr(false);
    }

    public static boolean countContainers() {
        return countContainers == null || countContainers.getOr(true);
    }

    public static Meter meter() {
        return meter == null ? Meter.BAR : meter.getOr(Meter.BAR);
    }

    public static boolean hud() {
        return hud == null || hud.getOr(true);
    }

    /** Sets a boolean entry (equipment_load, inventory_weight or count_containers); the self-test uses it. */
    static void setConfig(String key, boolean value) {
        ConfigValue<Boolean> v = switch (key) {
            case "equipment_load" -> equipmentLoad;
            case "inventory_weight" -> inventoryWeight;
            default -> countContainers;
        };
        v.set(value);
    }

    /** The last state the server sent this client. */
    public static WeightState client() {
        return client;
    }

    public static void clientReset() {
        client = WeightState.NONE;
    }

    // ---- data ----

    private static void loadWeights(Map<FId, List<JsonElement>> files) {
        Map<FId, Double> ex = new HashMap<>();
        Map<String, Double> tg = new java.util.LinkedHashMap<>();
        new TreeMap<>(files).forEach((id, list) -> {
            for (JsonElement e : list) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                if (o.has("replace") && o.get("replace").getAsBoolean()) {
                    ex.clear();
                    tg.clear();
                }
                if (!o.has("values") || !o.get("values").isJsonObject()) continue;
                for (Map.Entry<String, JsonElement> v : o.getAsJsonObject("values").entrySet()) {
                    double w;
                    try {
                        w = Math.max(0, v.getValue().getAsDouble());
                    } catch (RuntimeException bad) {
                        continue;
                    }
                    String k = v.getKey();
                    if (k.startsWith("#")) {
                        if (FId.tryOf(k.substring(1)) != null) tg.put(k.substring(1), w);
                    } else {
                        FId item = FId.tryOf(k);
                        if (item != null) ex.put(item, w);
                    }
                }
            }
        });
        List<Map.Entry<TagKey<Item>, Double>> t = new ArrayList<>();
        tg.forEach((k, w) -> t.add(Map.entry(TagKey.create(Registries.ITEM, FId.tryOf(k).vanilla()), w)));
        exact = Map.copyOf(ex);
        tags = List.copyOf(t);
        CACHE.clear();
    }

    private static void loadRules(Map<FId, List<JsonElement>> files) {
        List<JsonElement> list = files.get(DEFAULT_RULES);
        if (list == null && !files.isEmpty()) list = new TreeMap<>(files).firstEntry().getValue();
        Rules r = Rules.DEFAULT;
        if (list != null && !list.isEmpty() && list.get(list.size() - 1).isJsonObject()) {
            try {
                r = parseRules(list.get(list.size() - 1).getAsJsonObject());
            } catch (RuntimeException bad) {
                r = Rules.DEFAULT;
            }
        }
        rules = r;
        CACHE.clear();
    }

    static Rules parseRules(JsonObject o) {
        Rules d = Rules.DEFAULT;
        double item = num(o, "default_item", d.defaultItem());
        double block = num(o, "default_block", d.defaultBlock());
        JsonObject armor = o.has("armor") ? o.getAsJsonObject("armor") : new JsonObject();
        List<LoadStep> load = new ArrayList<>();
        if (o.has("load") && o.getAsJsonObject("load").has("steps")) {
            for (JsonElement e : o.getAsJsonObject("load").getAsJsonArray("steps")) {
                JsonObject s = e.getAsJsonObject();
                load.add(new LoadStep(s.has("name") ? s.get("name").getAsString() : "step" + load.size(),
                    num(s, "below", Double.MAX_VALUE), num(s, "distance", 1), num(s, "iframes", 1), num(s, "cost", 1)));
            }
            load.sort((a, b) -> Double.compare(a.below(), b.below()));
        }
        List<OverloadStep> over = new ArrayList<>();
        if (o.has("inventory") && o.getAsJsonObject("inventory").has("steps")) {
            for (JsonElement e : o.getAsJsonObject("inventory").getAsJsonArray("steps")) {
                JsonObject s = e.getAsJsonObject();
                over.add(new OverloadStep(num(s, "over", 1), clampPenalty(num(s, "speed", 0)), clampPenalty(num(s, "jump", 0))));
            }
            over.sort((a, b) -> Double.compare(a.over(), b.over()));
        }
        return new Rules(item, block, num(armor, "per_point", d.perArmor()), num(armor, "per_toughness", d.perToughness()),
            load.isEmpty() ? d.load() : List.copyOf(load), o.has("inventory") ? List.copyOf(over) : d.overload());
    }

    private static double num(JsonObject o, String k, double def) {
        return o.has(k) ? o.get(k).getAsDouble() : def;
    }

    /** Overload never stops a player: walking and jumping keep at least a tenth. */
    private static double clampPenalty(double v) {
        return Math.max(-0.9, Math.min(0, v));
    }

    /** The loaded rules. */
    public static Rules rules() {
        return rules;
    }

    // ---- weighing ----

    /** The weight of one of this item, without anything stored inside it. */
    public static double unitWeight(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        Double cached = CACHE.get(stack.getItem());
        if (cached != null) return cached;
        double w = lookup(stack);
        CACHE.put(stack.getItem(), w);
        return w;
    }

    private static double lookup(ItemStack stack) {
        Double w = exact.get(FVanilla.idOf(stack.getItem()));
        if (w != null) return w;
        for (Map.Entry<TagKey<Item>, Double> t : tags) {
            if (stack.is(t.getKey())) return t.getValue();
        }
        Rules r = rules;
        double[] a = WeightPort.armor(stack);
        if (a[0] > 0 || a[1] > 0) return a[0] * r.perArmor() + a[1] * r.perToughness();
        return stack.getItem() instanceof BlockItem ? r.defaultBlock() : r.defaultItem();
    }

    /** The weight of the whole stack, plus what is stored inside it when {@code count_containers} is on. */
    public static double stackWeight(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        double w = unitWeight(stack) * stack.getCount() + contained(stack);
        return w;
    }

    private static double contained(ItemStack stack) {
        if (!countContainers()) return 0;
        double[] sum = {0};
        WeightPort.contents(stack, in -> sum[0] += unitWeight(in) * in.getCount());
        return sum[0];
    }

    /** Worn armour plus one of each held item (and what it stores): what a dodge has to move. */
    public static double load(ServerPlayer p) {
        double w = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = p.getItemBySlot(slot);
            if (s.isEmpty()) continue;
            if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) w += unitWeight(s) + contained(s);
            else if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) w += stackWeight(s);
        }
        return w;
    }

    /** Everything in the player's inventory, armour and offhand included. */
    public static double carried(ServerPlayer p) {
        Inventory inv = p.getInventory();
        double w = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) w += stackWeight(inv.getItem(i));
        return w;
    }

    /** The load step the player's dodges use now; {@link LoadStep#NONE} while equipment load is off. */
    public static LoadStep loadStep(ServerPlayer p) {
        if (!equipmentLoad()) return LoadStep.NONE;
        Track t = TRACK.get(p.getUUID());
        if (t == null) t = update(p);
        return t.step;
    }

    private static LoadStep stepFor(double fraction) {
        List<LoadStep> steps = rules.load();
        for (LoadStep s : steps) {
            if (fraction < s.below()) return s;
        }
        return steps.isEmpty() ? LoadStep.NONE : steps.get(steps.size() - 1);
    }

    private static int overloadFor(double fraction) {
        List<OverloadStep> steps = rules.overload();
        int n = 0;
        for (int i = 0; i < steps.size(); i++) {
            if (fraction > steps.get(i).over()) n = i + 1;
        }
        return n;
    }

    private static double capacity(ServerPlayer p, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> a) {
        return Math.max(1e-3, FVanilla.attributeValue(p, a));
    }

    /** Weighs the player again now, applies the overload slowdown and sends the meter. Ticks do this every half second. */
    public static void refresh(ServerPlayer p) {
        update(p);
    }

    private static Track update(ServerPlayer p) {
        Track t = TRACK.computeIfAbsent(p.getUUID(), k -> new Track());
        boolean loadOn = equipmentLoad();
        boolean invOn = inventoryWeight();
        if (loadOn) {
            t.load = load(p);
            t.step = stepFor(t.load / capacity(p, StatAttributes.LOAD_CAPACITY));
        } else {
            t.load = 0;
            t.step = LoadStep.NONE;
        }
        double speed = 0, jump = 0;
        if (invOn) {
            t.carried = carried(p);
            t.invStep = overloadFor(t.carried / capacity(p, StatAttributes.CARRY_CAPACITY));
            if (t.invStep > 0) {
                OverloadStep s = rules.overload().get(t.invStep - 1);
                speed = s.speed();
                jump = s.jump();
            }
        } else {
            t.carried = 0;
            t.invStep = 0;
        }
        if (speed != t.speed || jump != t.jump || (t.player != p && (speed != 0 || jump != 0))) {
            WeightPort.penalty(p, speed, jump);
            t.speed = speed;
            t.jump = jump;
        }
        t.player = p;
        List<LoadStep> ls = rules.load();
        WeightState s = new WeightState(loadOn, invOn,
            round(loadOn ? t.load / capacity(p, StatAttributes.LOAD_CAPACITY) : 0), loadOn ? Math.max(0, ls.indexOf(t.step)) : 0, ls.size(),
            round(invOn ? t.carried / capacity(p, StatAttributes.CARRY_CAPACITY) : 0), t.invStep, rules.overload().size());
        if (!s.equals(t.sent)) {
            t.sent = s;
            FNet.toPlayer(p, s);
        }
        return t;
    }

    private static float round(double v) {
        return Math.round(v * 100) / 100f;
    }

    /** The carried fraction of capacity the server last worked out for this player (0 while inventory weight is off). */
    public static double carriedFraction(ServerPlayer p) {
        Track t = TRACK.get(p.getUUID());
        return t == null || t.sent == null ? 0 : t.sent.inv();
    }

    /** The overload step the server last worked out for this player (0 = not over). */
    public static int overloadStep(ServerPlayer p) {
        Track t = TRACK.get(p.getUUID());
        return t == null ? 0 : t.invStep;
    }

    private static void tick(MinecraftServer server) {
        clock++;
        if (clock % 10 != 0) return;
        boolean on = equipmentLoad() || inventoryWeight();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Track t = TRACK.get(p.getUUID());
            if (!on && (t == null || (t.speed == 0 && t.jump == 0 && (t.sent == null || !t.sent.on())))) continue;
            update(p);
        }
        if (TRACK.size() > server.getPlayerList().getPlayerCount() * 2 + 8) {
            java.util.Set<UUID> live = new java.util.HashSet<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) live.add(p.getUUID());
            TRACK.keySet().retainAll(live);
        }
    }
}
