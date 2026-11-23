package com.fundamentum.attribute;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

/**
 * One attribute modifier described in data, shared by status effects and armour set tiers:
 * <pre>{"attribute": "minecraft:max_health", "amount": 4, "operation": "add_value"}</pre>
 * {@code operation} is {@code add_value} (the default), {@code add_multiplied_base} or {@code add_multiplied_total}; the older
 * names {@code addition}, {@code multiply_base} and {@code multiply_total} are read too, so one file works on every version.
 * Modifiers are transient: whoever applies one removes it again by the same id.
 */
public record AttributeBonus(Holder<Attribute> attribute, double amount, Op operation) {
    /** The operation, by the same names on every version. */
    public enum Op { ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL }

    public static AttributeBonus of(Holder<Attribute> attribute, double amount, Op operation) {
        return new AttributeBonus(attribute, amount, operation);
    }

    public static AttributeBonus parse(JsonObject json) {
        Identifier id = Identifier.parse(GsonHelper.getAsString(json, "attribute"));
        Holder<Attribute> attribute = BuiltInRegistries.ATTRIBUTE.get(id).<Holder<Attribute>>map(h -> h)
            .orElseThrow(() -> new IllegalArgumentException(id + " is not an attribute"));
        return new AttributeBonus(attribute, GsonHelper.getAsDouble(json, "amount"), op(GsonHelper.getAsString(json, "operation", "add_value")));
    }

    /** A list of bonuses, or one bonus object, or nothing. */
    public static List<AttributeBonus> parseList(JsonElement json) {
        List<AttributeBonus> out = new ArrayList<>();
        if (json == null || json.isJsonNull()) {
            return out;
        }
        if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(e -> out.add(parse(e.getAsJsonObject())));
        } else {
            out.add(parse(json.getAsJsonObject()));
        }
        return List.copyOf(out);
    }

    public static Op op(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "add_value", "addition", "add" -> Op.ADD_VALUE;
            case "add_multiplied_base", "multiply_base" -> Op.ADD_MULTIPLIED_BASE;
            case "add_multiplied_total", "multiply_total" -> Op.ADD_MULTIPLIED_TOTAL;
            default -> throw new IllegalArgumentException("unknown attribute operation " + name);
        };
    }

    private static AttributeModifier.Operation vanilla(Op op) {
        return switch (op) {
            case ADD_VALUE -> AttributeModifier.Operation.ADD_VALUE;
            case ADD_MULTIPLIED_BASE -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case ADD_MULTIPLIED_TOTAL -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
        };
    }

    /** Adds (or replaces) this bonus on {@code entity} under {@code id}, its amount times {@code scale}. */
    public void apply(LivingEntity entity, Identifier id, double scale) {
        AttributeInstance instance = entity.getAttribute(this.attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(id);
        instance.addTransientModifier(new AttributeModifier(id, this.amount * scale, vanilla(this.operation)));
    }

    /** Takes the bonus added under {@code id} off again. */
    public void remove(LivingEntity entity, Identifier id) {
        AttributeInstance instance = entity.getAttribute(this.attribute);
        if (instance != null) {
            instance.removeModifier(id);
        }
    }

    /** A short line such as "+4 Max Health" or "+10% Speed", for tooltips. */
    public Component describe(double scale) {
        double v = this.amount * scale;
        String number = this.operation == Op.ADD_VALUE ? trim(v) : trim(v * 100) + "%";
        return Component.literal((v >= 0 ? "+" : "") + number + " ").append(Component.translatable(this.attribute.value().getDescriptionId()));
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "");
    }
}
