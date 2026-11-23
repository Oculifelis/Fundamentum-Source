package com.fundamentum.attribute;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.Holder;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
        ResourceLocation id = ResourceLocation.parse(GsonHelper.getAsString(json, "attribute"));
        Holder<Attribute> attribute = lookup(id);
        if (attribute == null) {
            throw new IllegalArgumentException(id + " is not an attribute");
        }
        return new AttributeBonus(attribute, GsonHelper.getAsDouble(json, "amount"), op(GsonHelper.getAsString(json, "operation", "add_value")));
    }

    /**
     * The attribute with this id. Vanilla ids here still carry their group ({@code minecraft:generic.armor}); the newer
     * names ({@code minecraft:armor}) are accepted too, so the same data works on every version.
     */
    public static @Nullable Holder<Attribute> lookup(ResourceLocation id) {
        Optional<? extends Holder<Attribute>> found = BuiltInRegistries.ATTRIBUTE.getHolder(id);
        if (found.isEmpty() && "minecraft".equals(id.getNamespace()) && id.getPath().indexOf('.') < 0) {
            for (String group : GROUPS) {
                found = BuiltInRegistries.ATTRIBUTE.getHolder(ResourceLocation.withDefaultNamespace(group + id.getPath()));
                if (found.isPresent()) {
                    break;
                }
            }
        }
        return found.isPresent() ? found.get() : null;
    }

    private static final String[] GROUPS = {"generic.", "player.", "zombie."};

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
    public void apply(LivingEntity entity, ResourceLocation id, double scale) {
        AttributeInstance instance = entity.getAttribute(this.attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(id);
        instance.addTransientModifier(new AttributeModifier(id, this.amount * scale, vanilla(this.operation)));
    }

    /** Takes the bonus added under {@code id} off again. */
    public void remove(LivingEntity entity, ResourceLocation id) {
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
