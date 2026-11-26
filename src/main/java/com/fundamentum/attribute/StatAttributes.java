package com.fundamentum.attribute;

import com.fundamentum.port.FId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Combat stats every mod can share, so gear, armour and effects from any of them stack. Every living entity has them.
 * Fundamentum only holds the values: the mod that fires, reloads or explodes reads the final value and applies it.
 * Multipliers default to 1, chances and fractions to 0. Startup stat files ({@link StartupStats}) add more attributes,
 * under their own namespaces, while the game starts. With Apothic Attributes loaded, the stats it shares with us
 * (crit chance and damage, armour pierce, ranged damage, projectile speed) are its own, and our ids are aliases.
 */
public final class StatAttributes {
    private static final DeferredRegister<Attribute> ATTRIBUTES = DeferredRegister.create(Registries.ATTRIBUTE, "fundamentum");

    /** Apothic Attributes' mod id. When it is loaded, the stats it also has are its own and ours are aliases of them. */
    public static final String APOTHIC = "apothic_attributes";
    private static final boolean APOTHIC_LOADED = ModList.get() != null && ModList.get().isLoaded(APOTHIC);
    /** Our name to Apothic's, for every stat that was folded into Apothic's. */
    private static final Map<String, String> FOLDED_IDS = new LinkedHashMap<>();
    private static final List<Holder<Attribute>> FOLDED = new ArrayList<>();

    /** Chance (0 to 1) that a hit is critical. */
    public static final Holder<Attribute> CRIT_CHANCE = shared("crit_chance", "crit_chance", 0, 0, 1);
    /** Damage multiplier of a critical hit. */
    public static final Holder<Attribute> CRIT_DAMAGE = shared("crit_damage", "crit_damage", 1.5, 1, 100);
    /** Multiplier on ranged and projectile damage. */
    public static final Holder<Attribute> RANGED_DAMAGE = shared("ranged_damage", "projectile_damage", 1, 0, 1024);
    /** Multiplier on shots per second. */
    public static final Holder<Attribute> FIRE_RATE = add("fire_rate", 1, 0.05, 20);
    /** Multiplier on reload speed (2 reloads in half the time). */
    public static final Holder<Attribute> RELOAD_SPEED = add("reload_speed", 1, 0.05, 20);
    /** Chance (0 to 1) that a shot uses no ammo. */
    public static final Holder<Attribute> AMMO_CONSERVATION = add("ammo_conservation", 0, 0, 1);
    /** Fraction (0 to 1) of the target's armour ignored. */
    public static final Holder<Attribute> ARMOR_PIERCE = shared("armor_pierce", "armor_shred", 0, 0, 1);
    /** Multiplier on explosion radius. */
    public static final Holder<Attribute> BLAST_RADIUS = add("blast_radius", 1, 0, 16);
    /** Multiplier on damage taken from one's own explosions (0 = none). */
    public static final Holder<Attribute> BLAST_SELF_DAMAGE = add("blast_self_damage", 1, 0, 16);
    /** Multiplier on projectile speed. */
    public static final Holder<Attribute> PROJECTILE_SPEED = shared("projectile_speed", "arrow_velocity", 1, 0.05, 20);
    /** Multiplier on spread (0 = perfectly accurate). */
    public static final Holder<Attribute> SPREAD = add("spread", 1, 0, 16);
    /** Multiplier on how long use sickness lasts (see com.fundamentum.sickness.Sickness). */
    public static final Holder<Attribute> SICKNESS_DURATION = add("sickness_duration", 1, 0, 16);
    /** Multiplier on the damage a player's minions deal (see com.fundamentum.summon.Summons). */
    public static final Holder<Attribute> SUMMON_DAMAGE = add("summon_damage", 1, 0, 1024);
    /** Multiplier on wing flight time (see com.fundamentum.wings.Wings). */
    public static final Holder<Attribute> WING_FLIGHT_TIME = add("wing_flight_time", 1, 0, 16);
    /** Dodge charges a player holds; 0 means no dodge at all (see com.fundamentum.dodge.Dodges). */
    public static final Holder<Attribute> DODGE_CHARGES = add("dodge_charges", 0, 0, 20);
    /** Multiplier on how fast spent dodge charges come back. */
    public static final Holder<Attribute> DODGE_RECHARGE = add("dodge_recharge", 1, 0, 20);
    /** Multiplier on how far a dodge carries. */
    public static final Holder<Attribute> DODGE_DISTANCE = add("dodge_distance", 1, 0, 10);
    /** Multiplier on dodge speed; the same distance is covered in fewer ticks. */
    public static final Holder<Attribute> DODGE_SPEED = add("dodge_speed", 1, 0.1, 10);
    /** Multiplier on how long a dodge ignores hits. */
    public static final Holder<Attribute> DODGE_IFRAMES = add("dodge_iframes", 1, 0, 10);
    /** Multiplier on the wait before the next dodge can start. */
    public static final Holder<Attribute> DODGE_CHAIN_DELAY = add("dodge_chain_delay", 1, 0, 10);
    /** Multiplier on the hunger a dodge costs. */
    public static final Holder<Attribute> DODGE_COST = add("dodge_cost", 1, 0, 10);
    /** Extra dodges allowed in one jump, on top of the style's own. */
    public static final Holder<Attribute> DODGE_AIR = add("dodge_air", 0, 0, 20);
    /** Multiplier on how long a player cannot attack after a dodge. */
    public static final Holder<Attribute> DODGE_RECOVERY = add("dodge_recovery", 1, 0, 10);
    /** Damage dealt to each mob a dodge passes through. */
    public static final Holder<Attribute> DODGE_POWER = add("dodge_power", 0, 0, 1024);
    /** Knockback given to each mob a dodge passes through. */
    public static final Holder<Attribute> DODGE_KNOCKBACK = add("dodge_knockback", 0, 0, 10);
    /** Chance (0 to 1) that a dodged hit gives the charge back. */
    public static final Holder<Attribute> DODGE_REFUND = add("dodge_refund", 0, 0, 1);
    /** Total item weight a player carries before overload slows them (see com.fundamentum.weight.Weights). */
    /** Poise damage a creature takes before it staggers; 0 means hits never stagger it (see com.fundamentum.poise.Poise). */
    public static final Holder<Attribute> POISE = add("poise", 0, 0, 100000);
    /** Multiplier on the poise damage a creature's hits deal. */
    public static final Holder<Attribute> POISE_DAMAGE = add("poise_damage", 1, 0, 1024);
    public static final Holder<Attribute> CARRY_CAPACITY = add("carry_capacity", 400, 0, 100000);
    /** Worn and held weight a player bears before their dodge counts as a medium or heavy load. */
    public static final Holder<Attribute> LOAD_CAPACITY = add("load_capacity", 40, 0, 100000);

