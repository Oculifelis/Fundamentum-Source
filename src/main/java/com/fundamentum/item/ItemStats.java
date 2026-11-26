package com.fundamentum.item;

import com.fundamentum.attribute.AttributeBonus;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ItemAttributeModifierEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Item stats set by data: {@code data/<namespace>/fundamentum/item_stats/<name>.json}, keyed by {@code "item"}.
 * <pre>{"item": "mymod:ruby_sword", "attack_damage": 8, "attack_speed": 1.6,
 *  "modifiers": [{"attribute": "minecraft:generic.movement_speed", "amount": 0.1, "operation": "add_multiplied_base", "slot": "mainhand"}]}</pre>
 * {@code attack_damage} and {@code attack_speed} are the totals the tooltip shows (the player's base 1 damage and 4 speed
 * included) and replace the item's own main-hand values. {@code modifiers} are added on top; {@code slot} is one of
 * mainhand (the default), offhand, hand, head, chest, legs, feet, armor or any. A mod ships its items' values as files
 * here, so packs override them like any other entry. Stats apply when an item's modifiers are read, so they follow
 * /reload; a stack already held picks them up when it is next equipped.
 */
public final class ItemStats {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    // Item.BASE_ATTACK_DAMAGE_UUID and BASE_ATTACK_SPEED_UUID (protected in Item)
    private static final UUID BASE_DAMAGE = UUID.fromString("CB3F55D3-645C-4F38-A497-9C13A33DB5CF");
    private static final UUID BASE_SPEED = UUID.fromString("FA233E1C-4180-4865-B01B-BCCE9785ACA3");
    private static final double PLAYER_DAMAGE = 1.0;
    private static final double PLAYER_SPEED = 4.0;

    private record Extra(Attribute attribute, AttributeModifier modifier, Set<EquipmentSlot> slots) { }

    private record Entry(Double damage, Double speed, List<Extra> extras) { }

    private static volatile Map<ResourceLocation, Entry> entries = Map.of();

    public static final DataTable<JsonObject> TABLE = DataTables.registerJson(new ResourceLocation("fundamentum", "item_stats"),
        "fundamentum/item_stats").keyField("item").synced().onLoad(t -> entries = read(t.rawEntries()));

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener(ItemStats::modifiers);
    }

    private static Map<ResourceLocation, Entry> read(Map<ResourceLocation, JsonObject> raw) {
        Map<ResourceLocation, Entry> out = new HashMap<>();
        for (var e : raw.entrySet()) {
            try {
                out.put(e.getKey(), parse(e.getKey(), e.getValue()));
            } catch (RuntimeException ex) {
                LOG.error("item_stats {} skipped: {}", e.getKey(), ex.getMessage());
            }
        }
        return Map.copyOf(out);
    }

    private static Entry parse(ResourceLocation item, JsonObject json) {
        Double damage = json.has("attack_damage") ? GsonHelper.getAsDouble(json, "attack_damage") : null;
        Double speed = json.has("attack_speed") ? GsonHelper.getAsDouble(json, "attack_speed") : null;
        List<Extra> extras = new ArrayList<>();
        if (json.has("modifiers")) {
            int i = 0;
            for (JsonElement m : json.getAsJsonArray("modifiers")) {
                JsonObject o = m.getAsJsonObject();
                AttributeBonus bonus = AttributeBonus.parse(o);
                if (bonus.attribute() == null) {
                    throw new IllegalArgumentException("item_stats modifiers take an attribute, not a stat");
                }
                String name = "fundamentum:item_stats/" + item + "/" + i++;
                UUID id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
                extras.add(new Extra(bonus.attribute().value(), new AttributeModifier(id, name, bonus.amount(), operation(bonus.operation())),
                    slots(GsonHelper.getAsString(o, "slot", "mainhand"))));
            }
        }
        return new Entry(damage, speed, List.copyOf(extras));
    }

    private static AttributeModifier.Operation operation(AttributeBonus.Op op) {
        return switch (op) {
            case ADD_VALUE -> AttributeModifier.Operation.ADDITION;
            case ADD_MULTIPLIED_BASE -> AttributeModifier.Operation.MULTIPLY_BASE;
            case ADD_MULTIPLIED_TOTAL -> AttributeModifier.Operation.MULTIPLY_TOTAL;
        };
    }

    private static Set<EquipmentSlot> slots(String name) {
        return switch (name) {
            case "any" -> EnumSet.allOf(EquipmentSlot.class);
            case "hand" -> EnumSet.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND);
            case "armor" -> EnumSet.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
            default -> EnumSet.of(EquipmentSlot.byName(name));
        };
    }

    private static void modifiers(ItemAttributeModifierEvent event) {
        Map<ResourceLocation, Entry> map = entries;
        if (map.isEmpty() || event.getItemStack().isEmpty()) {
            return;
        }
        Entry entry = map.get(BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem()));
        if (entry == null) {
            return;
        }
        if (event.getSlotType() == EquipmentSlot.MAINHAND) {
            if (entry.damage() != null) {
                replace(event, Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_DAMAGE, "Weapon modifier",
                    entry.damage() - PLAYER_DAMAGE, AttributeModifier.Operation.ADDITION));
            }
            if (entry.speed() != null) {
                replace(event, Attributes.ATTACK_SPEED, new AttributeModifier(BASE_SPEED, "Weapon modifier",
                    entry.speed() - PLAYER_SPEED, AttributeModifier.Operation.ADDITION));
            }
        }
        for (Extra x : entry.extras()) {
            if (x.slots().contains(event.getSlotType())) {
                replace(event, x.attribute(), x.modifier());
            }
        }
    }

    private static void replace(ItemAttributeModifierEvent event, Attribute attribute, AttributeModifier modifier) {
        for (AttributeModifier old : List.copyOf(event.getModifiers().get(attribute))) {
            if (old.getId().equals(modifier.getId())) {
                event.removeModifier(attribute, old);
            }
        }
        event.addModifier(attribute, modifier);
    }

    private ItemStats() { }
}
