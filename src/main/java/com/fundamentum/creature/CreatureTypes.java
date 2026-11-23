package com.fundamentum.creature;

import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Creature types shared by every mod: entity type tags {@code #fundamentum:creature/<type>} (undead, arthropod, aquatic,
 * illager, construct, boss to start; any mod may add types or members). Damage rules in
 * {@code data/<namespace>/fundamentum/creature_damage/<name>.json} scale damage against a type:
 * {@code {"creature": "undead", "weapon": "#fundamentum:weapon/holy", "damage": "#minecraft:is_fire", "multiplier": 1.5}}.
 * "weapon" (item tag the weapon must be in) and "damage" (damage type tag) are optional; a rule with neither applies to
 * every hit on that creature type. Matching rules multiply together.
 */
public final class CreatureTypes {
    public record Rule(TagKey<EntityType<?>> creature, TagKey<Item> weapon, TagKey<DamageType> damage, double multiplier) {
        static Rule fromJson(JsonObject json) {
            return new Rule(type(json.get("creature").getAsString()),
                json.has("weapon") ? TagKey.create(Registries.ITEM, tagId(json.get("weapon").getAsString())) : null,
                json.has("damage") ? TagKey.create(Registries.DAMAGE_TYPE, tagId(json.get("damage").getAsString())) : null,
                json.get("multiplier").getAsDouble());
        }

        boolean matches(Entity target, DamageSource source) {
            if (!target.getType().is(this.creature) || this.damage != null && !source.is(this.damage)) {
                return false;
            }
            if (this.weapon == null) {
                return true;
            }
            ItemStack stack = source.getWeaponItem();
            return stack != null && stack.is(this.weapon);
        }
    }

    public static final DataTable<Rule> DAMAGE_RULES = DataTables
        .register(ResourceLocation.fromNamespaceAndPath("fundamentum", "creature_damage"), "fundamentum/creature_damage", Rule::fromJson)
        .synced();

    /** The tag of a creature type: "undead" or "mymod:spirit" (a plain name is in the fundamentum namespace). */
    public static TagKey<EntityType<?>> type(String name) {
        ResourceLocation id = name.contains(":") ? ResourceLocation.parse(name) : ResourceLocation.fromNamespaceAndPath("fundamentum", name);
        return TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "creature/" + id.getPath()));
    }

    public static boolean is(Entity entity, String type) {
        return entity.getType().is(type(type));
    }

    /** The product of every rule that matches this hit (1 when none do). */
    public static double multiplier(Entity target, DamageSource source) {
        double m = 1;
        for (Rule rule : DAMAGE_RULES.entries().values()) {
            if (rule.matches(target, source)) {
                m *= rule.multiplier();
            }
        }
        return m;
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(CreatureTypes::onDamage);
    }

    private static void onDamage(LivingIncomingDamageEvent event) {
        double m = multiplier(event.getEntity(), event.getSource());
        if (m != 1) {
            event.setAmount((float) (event.getAmount() * m));
        }
    }

    private static ResourceLocation tagId(String tag) {
        return ResourceLocation.parse(tag.startsWith("#") ? tag.substring(1) : tag);
    }

    private CreatureTypes() {}
}
