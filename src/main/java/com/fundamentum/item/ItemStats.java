package com.fundamentum.item;

import com.fundamentum.attribute.AttributeBonus;
import com.fundamentum.data.DataTable;
import com.fundamentum.data.DataTables;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Item stats set by data: {@code data/<namespace>/fundamentum/item_stats/<name>.json}, keyed by {@code "item"}.
 * <pre>{"item": "mymod:ruby_sword", "attack_damage": 8, "attack_speed": 1.6,
 *  "modifiers": [{"attribute": "minecraft:entity_interaction_range", "amount": 1, "operation": "add_value", "slot": "mainhand"}]}</pre>
 * {@code attack_damage} and {@code attack_speed} are the totals the tooltip shows (the player's base 1 damage and 4 speed
 * included) and replace the item's own main-hand values. {@code modifiers} are added on top; {@code slot} defaults to
 * {@code mainhand}. A mod ships its items' values as files here, so packs override them like any other entry. Stats apply
 * when an item's modifiers are read, so they follow /reload; a stack already held picks them up when it is next equipped.
 */
public final class ItemStats {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    private static final ResourceLocation BASE_DAMAGE = ResourceLocation.withDefaultNamespace("base_attack_damage");
    private static final ResourceLocation BASE_SPEED = ResourceLocation.withDefaultNamespace("base_attack_speed");
    private static final double PLAYER_DAMAGE = 1.0;
    private static final double PLAYER_SPEED = 4.0;

    private record Extra(Holder<Attribute> attribute, AttributeModifier modifier, EquipmentSlotGroup slot) { }

    private record Entry(Double damage, Double speed, List<Extra> extras) { }

    private static volatile Map<ResourceLocation, Entry> entries = Map.of();

    public static final DataTable<JsonObject> TABLE = DataTables.registerJson(ResourceLocation.fromNamespaceAndPath("fundamentum", "item_stats"),
        "fundamentum/item_stats").keyField("item").synced().onLoad(t -> entries = read(t.rawEntries()));

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ItemStats::modifiers);
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
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath("fundamentum", "item_stats/" + item.getNamespace() + "/" + item.getPath() + "/" + i++);
                extras.add(new Extra(bonus.attribute(), new AttributeModifier(id, bonus.amount(), operation(bonus.operation())),
                    slot(GsonHelper.getAsString(o, "slot", "mainhand"))));
            }
        }
        return new Entry(damage, speed, List.copyOf(extras));
    }

    private static AttributeModifier.Operation operation(AttributeBonus.Op op) {
        return switch (op) {
            case ADD_VALUE -> AttributeModifier.Operation.ADD_VALUE;
            case ADD_MULTIPLIED_BASE -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case ADD_MULTIPLIED_TOTAL -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
        };
    }

    private static EquipmentSlotGroup slot(String name) {
        for (EquipmentSlotGroup g : EquipmentSlotGroup.values()) {
            if (g.getSerializedName().equals(name)) {
                return g;
            }
        }
        throw new IllegalArgumentException("unknown slot " + name);
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
        if (entry.damage() != null) {
            event.replaceModifier(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_DAMAGE, entry.damage() - PLAYER_DAMAGE,
                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        }
        if (entry.speed() != null) {
            event.replaceModifier(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_SPEED, entry.speed() - PLAYER_SPEED,
                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        }
        for (Extra x : entry.extras()) {
            event.replaceModifier(x.attribute(), x.modifier(), x.slot());
        }
    }

    private ItemStats() { }
}
