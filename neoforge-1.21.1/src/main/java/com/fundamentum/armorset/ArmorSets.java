package com.fundamentum.armorset;

import com.fundamentum.attribute.AttributeBonus;
import com.fundamentum.curios.Slots;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.fundamentum.effect.Effects;
import com.fundamentum.item.ItemMatcher;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Armour set bonuses, defined in {@code data/<ns>/fundamentum/armor_sets/<name>.json} (format in
 * {@link ArmorSetDefinition}). Rewards per tier: attribute modifiers, effects kept up while the tier holds, flags
 * ({@link #hasFlag}) and custom rewards ({@link SetRewards}). Worn pieces are counted again when an entity's equipment or
 * Curios change, when it joins a level and when data reloads, never every tick. {@link ArmorSetEvent.TierGained} and
 * {@link ArmorSetEvent.TierLost} report each change.
 *
 * <p>Plain static calls for scripts: {@code ArmorSets.hasFlag(player, "ironclad")}, {@code ArmorSets.pieces(player,
 * "mypack:ironclad")}.
 */
public final class ArmorSets {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    private static final String VANILLA_TAG = "fundamentum:set_effects";

    private static volatile Map<ResourceLocation, ArmorSetDefinition> sets = Map.of();
    private static final Map<LivingEntity, Map<ResourceLocation, Worn>> ACTIVE = new WeakHashMap<>();
    private static final Set<LivingEntity> DIRTY = Collections.newSetFromMap(new WeakHashMap<>());
    private static volatile boolean reloaded;

    /** What an entity currently gets from one set: the definition it was granted under, and the tiers granted. */
    private record Worn(ArmorSetDefinition set, List<ArmorSetDefinition.Tier> tiers) {}

    public static final DataTable<JsonObject> TABLE = DataTables.registerJson(ResourceLocation.fromNamespaceAndPath("fundamentum", "armor_sets"),
            "fundamentum/armor_sets")
        .synced()
        .onLoad(t -> load(t.rawEntries()));

    private static void load(Map<ResourceLocation, JsonObject> files) {
        Map<ResourceLocation, ArmorSetDefinition> out = new LinkedHashMap<>();
        files.forEach((id, json) -> {
            try {
                out.put(id, ArmorSetDefinition.fromJson(id, json));
            } catch (RuntimeException e) {
                LOG.error("Fundamentum: armour set {} is broken: {}", id, e.getMessage());
            }
        });
        sets = Collections.unmodifiableMap(out);
        reloaded = true;
    }

    // ---- queries ----------------------------------------------------------------------------------------------------

    public static Map<ResourceLocation, ArmorSetDefinition> sets() {
        return sets;
    }

    public static @Nullable ArmorSetDefinition get(ResourceLocation id) {
        return sets.get(id);
    }

    /** The sets {@code stack} belongs to. */
    public static List<ArmorSetDefinition> setsOf(ItemStack stack) {
        List<ArmorSetDefinition> out = new ArrayList<>();
        if (!stack.isEmpty()) {
            for (ArmorSetDefinition set : sets.values()) {
                if (set.isMember(stack)) {
                    out.add(set);
                }
            }
        }
        return out;
    }

    /** How many pieces of {@code set} the entity wears now (counted fresh). */
    public static int pieces(LivingEntity entity, ArmorSetDefinition set) {
        int n = 0;
        for (Map.Entry<EquipmentSlot, ItemMatcher> e : set.pieces().entrySet()) {
            if (e.getValue().test(entity.getItemBySlot(e.getKey()))) {
                n++;
            }
        }
        if (!set.curios().isEmpty()) {
            List<ItemStack> worn = Slots.allWorn(entity);
            for (ItemMatcher m : set.curios()) {
                for (ItemStack stack : worn) {
                    if (m.test(stack)) {
                        n++;
                        break;
                    }
                }
            }
        }
        return n;
    }

    public static int pieces(LivingEntity entity, String set) {
        ArmorSetDefinition def = sets.get(ResourceLocation.parse(set));
        return def == null ? 0 : pieces(entity, def);
    }

    /** The tiers of {@code set} the entity has (on the server, as granted; on a client, counted fresh). */
    public static List<ArmorSetDefinition.Tier> activeTiers(LivingEntity entity, ArmorSetDefinition set) {
        if (!entity.level().isClientSide()) {
            Map<ResourceLocation, Worn> map = worn(entity);
            if (map != null) {
                Worn w = map.get(set.id());
                return w == null ? List.of() : w.tiers();
            }
        }
        return tiersFor(set, pieces(entity, set));
    }

    /** Whether any active tier of any set gives the entity {@code flag}. */
    public static boolean hasFlag(LivingEntity entity, String flag) {
        if (!entity.level().isClientSide()) {
            Map<ResourceLocation, Worn> map = worn(entity);
            if (map != null) {
                for (Worn w : map.values()) {
                    for (ArmorSetDefinition.Tier t : w.tiers()) {
                        if (t.flags().contains(flag)) {
                            return true;
                        }
                    }
                }
                return false;
            }
        }
        for (ArmorSetDefinition set : sets.values()) {
            for (ArmorSetDefinition.Tier t : tiersFor(set, pieces(entity, set))) {
                if (t.flags().contains(flag)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every flag the entity has from its sets. */
    public static Set<String> flags(LivingEntity entity) {
        Set<String> out = new LinkedHashSet<>();
        for (ArmorSetDefinition set : sets.values()) {
            for (ArmorSetDefinition.Tier t : activeTiers(entity, set)) {
                out.addAll(t.flags());
            }
        }
        return out;
    }

    /** Counts the entity's pieces again right away (normally that happens by itself at the end of the tick). */
    public static void recompute(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel) || entity.isRemoved()) {
            return;
        }
        Map<ResourceLocation, Worn> map = worn(entity);
        Map<ResourceLocation, Worn> next = new HashMap<>();
        // take away what no longer holds (or holds under an older definition), highest tier first
        if (map != null) {
            for (Worn w : List.copyOf(map.values())) {
                ArmorSetDefinition current = sets.get(w.set().id());
                List<ArmorSetDefinition.Tier> keep = current == w.set() ? tiersFor(current, pieces(entity, current)) : List.of();
                int count = current == null ? 0 : pieces(entity, current);
                for (int i = w.tiers().size() - 1; i >= 0; i--) {
                    ArmorSetDefinition.Tier t = w.tiers().get(i);
                    if (!keep.contains(t)) {
                        revoke(entity, w.set(), t);
                        NeoForge.EVENT_BUS.post(new ArmorSetEvent.TierLost(entity, w.set(), t, count));
                    }
                }
                List<ArmorSetDefinition.Tier> still = new ArrayList<>(w.tiers());
                still.retainAll(keep);
                if (!still.isEmpty()) {
                    next.put(w.set().id(), new Worn(w.set(), List.copyOf(still)));
                }
            }
        }
        // grant what newly holds, lowest tier first
        for (ArmorSetDefinition set : sets.values()) {
            int count = pieces(entity, set);
            List<ArmorSetDefinition.Tier> want = tiersFor(set, count);
            if (want.isEmpty()) {
                continue;
            }
            Worn had = next.get(set.id());
            List<ArmorSetDefinition.Tier> granted = new ArrayList<>(had == null ? List.of() : had.tiers());
            for (ArmorSetDefinition.Tier t : want) {
                if (!granted.contains(t)) {
                    grant(entity, set, t);
                    granted.add(t);
                    NeoForge.EVENT_BUS.post(new ArmorSetEvent.TierGained(entity, set, t, count));
                }
            }
            next.put(set.id(), new Worn(set, List.copyOf(granted)));
        }
        synchronized (ArmorSets.class) {
            if (next.isEmpty()) {
                ACTIVE.remove(entity);
            } else {
                ACTIVE.put(entity, next);
            }
        }
    }

    // ---- internals --------------------------------------------------------------------------------------------------

    private static List<ArmorSetDefinition.Tier> tiersFor(ArmorSetDefinition set, int count) {
        List<ArmorSetDefinition.Tier> out = new ArrayList<>();
        for (ArmorSetDefinition.Tier t : set.tiers()) {
            if (count >= t.pieces()) {
                out.add(t);
            }
        }
        return out;
    }

    private static synchronized @Nullable Map<ResourceLocation, Worn> worn(LivingEntity entity) {
        return ACTIVE.get(entity);
    }

    private static String source(ArmorSetDefinition set) {
        return "set:" + set.id();
    }

    private static ResourceLocation modifierId(ArmorSetDefinition set, ArmorSetDefinition.Tier tier, int index) {
        return ResourceLocation.fromNamespaceAndPath(set.id().getNamespace(), "set/" + set.id().getPath() + "/" + tier.index() + "/" + index);
    }

    private static void grant(LivingEntity entity, ArmorSetDefinition set, ArmorSetDefinition.Tier tier) {
        for (int i = 0; i < tier.attributes().size(); i++) {
            tier.attributes().get(i).apply(entity, modifierId(set, tier, i), 1);
        }
        for (ArmorSetDefinition.EffectGrant e : tier.effects()) {
            if (Effects.definition(e.effect()) != null) {
                Effects.grant(entity, e.effect(), e.amplifier(), source(set) + "/" + tier.index());
            } else {
                Holder<MobEffect> holder = mobEffect(e.effect());
                if (holder != null) {
                    entity.addEffect(new MobEffectInstance(holder, MobEffectInstance.INFINITE_DURATION, e.amplifier(), true, false, true));
                    remember(entity, e, true);
                }
            }
        }
        for (SetReward r : tier.rewards()) {
            r.grant(entity, set, tier);
        }
    }

    private static void revoke(LivingEntity entity, ArmorSetDefinition set, ArmorSetDefinition.Tier tier) {
        for (int i = 0; i < tier.attributes().size(); i++) {
            AttributeBonus b = tier.attributes().get(i);
            b.remove(entity, modifierId(set, tier, i));
        }
        for (ArmorSetDefinition.EffectGrant e : tier.effects()) {
            if (Effects.definition(e.effect()) != null) {
                Effects.revoke(entity, e.effect(), source(set) + "/" + tier.index());
            } else {
                removeVanilla(entity, e);
                remember(entity, e, false);
            }
        }
        for (SetReward r : tier.rewards()) {
            r.revoke(entity, set, tier);
        }
    }

    private static @Nullable Holder<MobEffect> mobEffect(ResourceLocation id) {
        return BuiltInRegistries.MOB_EFFECT.getHolder(id).<Holder<MobEffect>>map(h -> h).orElse(null);
    }

    private static void removeVanilla(LivingEntity entity, ArmorSetDefinition.EffectGrant e) {
        Holder<MobEffect> holder = mobEffect(e.effect());
        MobEffectInstance instance = holder == null ? null : entity.getEffect(holder);
        if (instance != null && instance.isInfiniteDuration() && instance.getAmplifier() == e.amplifier()) {
            entity.removeEffect(holder);
        }
    }

    /** Vanilla effects are saved with the entity; note which ones a set added, so a reload can take stale ones off. */
    private static void remember(LivingEntity entity, ArmorSetDefinition.EffectGrant e, boolean add) {
        CompoundTag tag = entity.getPersistentData();
        Set<String> list = new LinkedHashSet<>(List.of(tag.getString(VANILLA_TAG).split(";")));
        list.remove("");
        String key = e.effect() + "|" + e.amplifier();
        if (add) {
            list.add(key);
        } else {
            list.remove(key);
        }
        if (list.isEmpty()) {
            tag.remove(VANILLA_TAG);
        } else {
            tag.putString(VANILLA_TAG, String.join(";", list));
        }
    }

    private static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || !(event.getLevel() instanceof ServerLevel) || worn(entity) != null) {
            return;
        }
        String saved = entity.getPersistentData().getString(VANILLA_TAG);
        if (!saved.isEmpty()) {
            for (String key : saved.split(";")) {
                int bar = key.lastIndexOf('|');
                if (bar > 0) {
                    try {
                        removeVanilla(entity, new ArmorSetDefinition.EffectGrant(ResourceLocation.parse(key.substring(0, bar)), Integer.parseInt(key.substring(bar + 1))));
                    } catch (RuntimeException ignored) {
                        // a removed effect or a hand-edited tag; nothing to take off
                    }
                }
            }
            entity.getPersistentData().remove(VANILLA_TAG);
        }
        markDirty(entity);
    }

    private static void onEquipment(LivingEquipmentChangeEvent event) {
        if (!event.getEntity().level().isClientSide()) {
            markDirty(event.getEntity());
        }
    }

    private static synchronized void markDirty(LivingEntity entity) {
        DIRTY.add(entity);
    }

    private static void tick(ServerTickEvent.Post event) {
        List<LivingEntity> todo;
        synchronized (ArmorSets.class) {
            if (reloaded) {
                reloaded = false;
                DIRTY.addAll(ACTIVE.keySet());
                event.getServer().getAllLevels().forEach(level -> level.getAllEntities().forEach(e -> {
                    if (e instanceof LivingEntity living) {
                        DIRTY.add(living);
                    }
                }));
            }
            if (DIRTY.isEmpty()) {
                return;
            }
            todo = new ArrayList<>(DIRTY);
            DIRTY.clear();
        }
        for (LivingEntity entity : todo) {
            if (entity.isRemoved()) {
                synchronized (ArmorSets.class) {
                    ACTIVE.remove(entity);
                }
            } else {
                recompute(entity);
            }
        }
    }

    public static void init(IEventBus modBus) {
        TABLE.id();
        NeoForge.EVENT_BUS.addListener(ArmorSets::tick);
        NeoForge.EVENT_BUS.addListener(ArmorSets::onJoin);
        NeoForge.EVENT_BUS.addListener(ArmorSets::onEquipment);
        Slots.onChange(entity -> {
            if (!entity.level().isClientSide()) {
                markDirty(entity);
            }
        });
    }

    private ArmorSets() {}
}
