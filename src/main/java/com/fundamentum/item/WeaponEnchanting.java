package com.fundamentum.item;

import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.jetbrains.annotations.Nullable;

/**
 * Which vanilla enchantments Fundamentum's weapon types take, the way 1.21's enchantable item tags decide it. 1.20.1 picks
 * an enchantment's items by its {@code EnchantmentCategory} in code; Fundamentum adds the 1.21 item tags
 * {@code #minecraft:enchantable/melee_weapon}, {@code sharp_weapon}, {@code weapon}, {@code fire_aspect},
 * {@code durability} and {@code vanishing} (holding whips, thrown weapons, spears, gauntlets, flails and scythes, and
 * nesting like 1.21's), and an item in one gets the enchantments 1.21 gives that tag. Each enchantment has a supported tag
 * (books in an anvil) and a primary tag (the enchanting table), as in 1.21's enchantment files; the table still needs the
 * item to be enchantable (stack size 1, an enchantment value). Vanilla's own rules stay as they are: this only adds.
 * {@code com.fundamentum.mixin.EnchantmentMixin} asks it.
 */
public final class WeaponEnchanting {
    public static final TagKey<Item> MELEE_WEAPON = tag("melee_weapon");
    public static final TagKey<Item> SHARP_WEAPON = tag("sharp_weapon");
    public static final TagKey<Item> WEAPON = tag("weapon");
    public static final TagKey<Item> FIRE_ASPECT = tag("fire_aspect");
    public static final TagKey<Item> DURABILITY = tag("durability");
    public static final TagKey<Item> VANISHING = tag("vanishing");

    private record Rule(TagKey<Item> supported, TagKey<Item> primary) {}

    private static @Nullable Map<Enchantment, Rule> rules;

    private static Map<Enchantment, Rule> rules() {
        Map<Enchantment, Rule> r = rules;
        if (r == null) {
            r = Map.of(
                Enchantments.SHARPNESS, new Rule(SHARP_WEAPON, MELEE_WEAPON),
                Enchantments.SMITE, new Rule(WEAPON, MELEE_WEAPON),
                Enchantments.BANE_OF_ARTHROPODS, new Rule(WEAPON, MELEE_WEAPON),
                Enchantments.KNOCKBACK, new Rule(MELEE_WEAPON, MELEE_WEAPON),
                Enchantments.MOB_LOOTING, new Rule(MELEE_WEAPON, MELEE_WEAPON),
                Enchantments.FIRE_ASPECT, new Rule(FIRE_ASPECT, MELEE_WEAPON),
                Enchantments.UNBREAKING, new Rule(DURABILITY, DURABILITY),
                Enchantments.MENDING, new Rule(DURABILITY, DURABILITY),
                Enchantments.VANISHING_CURSE, new Rule(VANISHING, VANISHING));
            rules = r;
        }
        return r;
    }

    /** Whether the enchantment goes on the stack by book in an anvil because of these tags. */
    public static boolean supports(Enchantment enchantment, ItemStack stack) {
        Rule rule = rules().get(enchantment);
        return rule != null && !stack.isEmpty() && stack.is(rule.supported());
    }

    /** Whether the enchanting table may offer the enchantment for the stack because of these tags. */
    public static boolean primary(Enchantment enchantment, ItemStack stack) {
        Rule rule = rules().get(enchantment);
        return rule != null && !stack.isEmpty() && stack.is(rule.primary());
    }

    private static TagKey<Item> tag(String name) {
        return TagKey.create(Registries.ITEM, new ResourceLocation("minecraft", "enchantable/" + name));
    }

    private WeaponEnchanting() {}
}
