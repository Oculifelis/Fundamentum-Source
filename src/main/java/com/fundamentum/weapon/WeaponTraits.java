package com.fundamentum.weapon;

import com.fundamentum.port.FId;
import com.fundamentum.port.FTags;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * One shared vocabulary of weapon traits, so every weapon mod tags its items the same way and every other mod (a combat
 * mod, a skill tree, a quest) can ask about them. A weapon has a trait when it is in the item tag
 * {@code #fundamentum:weapon/<trait>}. Packs and mods fill the tags; Fundamentum fills the vanilla members. Mods add new
 * traits with {@link #add} while they construct, so {@link #all} lists them for tools and docs.
 */
public final class WeaponTraits {
    /** Built-in traits: kinds of weapon, then handling. */
    public static final List<String> BUILT_IN = List.of(
        "sword", "axe", "dagger", "spear", "polearm", "hammer", "mace", "greatsword", "katana", "rapier", "scythe",
        "whip", "flail", "staff", "wand", "bow", "crossbow", "gun", "thrown", "shield", "fist",
        "one_handed", "two_handed", "dual_wield", "light", "heavy", "long_reach", "sweeping", "parrying", "piercing");

    private static final List<String> TRAITS = new CopyOnWriteArrayList<>(BUILT_IN);

    private WeaponTraits() { }

    /** Adds a trait name (no namespace; it lives under {@code #fundamentum:weapon/}). */
    public static void add(String trait) {
        if (!TRAITS.contains(trait)) TRAITS.add(trait);
    }

    public static List<String> all() {
        return List.copyOf(TRAITS);
    }

    public static TagKey<Item> tag(String trait) {
        return FTags.item(FId.of("fundamentum", "weapon/" + trait));
    }

    public static boolean has(ItemStack stack, String trait) {
        return !stack.isEmpty() && stack.is(tag(trait));
    }

    /** Every known trait the stack has. */
    public static List<String> of(ItemStack stack) {
        List<String> out = new ArrayList<>();
        if (stack.isEmpty()) return out;
        for (String t : TRAITS) {
            if (stack.is(tag(t))) out.add(t);
        }
        return out;
    }
}
