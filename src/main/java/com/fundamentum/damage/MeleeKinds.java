package com.fundamentum.damage;

import com.fundamentum.port.FId;
import com.fundamentum.port.FTags;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Melee kinds: slash, pierce and blunt (mods may add more with {@link #add}). A hit is of a kind when its damage type is
 * in {@code #fundamentum:kind/<k>}, or else when the weapon that dealt it is in the item tag {@code #fundamentum:kind/<k>}.
 * Armour, creature resistances and effects read the kind with {@link #of}; Fundamentum itself attaches no numbers to it.
 */
public final class MeleeKinds {
    public static final String SLASH = "slash";
    public static final String PIERCE = "pierce";
    public static final String BLUNT = "blunt";

    private static final List<String> KINDS = new CopyOnWriteArrayList<>(List.of(SLASH, PIERCE, BLUNT));

    private MeleeKinds() { }

    public static void add(String kind) {
        if (!KINDS.contains(kind)) KINDS.add(kind);
    }

    public static List<String> all() {
        return List.copyOf(KINDS);
    }

    public static TagKey<DamageType> damageTag(String kind) {
        return FTags.of(Registries.DAMAGE_TYPE, FId.of("fundamentum", "kind/" + kind));
    }

    public static TagKey<Item> itemTag(String kind) {
        return FTags.item(FId.of("fundamentum", "kind/" + kind));
    }

    /** The weapon's kind, or null when it has none. */
    public static String of(ItemStack weapon) {
        if (weapon == null || weapon.isEmpty()) return null;
        for (String k : KINDS) {
            if (weapon.is(itemTag(k))) return k;
        }
        return null;
    }

    /** The hit's kind, by damage type first and then by weapon; null when it has none. */
    public static String of(DamageSource source) {
        for (String k : KINDS) {
            if (source.is(damageTag(k))) return k;
        }
        return of(Elements.weapon(source));
    }

    public static boolean is(DamageSource source, String kind) {
        return kind.equals(of(source));
    }
}
