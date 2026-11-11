package com.fundamentum.damage;

import com.fundamentum.attribute.StatAttributes;
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
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * Damage classes shared by every mod: melee, ranger (bows and crossbows), marksman (guns), mage (magic) and thrower
 * (thrown weapons: javelins, knives, shuriken, molotovs, boomerangs; see {@code com.fundamentum.thrown}). A hit is of a
 * class when its damage type is in the damage type tag {@code #fundamentum:class/<c>}. A projectile's class can come from
 * the weapon that fired it instead: when that bow, gun or staff is in an item tag {@code #fundamentum:class/<c>}, that
 * decides (so a gun firing vanilla arrows is marksman, not ranger). Direct hits always go by damage type, so bashing with
 * a gun is still melee. {@code fundamentum:<c>_damage} (base 1) multiplies that class's hits a creature deals, and
 * Fundamentum applies it. It stacks with {@code fundamentum:ranged_damage}, which the firing mod applies itself.
 * Vanilla player and mob attacks are melee, arrows and thrown tridents ranger, magic and indirect magic mage, snowballs and eggs thrower; marksman
 * has no vanilla members. A summoned minion's hits go by the minion's own stats (and the owner's summon_damage), not
 * the owner's class stats. Mods add classes with {@link #add} while they construct.
 */
public final class DamageClasses {
    public record DamageClass(ResourceLocation id, TagKey<DamageType> damageTypes, TagKey<Item> weapons, Supplier<Attribute> damage) {}

    private static final List<DamageClass> CLASSES = new CopyOnWriteArrayList<>();

    public static final DamageClass MELEE = builtIn("melee");
    public static final DamageClass RANGER = builtIn("ranger");
    public static final DamageClass MARKSMAN = builtIn("marksman");
    public static final DamageClass MAGE = builtIn("mage");
    public static final DamageClass THROWER = builtIn("thrower");

    private static DamageClass builtIn(String name) {
        return add(new ResourceLocation("fundamentum", name), StatAttributes.add(name + "_damage", 1, 0, 1024));
    }

    /** Adds a class: its tags are {@code #<namespace>:class/<path>}; the attribute is the mod's own. */
    public static DamageClass add(ResourceLocation id, Supplier<Attribute> damage) {
        ResourceLocation tag = new ResourceLocation(id.getNamespace(), "class/" + id.getPath());
        DamageClass damageClass = new DamageClass(id, TagKey.create(Registries.DAMAGE_TYPE, tag), TagKey.create(Registries.ITEM, tag), damage);
        CLASSES.add(damageClass);
        return damageClass;
    }

    public static List<DamageClass> all() {
        return List.copyOf(CLASSES);
    }

    public static @Nullable DamageClass get(ResourceLocation id) {
        for (DamageClass damageClass : CLASSES) {
            if (damageClass.id().equals(id)) {
                return damageClass;
            }
        }
        return null;
    }

    /** The classes of a hit: the firing weapon's for a projectile whose weapon has one, else its damage type's. */
    public static List<DamageClass> of(DamageSource source) {
        if (source.isIndirect()) {
            ItemStack weapon = Elements.weapon(source);
            if (weapon != null && !weapon.isEmpty()) {
                List<DamageClass> byWeapon = CLASSES.stream().filter(c -> weapon.is(c.weapons())).toList();
                if (!byWeapon.isEmpty()) {
                    return byWeapon;
                }
            }
        }
        return CLASSES.stream().filter(c -> source.is(c.damageTypes())).toList();
    }

    public static boolean is(DamageSource source, DamageClass damageClass) {
        return of(source).contains(damageClass);
    }

    /** What the attacker's class stats multiply this hit by. */
    public static double multiplier(LivingEntity attacker, DamageSource source) {
        double m = 1;
        for (DamageClass damageClass : of(source)) {
            m *= value(attacker, damageClass.damage());
        }
        return m;
    }

    static double value(LivingEntity entity, Supplier<Attribute> attribute) {
        AttributeInstance instance = entity.getAttribute(attribute.get());
        return instance == null ? attribute.get().getDefaultValue() : instance.getValue();
    }

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(DamageClasses::onDamage);
    }

    private static void onDamage(LivingHurtEvent event) {
        if (event.getSource().getEntity() instanceof LivingEntity attacker) {
            double m = multiplier(attacker, event.getSource());
            if (m != 1) {
                event.setAmount((float) (event.getAmount() * m));
            }
        }
    }

    private DamageClasses() {}
}
