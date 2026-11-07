package com.fundamentum.damage;

import com.fundamentum.attribute.StatAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.jspecify.annotations.Nullable;

/**
 * Elements shared by every mod: fire, ice, water, electric, earth, air, poison, holy, shadow and arcane. A hit is of an
 * element when its damage type is in the damage type tag {@code #fundamentum:element/<e>}, or the weapon that dealt it
 * (the held item, or the bow or gun that fired the projectile) is in the item tag {@code #fundamentum:element/<e>}; one
 * hit can be of several. Each element has two attributes on every creature: {@code fundamentum:<e>_damage} (base 1)
 * multiplies that element's hits the creature deals, and {@code fundamentum:<e>_resistance} (base 0, -1 to 1) cuts that
 * element's hits it takes by that fraction (1 is immune, -0.5 takes 1.5 times). Fundamentum applies both; mods only tag
 * their damage types and weapons. Vanilla fire, lava and fireballs are fire, freezing is ice, lightning is electric, wind charges are air and
 * wither is shadow. Mods add elements of their own with {@link #add} while they construct.
 */
public final class Elements {
    public record Element(Identifier id, TagKey<DamageType> damageTypes, TagKey<Item> weapons, Holder<Attribute> damage,
                          Holder<Attribute> resistance) {
        public boolean matches(DamageSource source) {
            if (matchesOwn(source)) {
                return true;
            }
            // a variant's hits are this element's too (soul fire is fire)
            for (Element element : ELEMENTS) {
                if (element.parent() == this && element.matches(source)) {
                    return true;
                }
            }
            return false;
        }

        /** The element this one is a variant of (soul fire's is fire), or null. */
        public @Nullable Element parent() {
            return PARENTS.get(this.id);
        }

        /** Whether this element is {@code other} or a variant of it, at any depth. */
        public boolean isA(Element other) {
            for (Element e = this; e != null; e = e.parent()) {
                if (e.id.equals(other.id)) {
                    return true;
                }
            }
            return false;
        }

        private boolean matchesOwn(DamageSource source) {
            if (source.is(this.damageTypes)) {
                return true;
            }
            ItemStack weapon = source.getWeaponItem();
            if (weapon != null && weapon.is(this.weapons)) {
                return true;
            }
            for (Classifier classifier : CLASSIFIERS) {
                if (classifier.matches(this, source)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Code that makes more hits count as an element than the tags can (1.15.0), e.g. every hit with a Fire Aspect weapon
     * as fire. Register while the mod is constructed; it is asked for every element of every hit, so keep it cheap.
     */
    @FunctionalInterface
    public interface Classifier {
        boolean matches(Element element, DamageSource source);
    }

    private static final List<Classifier> CLASSIFIERS = new CopyOnWriteArrayList<>();

    public static void registerClassifier(Classifier classifier) {
        CLASSIFIERS.add(classifier);
    }

    private static final List<Element> ELEMENTS = new CopyOnWriteArrayList<>();
    private static final Map<Identifier, Element> PARENTS = new ConcurrentHashMap<>();

    public static final Element FIRE = builtIn("fire");
    public static final Element ICE = builtIn("ice");
    public static final Element WATER = builtIn("water");
    public static final Element ELECTRIC = builtIn("electric");
    public static final Element EARTH = builtIn("earth");
    public static final Element AIR = builtIn("air");
    public static final Element POISON = builtIn("poison");
    public static final Element HOLY = builtIn("holy");
    public static final Element SHADOW = builtIn("shadow");
    public static final Element ARCANE = builtIn("arcane");
    /**
     * Soul fire (1.25.6): a variant of {@link #FIRE}. Every soul fire hit is a fire hit too (fire resistance, fire-immune
     * creatures, water putting it out and every fire reaction apply unchanged); soul fire only adds on top: the blue
     * soul burning ({@link com.fundamentum.api.SoulFire}) and the soul-only reactions and affinities in data.
     */
    public static final Element SOUL_FIRE = variant(Identifier.fromNamespaceAndPath("fundamentum", "soul_fire"), FIRE,
        StatAttributes.add("soul_fire_damage", 1, 0, 1024), StatAttributes.add("soul_fire_resistance", 0, -1, 1));

    private static Element builtIn(String name) {
        return add(Identifier.fromNamespaceAndPath("fundamentum", name), StatAttributes.add(name + "_damage", 1, 0, 1024),
            StatAttributes.add(name + "_resistance", 0, -1, 1));
    }

    /** Adds an element: its tags are {@code #<namespace>:element/<path>}; the two attributes are the mod's own. */
    public static Element add(Identifier id, Holder<Attribute> damage, Holder<Attribute> resistance) {
        Identifier tag = Identifier.fromNamespaceAndPath(id.getNamespace(), "element/" + id.getPath());
        Element element = new Element(id, TagKey.create(Registries.DAMAGE_TYPE, tag), TagKey.create(Registries.ITEM, tag), damage, resistance);
        ELEMENTS.add(element);
        return element;
    }

    /**
     * Adds a variant of another element (1.25.6): its own tags and attributes, and every hit of it counts as {@code parent}
     * too ({@link Element#matches}), so whatever checks the parent covers the variant. In data, also put the variant's
     * tags inside the parent's ({@code #<ns>:element/<path>} in {@code #fundamentum:element/fire}) so tag lookups agree.
     */
    public static Element variant(Identifier id, Element parent, Holder<Attribute> damage, Holder<Attribute> resistance) {
        PARENTS.put(id, parent);
        return add(id, damage, resistance);
    }

    public static List<Element> all() {
        return List.copyOf(ELEMENTS);
    }

    public static @Nullable Element get(Identifier id) {
        for (Element element : ELEMENTS) {
            if (element.id().equals(id)) {
                return element;
            }
        }
        return null;
    }

    /** The elements of a hit (empty for a plain one). */
    public static List<Element> of(DamageSource source) {
        List<Element> out = new ArrayList<>();
        for (Element element : ELEMENTS) {
            if (element.matches(source)) {
                out.add(element);
            }
        }
        return out;
    }

    /** What the elements of this hit multiply it by: the attacker's element damage times the target's resistances. */
    public static double multiplier(@Nullable LivingEntity attacker, LivingEntity target, DamageSource source) {
        double m = 1;
        for (Element element : ELEMENTS) {
            if (element.matches(source)) {
                if (attacker != null) {
                    m *= DamageClasses.value(attacker, element.damage());
                }
                m *= 1 - DamageClasses.value(target, element.resistance());
            }
        }
        return m;
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(Elements::onDamage);
    }

    private static void onDamage(LivingIncomingDamageEvent event) {
        LivingEntity attacker = event.getSource().getEntity() instanceof LivingEntity living ? living : null;
        double m = multiplier(attacker, event.getEntity(), event.getSource());
        if (m != 1) {
            event.setAmount((float) Math.max(0, event.getAmount() * m));
        }
    }

    private Elements() {}
}
