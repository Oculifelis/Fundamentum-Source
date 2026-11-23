package com.fundamentum.compat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * The one place where Fundamentum's weapon types meet Better Combat (an optional mod, never a dependency).
 *
 * <p>The rule every type follows: when Better Combat is loaded it owns left-click attacks (timing, combo sequence, hitbox,
 * swing animation), and a type's left-click moves are written as Better Combat {@code weapon_attributes} data instead of a
 * second handler of our own. A type's own mechanics live on right click, hold or sneak, so nothing swings or hits twice
 * and nothing fights Better Combat's cooldown. Without Better Combat, the type's own fallback runs.
 *
 * <p>Each type registers here once with its item tag, its preset ({@code data/fundamentum/weapon_attributes/<name>.json},
 * an item points at it with {@code {"parent": "fundamentum:<name>"}}) and who owns its left click:
 * <ul>
 * <li>{@link LeftClick#BETTER_COMBAT}: Better Combat plays the left click when loaded; the type's left-click handler
 * checks {@link #drivesLeftClick} and steps aside.</li>
 * <li>{@link LeftClick#OWN}: the left click cannot be written as Better Combat data (a whip's cord is a simulation), so
 * the type keeps it. Such items must never get {@code weapon_attributes}; the type has no preset.</li>
 * </ul>
 * The seam and the presets ship on every Minecraft version and loader, also where Better Combat has no build yet, so
 * nothing needs redoing when one arrives.
 */
public final class BetterCombat {
    public static final String MOD_ID = "bettercombat";

    /** Who plays a type's left click while Better Combat is loaded. */
    public enum LeftClick { BETTER_COMBAT, OWN }

    /** One registered weapon type. {@code preset} is null for {@link LeftClick#OWN} types. */
    public record WeaponType(String name, TagKey<Item> items, @Nullable ResourceLocation preset, LeftClick leftClick) {}

    private static final List<WeaponType> TYPES = new CopyOnWriteArrayList<>();
    private static @Nullable Boolean loaded;

    /** Registers a weapon type (call while the mod is constructed). Returns it for convenience. */
    public static WeaponType register(String name, TagKey<Item> items, @Nullable ResourceLocation preset, LeftClick leftClick) {
        if (leftClick == LeftClick.BETTER_COMBAT && preset == null) {
            throw new IllegalArgumentException("weapon type " + name + " gives its left click to Better Combat but has no preset");
        }
        WeaponType type = new WeaponType(name, items, preset, leftClick);
        TYPES.removeIf(t -> t.name.equals(name));
        TYPES.add(type);
        return type;
    }

    /** Shorthand for an item tag in the fundamentum namespace. */
    public static TagKey<Item> tag(String path) {
        return TagKey.create(Registries.ITEM, new ResourceLocation("fundamentum", path));
    }

    /** Every registered type. */
    public static List<WeaponType> types() {
        return List.copyOf(TYPES);
    }

    /** True when Better Combat is installed. */
    public static boolean loaded() {
        if (loaded == null) {
            loaded = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
        }
        return loaded;
    }

    /** The registered type {@code stack} belongs to, or null. */
    public static @Nullable WeaponType typeOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        for (WeaponType t : TYPES) {
            if (stack.is(t.items)) {
                return t;
            }
        }
        return null;
    }

    /**
     * True when Better Combat plays this item's left click: it is loaded and the item's type gives its left click to it.
     * A left-click handler of ours returns early when this is true.
     */
    public static boolean drivesLeftClick(ItemStack stack) {
        if (!loaded()) {
            return false;
        }
        WeaponType t = typeOf(stack);
        return t != null && t.leftClick == LeftClick.BETTER_COMBAT;
    }

    /**
     * The same for an item already known to be of {@code type} (say, through its own data file rather than the tag):
     * true when Better Combat is loaded and the type gives its left click to it.
     */
    public static boolean drivesLeftClick(WeaponType type) {
        return loaded() && type.leftClick == LeftClick.BETTER_COMBAT;
    }

    /** For in-game tests: pretend Better Combat is (or is not) loaded; null goes back to the real answer. */
    public static void override(@Nullable Boolean value) {
        loaded = value;
    }

    private BetterCombat() {}
}