    /**
     * A stat Apothic Attributes also has. With Apothic loaded this is Apothic's own attribute, and {@code fundamentum:<ours>}
     * becomes an alias of it, so gear, commands and saves using either name change the same value. Without Apothic it is ours.
     */
    private static Holder<Attribute> shared(String ours, String theirs, double base, double min, double max) {
        if (!APOTHIC_LOADED || theirs == null) return add(ours, base, min, max);
        FOLDED_IDS.put(ours, theirs);
        Holder<Attribute> h = DeferredHolder.create(Registries.ATTRIBUTE, FId.of(APOTHIC, theirs).vanilla());
        FOLDED.add(h);
        return h;
    }

    /**
     * True when this stat is Apothic Attributes' own (Apothic is loaded and has the same stat). Apothic then applies its
     * effect to vanilla attacks and arrows itself; a mod should still apply it to its own weapons and projectiles.
     */
    public static boolean apothicOwns(Holder<Attribute> stat) {
        return FOLDED.contains(stat);
    }

    /** Our stat names that are aliases of Apothic's while Apothic is loaded, with the Apothic name each points to. */
    public static Map<String, String> foldedIds() {
        return java.util.Collections.unmodifiableMap(FOLDED_IDS);
    }

    private static void alias(RegisterEvent event) {
        if (FOLDED_IDS.isEmpty() || !event.getRegistryKey().equals(Registries.ATTRIBUTE)) return;
        Registry<Attribute> registry = event.getRegistry(Registries.ATTRIBUTE);
        FOLDED_IDS.forEach((ours, theirs) -> registry.addAlias(FId.of("fundamentum", ours).vanilla(), FId.of(APOTHIC, theirs).vanilla()));
    }

    /** Registers one more Fundamentum attribute (the element and class ones); call it while mods construct. */
    public static Holder<Attribute> add(String name, double base, double min, double max) {
        return ATTRIBUTES.register(name, () -> new RangedAttribute("attribute.name.fundamentum." + name, base, min, max).setSyncable(true));
    }

    /** Registers for startup stats in other namespaces, by namespace. */
    private static final java.util.Map<String, DeferredRegister<Attribute>> STARTUP = new java.util.LinkedHashMap<>();

    public static void init(IEventBus modBus) {
        registerStartup(StartupStats.init(com.fundamentum.port.FVanilla.configDir()).values());
        ATTRIBUTES.register(modBus);
        STARTUP.values().forEach(r -> r.register(modBus));
        modBus.addListener(StatAttributes::alias);
        modBus.addListener(StatAttributes::attach);
    }

    private static void registerStartup(java.util.Collection<StartupStats.Entry> entries) {
        for (StartupStats.Entry e : entries) {
            String ns = e.id().namespace();
            String path = e.id().path();
            DeferredRegister<Attribute> reg = "fundamentum".equals(ns) ? ATTRIBUTES
                : STARTUP.computeIfAbsent(ns, n -> DeferredRegister.create(Registries.ATTRIBUTE, n));
            if (reg.getEntries().stream().anyMatch(h -> h.getId().getPath().equals(path))) {
                org.slf4j.LoggerFactory.getLogger("Fundamentum").error("Startup stat {} is already an attribute; skipped", e.id());
                continue;
            }
            reg.register(path, () -> new RangedAttribute(e.name(), e.base(), e.min(), e.max()).setSyncable(true));
        }
    }

    private static void attach(EntityAttributeModificationEvent event) {
        java.util.List<DeferredRegister<Attribute>> regs = new java.util.ArrayList<>();
        regs.add(ATTRIBUTES);
        regs.addAll(STARTUP.values());
        for (var type : event.getTypes()) {
            for (Holder<Attribute> attribute : java.util.stream.Stream.concat(regs.stream().flatMap(r -> r.getEntries().stream()), FOLDED.stream()).toList()) {
                if (!attribute.isBound()) continue;
                if (!event.has(type, attribute)) {
                    event.add(type, attribute);
                }
            }
        }
    }

    private StatAttributes() {}
}
