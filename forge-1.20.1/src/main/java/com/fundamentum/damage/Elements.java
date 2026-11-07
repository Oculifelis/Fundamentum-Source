package com.fundamentum.damage;

import com.fundamentum.attribute.StatAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

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
    public record Element(ResourceLocation id, TagKey<DamageType> damageTypes, TagKey<Item> weapons, Supplier<Attribute> damage,
                          Supplier<Attribute> resistance) {
        public boolean matches(DamageSource source) {
            if (source.is(this.damageTypes)) {
                return true;
            }
            ItemStack weapon = weapon(source);
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

    private static Element builtIn(String name) {
        return add(new ResourceLocation("fundamentum", name), StatAttributes.add(name + "_damage", 1, 0, 1024),
            StatAttributes.add(name + "_resistance", 0, -1, 1));
    }

    /** Adds an element: its tags are {@code #<namespace>:element/<path>}; the two attributes are the mod's own. */
    public static Element add(ResourceLocation id, Supplier<Attribute> damage, Supplier<Attribute> resistance) {
        ResourceLocation tag = new ResourceLocation(id.getNamespace(), "element/" + id.getPath());
        Element element = new Element(id, TagKey.create(Registries.DAMAGE_TYPE, tag), TagKey.create(Registries.ITEM, tag), damage, resistance);
        ELEMENTS.add(element);
        return element;
    }

    public static List<Element> all() {
        return List.copyOf(ELEMENTS);
    }

    public static @Nullable Element get(ResourceLocation id) {
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
        MinecraftForge.EVENT_BUS.addListener(Elements::onDamage);
    }

    /**
     * The weapon behind a hit, standing in for 1.21's {@code DamageSource.getWeaponItem()}: the attacker's main hand item.
     * 1.20.1 projectiles don't remember the weapon that fired them, so for those it is what the shooter holds now.
     */
    public static @Nullable ItemStack weapon(DamageSource source) {
        if (source.getDirectEntity() instanceof LivingEntity direct) {
            return direct.getMainHandItem();
        }
        return source.getEntity() instanceof LivingEntity shooter ? shooter.getMainHandItem() : null;
    }

    private static void onDamage(LivingHurtEvent event) {
        LivingEntity attacker = event.getSource().getEntity() instanceof LivingEntity living ? living : null;
        double m = multiplier(attacker, event.getEntity(), event.getSource());
        if (m != 1) {
            event.setAmount((float) Math.max(0, event.getAmount() * m));
        }
    }

    private Elements() {}
}
