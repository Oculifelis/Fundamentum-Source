package com.fundamentum.effect;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.damage.Elements;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data-driven status effects: effects defined in JSON ({@code data/<ns>/fundamentum/effects/<name>.json}, see
 * {@link EffectDefinition}) or in code, kept by Fundamentum itself rather than as registered MobEffects, so a datapack can
 * add new ones. Each has a stacking rule, attribute modifiers, tick actions ({@link EffectActions}), an optional element
 * (the target's resistance to it scales the effect's damage, and full resistance refuses the effect) and cure tags.
 *
 * <p>Everything here is a plain static call, so scripts reach it directly:
 * {@code Effects.apply(entity, "mypack:bleed", 200, 0)}, {@code Effects.cure(entity, "fundamentum:cures/milk")}.
 * Effects live on the server; they persist with the entity (in its persistent data) and are cleared by death. Drinking
 * milk cures {@link #CURE_MILK}. The table {@code data/<ns>/fundamentum/effect_cures/<name>.json}
 * ({@code {"cures": ["fundamentum:cures/holy"], "effects": ["minecraft:wither"]}}) lets a cure tag also take off ordinary
 * MobEffects, such as Fundamentum's potion sickness.
 */
public final class Effects {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    public static final Identifier CURE_MILK = Identifier.fromNamespaceAndPath("fundamentum", "cures/milk");
    public static final Identifier CURE_HOLY = Identifier.fromNamespaceAndPath("fundamentum", "cures/holy");
    static final String TAG = "fundamentum:effects";

    private static final Map<Identifier, EffectDefinition> CODE = new ConcurrentHashMap<>();
    private static volatile Map<Identifier, EffectDefinition> data = Map.of();
    private static final Map<LivingEntity, Map<Identifier, ActiveEffect>> ACTIVE = new WeakHashMap<>();

    public static final DataTable<JsonObject> TABLE = DataTables.registerJson(Identifier.fromNamespaceAndPath("fundamentum", "effects"),
            "fundamentum/effects")
        .synced()
        .onLoad(t -> load(t.rawEntries()));

    /** Vanilla MobEffects a cure tag also takes off. */
    public record VanillaCure(List<Identifier> cures, List<Identifier> effects) {}

    public static final DataTable<VanillaCure> VANILLA_CURES = DataTables.register(Identifier.fromNamespaceAndPath("fundamentum", "effect_cures"),
        "fundamentum/effect_cures", json -> new VanillaCure(ids(json.get("cures")), ids(json.get("effects"))));

    // ---- definitions ----------------------------------------------------------------------------------------------

    /** Registers an effect made in code. A data file with the same id replaces it. */
    public static void register(EffectDefinition definition) {
        CODE.put(definition.id(), definition);
    }

    public static EffectDefinition.Builder builder(Identifier id) {
        return EffectDefinition.builder(id);
    }

    public static @Nullable EffectDefinition definition(Identifier id) {
        EffectDefinition d = data.get(id);
        return d != null ? d : CODE.get(id);
    }

    public static @Nullable EffectDefinition definition(String id) {
        return definition(Identifier.parse(id));
    }

    /** Every effect, from code and data. */
    public static Map<Identifier, EffectDefinition> definitions() {
        Map<Identifier, EffectDefinition> all = new LinkedHashMap<>(CODE);
        all.putAll(data);
        return Collections.unmodifiableMap(all);
    }

    private static void load(Map<Identifier, JsonObject> files) {
        Map<Identifier, EffectDefinition> out = new HashMap<>();
        files.forEach((id, json) -> {
            try {
                out.put(id, EffectDefinition.fromJson(id, json));
            } catch (RuntimeException e) {
                LOG.error("Fundamentum: status effect {} is broken: {}", id, e.getMessage());
            }
        });
        data = Map.copyOf(out);
    }

    private static List<Identifier> ids(@Nullable JsonElement json) {
        List<Identifier> out = new ArrayList<>();
        if (json != null && json.isJsonArray()) {
            json.getAsJsonArray().forEach(e -> out.add(Identifier.parse(e.getAsString())));
        } else if (json != null && json.isJsonPrimitive()) {
            out.add(Identifier.parse(json.getAsString()));
        }
        return List.copyOf(out);
    }

    // ---- applying ---------------------------------------------------------------------------------------------------

    /** Applies an effect for {@code duration} ticks (negative: until removed); false if unknown, refused or client side. */
    public static boolean apply(LivingEntity entity, Identifier id, int duration, int amplifier) {
        EffectDefinition def = definition(id);
        if (def == null || !(entity.level() instanceof ServerLevel level) || !entity.isAlive()) {
            return false;
        }
        if (elementResistance(entity, def) >= 1) {
            return false;
        }
        EffectEvent.Apply pre = post(new EffectEvent.Apply(entity, def, duration, amplifier));
        if (pre.isCanceled()) {
            return false;
        }
        if (FEvents.post(new Events.EffectApplied(entity, FId.from(id), pre.getAmplifier(), Math.max(-1, pre.getDuration())))) {
            return false;
        }
        long now = level.getGameTime();
        int amp = Math.max(0, Math.min(pre.getAmplifier(), def.maxAmplifier()));
        long expires = pre.getDuration() < 0 ? Long.MAX_VALUE : now + pre.getDuration();
        Map<Identifier, ActiveEffect> map = state(entity, true);
        ActiveEffect a = map.get(id);
        if (a == null) {
            a = new ActiveEffect(id, now);
            a.mutableStacks().add(new ActiveEffect.Stack(amp, expires, null));
            map.put(id, a);
            started(entity, level, def, a);
            post(new EffectEvent.Applied(entity, def, a));
        } else {
            int oldLevel = a.level(def);
            stack(def, a, amp, expires, pre.getDuration());
            refreshAttributes(entity, def, a);
            post(new EffectEvent.Stacked(entity, def, a, oldLevel));
        }
        save(entity);
        return true;
    }

    public static boolean apply(LivingEntity entity, String id, int duration, int amplifier) {
        return apply(entity, Identifier.parse(id), duration, amplifier);
    }

    private static void stack(EffectDefinition def, ActiveEffect a, int amp, long expires, int duration) {
        List<ActiveEffect.Stack> stacks = a.mutableStacks();
        int timed = -1;
        for (int i = 0; i < stacks.size(); i++) {
            if (stacks.get(i).source() == null) {
                timed = i;
                break;
            }
        }
        if (timed < 0 || def.stacking() == EffectDefinition.Stacking.INDEPENDENT) {
            if (def.stacking() == EffectDefinition.Stacking.INDEPENDENT && a.timedStacks() >= def.maxStacks()) {
                int shortest = -1;
                for (int i = 0; i < stacks.size(); i++) {
                    ActiveEffect.Stack s = stacks.get(i);
                    if (s.source() == null && (shortest < 0 || s.expiresAt() < stacks.get(shortest).expiresAt())) {
                        shortest = i;
                    }
                }
                stacks.set(shortest, new ActiveEffect.Stack(amp, expires, null));
            } else {
                stacks.add(new ActiveEffect.Stack(amp, expires, null));
            }
            return;
        }
        ActiveEffect.Stack s = stacks.get(timed);
        ActiveEffect.Stack next = switch (def.stacking()) {
            case REFRESH -> new ActiveEffect.Stack(Math.max(s.amplifier(), amp), Math.max(s.expiresAt(), expires), null);
            case EXTEND -> new ActiveEffect.Stack(Math.max(s.amplifier(), amp),
                s.permanent() || expires == Long.MAX_VALUE ? Long.MAX_VALUE : s.expiresAt() + duration, null);
            case AMPLIFY -> new ActiveEffect.Stack(Math.min(s.amplifier() + amp + 1, def.maxAmplifier()), Math.max(s.expiresAt(), expires), null);
            case INDEPENDENT -> throw new IllegalStateException();
        };
        stacks.set(timed, next);
    }

    /**
     * Keeps an effect up for as long as {@code source} wants it (armour set tiers use this): it never runs out and cures
     * leave it alone. Granting again with the same source replaces that stack.
     */
    public static void grant(LivingEntity entity, Identifier id, int amplifier, String source) {
        EffectDefinition def = definition(id);
        if (def == null || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        Map<Identifier, ActiveEffect> map = state(entity, true);
        ActiveEffect a = map.get(id);
        boolean fresh = a == null;
        if (fresh) {
            a = new ActiveEffect(id, level.getGameTime());
            map.put(id, a);
        }
        a.mutableStacks().removeIf(s -> source.equals(s.source()));
        a.mutableStacks().add(new ActiveEffect.Stack(Math.max(0, Math.min(amplifier, def.maxAmplifier())), Long.MAX_VALUE, source));
        if (fresh) {
            started(entity, level, def, a);
            post(new EffectEvent.Applied(entity, def, a));
        } else {
            refreshAttributes(entity, def, a);
        }
    }

    public static void grant(LivingEntity entity, String id, int amplifier, String source) {
        grant(entity, Identifier.parse(id), amplifier, source);
    }

    /** Takes away the stack {@code source} granted. */
    public static void revoke(LivingEntity entity, Identifier id, String source) {
        Map<Identifier, ActiveEffect> map = state(entity, false);
        ActiveEffect a = map == null ? null : map.get(id);
        if (a == null || !a.mutableStacks().removeIf(s -> source.equals(s.source()))) {
            return;
        }
        EffectDefinition def = definition(id);
        if (a.stacks().isEmpty()) {
            end(entity, map, a, def, new Removal(Reason.REMOVED, null));
        } else if (def != null) {
            refreshAttributes(entity, def, a);
        }
    }

    /** Takes away every stack {@code source} granted, on every effect. */
    public static void revokeAll(LivingEntity entity, String source) {
        Map<Identifier, ActiveEffect> map = state(entity, false);
        if (map != null) {
            for (Identifier id : List.copyOf(map.keySet())) {
                revoke(entity, id, source);
            }
        }
    }

    /** Takes an effect off (its granted stacks stay); true if it had timed stacks. */
    public static boolean remove(LivingEntity entity, Identifier id) {
        Map<Identifier, ActiveEffect> map = state(entity, false);
        ActiveEffect a = map == null ? null : map.get(id);
        if (a == null || a.timedStacks() == 0) {
            return false;
        }
        dropTimed(entity, map, a, new Removal(Reason.REMOVED, null));
        save(entity);
        return true;
    }

    public static boolean remove(LivingEntity entity, String id) {
        return remove(entity, Identifier.parse(id));
    }

    /**
     * Cures every effect on {@code entity} that lists {@code cure} (granted stacks stay), and the MobEffects the
     * {@code effect_cures} table ties to it. Returns how many effects were taken off.
     */
    public static int cure(LivingEntity entity, Identifier cure) {
        if (entity.level().isClientSide()) {
            return 0;
        }
        int n = 0;
        Map<Identifier, ActiveEffect> map = state(entity, false);
        if (map != null) {
            for (ActiveEffect a : List.copyOf(map.values())) {
                EffectDefinition def = definition(a.id());
                if (def != null && def.cures().contains(cure) && a.timedStacks() > 0) {
                    dropTimed(entity, map, a, new Removal(Reason.CURED, cure));
                    n++;
                }
            }
            save(entity);
        }
        for (VanillaCure vc : VANILLA_CURES.entries().values()) {
            if (vc.cures().contains(cure)) {
                for (Identifier effect : vc.effects()) {
                    Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.get(effect).<Holder<MobEffect>>map(h -> h).orElse(null);
                    if (holder != null && entity.removeEffect(holder)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    public static int cure(LivingEntity entity, String cure) {
        return cure(entity, Identifier.parse(cure));
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    public static boolean has(LivingEntity entity, Identifier id) {
        return get(entity, id) != null;
    }

    public static boolean has(LivingEntity entity, String id) {
        return has(entity, Identifier.parse(id));
    }

    public static @Nullable ActiveEffect get(LivingEntity entity, Identifier id) {
        Map<Identifier, ActiveEffect> map = state(entity, false);
        return map == null ? null : map.get(id);
    }

    /** The effect's level (1 and up), or 0 without it. */
    public static int level(LivingEntity entity, Identifier id) {
        ActiveEffect a = get(entity, id);
        EffectDefinition def = definition(id);
        return a == null || def == null ? 0 : a.level(def);
    }

    public static int level(LivingEntity entity, String id) {
        return level(entity, Identifier.parse(id));
    }

    /** The highest amplifier among its stacks, or -1 without it. */
    public static int amplifier(LivingEntity entity, Identifier id) {
        ActiveEffect a = get(entity, id);
        return a == null ? -1 : a.amplifier();
    }

    /** Ticks left (Long.MAX_VALUE for a granted or endless one), 0 without it. */
    public static long remaining(LivingEntity entity, Identifier id) {
        ActiveEffect a = get(entity, id);
        return a == null ? 0 : a.remaining(entity.level().getGameTime());
    }

    /** Every effect on the entity, by id. */
    public static Map<Identifier, ActiveEffect> active(LivingEntity entity) {
        Map<Identifier, ActiveEffect> map = state(entity, false);
        return map == null ? Map.of() : Collections.unmodifiableMap(map);
    }

    /**
     * How strongly an effect's element reaches {@code entity}: 1 minus its resistance to that element (1 without an
     * element). When {@code source} already belongs to the element, the hit is scaled by the element system itself, so
     * this returns 1 to avoid scaling twice.
     */
    public static double elementScale(LivingEntity entity, EffectDefinition def, @Nullable DamageSource source) {
        if (def.element() == null) {
            return 1;
        }
        Elements.Element element = Elements.get(def.element());
        if (element == null || (source != null && source.is(element.damageTypes()))) {
            return 1;
        }
        return Math.max(0, 1 - elementResistance(entity, def));
    }

    static double elementResistance(LivingEntity entity, EffectDefinition def) {
        Elements.Element element = def.element() == null ? null : Elements.get(def.element());
        if (element == null || element.resistance() == null) {
            return 0;
        }
        AttributeInstance instance = entity.getAttribute(element.resistance());
        return instance == null ? 0 : instance.getValue();
    }

    // ---- internals --------------------------------------------------------------------------------------------------

    private enum Reason { EXPIRED, CURED, REMOVED }

    private record Removal(Reason reason, @Nullable Identifier cure) {}

    private static synchronized @Nullable Map<Identifier, ActiveEffect> state(LivingEntity entity, boolean create) {
        Map<Identifier, ActiveEffect> map = ACTIVE.get(entity);
        if (map == null && create) {
            map = new LinkedHashMap<>();
            ACTIVE.put(entity, map);
        }
        return map;
    }

    private static <E extends Event> E post(E event) {
        NeoForge.EVENT_BUS.post(event);
        return event;
    }

    private static void started(LivingEntity entity, ServerLevel level, EffectDefinition def, ActiveEffect a) {
        refreshAttributes(entity, def, a);
        EffectAction.Context ctx = new EffectAction.Context(entity, level, def, a, 0);
        for (EffectAction action : def.actions()) {
            action.onApply(ctx);
        }
    }

    private static void dropTimed(LivingEntity entity, Map<Identifier, ActiveEffect> map, ActiveEffect a, Removal why) {
        a.mutableStacks().removeIf(s -> s.source() == null);
        EffectDefinition def = definition(a.id());
        if (a.stacks().isEmpty()) {
            end(entity, map, a, def, why);
        } else if (def != null) {
            refreshAttributes(entity, def, a);
        }
    }

    private static void end(LivingEntity entity, Map<Identifier, ActiveEffect> map, ActiveEffect a, @Nullable EffectDefinition def, Removal why) {
        map.remove(a.id());
        removeAttributes(entity, a, def);
        if (def == null) {
            return;
        }
        if (entity.level() instanceof ServerLevel level) {
            EffectAction.Context ctx = new EffectAction.Context(entity, level, def, a, level.getGameTime() - a.startedAt());
            for (EffectAction action : def.actions()) {
                action.onRemove(ctx);
            }
        }
        switch (why.reason()) {
            case EXPIRED -> post(new EffectEvent.Expired(entity, def));
            case CURED -> post(new EffectEvent.Cured(entity, def, why.cure()));
            case REMOVED -> post(new EffectEvent.Removed(entity, def));
        }
        FEvents.post(new Events.EffectEnded(entity, FId.from(def.id()), why.reason().name().toLowerCase(java.util.Locale.ROOT)));
    }

    private static Identifier modifierId(Identifier effect, int index) {
        return Identifier.fromNamespaceAndPath(effect.getNamespace(), "effect/" + effect.getPath() + "/" + index);
    }

    private static void refreshAttributes(LivingEntity entity, EffectDefinition def, ActiveEffect a) {
        int level = a.level(def);
        for (int i = def.attributes().size(); i < a.appliedModifiers; i++) {
            removeById(entity, modifierId(a.id(), i));
        }
        for (int i = 0; i < def.attributes().size(); i++) {
            def.attributes().get(i).apply(entity, modifierId(a.id(), i), def.perLevel() ? level : 1);
        }
        a.appliedModifiers = def.attributes().size();
        a.appliedLevel = level;
    }

    private static void removeAttributes(LivingEntity entity, ActiveEffect a, @Nullable EffectDefinition def) {
        for (int i = 0; i < a.appliedModifiers; i++) {
            if (def != null && i < def.attributes().size()) {
                def.attributes().get(i).remove(entity, modifierId(a.id(), i));
            } else {
                removeById(entity, modifierId(a.id(), i));
            }
        }
        a.appliedModifiers = 0;
    }

    private static void removeById(LivingEntity entity, Identifier id) {
        BuiltInRegistries.ATTRIBUTE.listElements().forEach(attribute -> {
            AttributeInstance instance = entity.getAttribute(attribute);
            if (instance != null) {
                instance.removeModifier(id);
            }
        });
    }

    private static void tick(ServerTickEvent.Post event) {
        List<LivingEntity> entities;
        synchronized (Effects.class) {
            entities = new ArrayList<>(ACTIVE.keySet());
        }
        for (LivingEntity entity : entities) {
            Map<Identifier, ActiveEffect> map = state(entity, false);
            if (map == null) {
                continue;
            }
            if (entity.isRemoved()) {
                synchronized (Effects.class) {
                    ACTIVE.remove(entity); // kept in its saved data; loaded again when it joins a level
                }
                continue;
            }
            if (!(entity.level() instanceof ServerLevel level) || !entity.isAlive()) {
                continue;
            }
            long now = level.getGameTime();
            boolean changed = false;
            for (ActiveEffect a : List.copyOf(map.values())) {
                EffectDefinition def = definition(a.id());
                if (def == null) {
                    end(entity, map, a, null, new Removal(Reason.REMOVED, null));
                    changed = true;
                    continue;
                }
                if (a.mutableStacks().removeIf(s -> s.expiresAt() <= now)) {
                    changed = true;
                    if (a.stacks().isEmpty()) {
                        end(entity, map, a, def, new Removal(Reason.EXPIRED, null));
                        continue;
                    }
                    refreshAttributes(entity, def, a);
                }
                long left = a.remaining(now);
                if (FEvents.post(new Events.EffectTick(entity, FId.from(a.id()), a.level(def), left == Long.MAX_VALUE ? -1 : (int) Math.min(Integer.MAX_VALUE, left)))) {
                    continue;
                }
                EffectAction.Context ctx = new EffectAction.Context(entity, level, def, a, now - a.startedAt());
                for (EffectAction action : def.actions()) {
                    action.onTick(ctx);
                }
                if (!entity.isAlive()) {
                    break;
                }
            }
            if (changed) {
                save(entity);
            }
        }
    }

    private static void save(LivingEntity entity) {
        Map<Identifier, ActiveEffect> map = state(entity, false);
        JsonObject out = new JsonObject();
        if (map != null) {
            for (ActiveEffect a : map.values()) {
                JsonArray stacks = new JsonArray();
                for (ActiveEffect.Stack s : a.stacks()) {
                    if (s.source() == null) {
                        JsonArray one = new JsonArray();
                        one.add(s.amplifier());
                        one.add(s.expiresAt());
                        stacks.add(one);
                    }
                }
                if (!stacks.isEmpty()) {
                    JsonObject e = new JsonObject();
                    e.addProperty("start", a.startedAt());
                    e.add("stacks", stacks);
                    out.add(a.id().toString(), e);
                }
            }
        }
        CompoundTag tag = entity.getPersistentData();
        if (out.isEmpty()) {
            tag.remove(TAG);
        } else {
            tag.putString(TAG, out.toString());
        }
    }

    private static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || !(event.getLevel() instanceof ServerLevel) || state(entity, false) != null) {
            return;
        }
        String text = entity.getPersistentData().getStringOr(TAG, "");
        if (text.isEmpty()) {
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            Map<Identifier, ActiveEffect> map = state(entity, true);
            for (Map.Entry<String, JsonElement> e : json.entrySet()) {
                Identifier id = Identifier.parse(e.getKey());
                EffectDefinition def = definition(id);
                if (def == null) {
                    continue;
                }
                JsonObject o = e.getValue().getAsJsonObject();
                ActiveEffect a = new ActiveEffect(id, o.get("start").getAsLong());
                for (JsonElement s : o.getAsJsonArray("stacks")) {
                    a.mutableStacks().add(new ActiveEffect.Stack(s.getAsJsonArray().get(0).getAsInt(), s.getAsJsonArray().get(1).getAsLong(), null));
                }
                map.put(id, a);
                refreshAttributes(entity, def, a);
            }
        } catch (RuntimeException e) {
            LOG.warn("Fundamentum: dropped unreadable status effects on {}: {}", entity, e.getMessage());
            entity.getPersistentData().remove(TAG);
        }
    }

    private static void onDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Map<Identifier, ActiveEffect> map = state(entity, false);
        if (map == null || entity.level().isClientSide()) {
            return;
        }
        for (ActiveEffect a : List.copyOf(map.values())) {
            if (a.timedStacks() > 0) {
                dropTimed(entity, map, a, new Removal(Reason.REMOVED, null));
            }
        }
        save(entity);
    }

    private static void onUseFinish(LivingEntityUseItemEvent.Finish event) {
        if (event.getItem().is(Items.MILK_BUCKET) && !event.getEntity().level().isClientSide()) {
            cure(event.getEntity(), CURE_MILK);
        }
    }

    public static void init(IEventBus modBus) {
        TABLE.id();
        VANILLA_CURES.id();
        NeoForge.EVENT_BUS.addListener(Effects::tick);
        NeoForge.EVENT_BUS.addListener(Effects::onJoin);
        NeoForge.EVENT_BUS.addListener(Effects::onDeath);
        NeoForge.EVENT_BUS.addListener(Effects::onUseFinish);
    }

    private Effects() {}
}
