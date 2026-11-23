package com.fundamentum.effect;

import com.fundamentum.attribute.AttributeBonus;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.ai.attributes.Attribute;
import org.jspecify.annotations.Nullable;

/**
 * A status effect, from data ({@code data/<ns>/fundamentum/effects/<name>.json}) or from code ({@link #builder}). Every field
 * is optional:
 * <pre>{
 *   "category": "harmful",
 *   "color": "#8A1C1C",
 *   "icon": "mypack:effect/bleed",
 *   "stacking": "amplify",
 *   "max_amplifier": 4,
 *   "max_stacks": 1,
 *   "element": "fundamentum:poison",
 *   "cures": ["fundamentum:cures/milk", "fundamentum:cures/holy"],
 *   "attributes": [{"attribute": "minecraft:movement_speed", "amount": -0.05, "operation": "add_multiplied_total"}],
 *   "per_level": true,
 *   "actions": [{"type": "fundamentum:damage", "amount": 1, "interval": 20}]
 * }</pre>
 * {@code stacking}: {@code refresh} (the default: the higher amplifier and the longer time win), {@code extend} (the new
 * duration is added on), {@code amplify} (amplifiers add up to {@code max_amplifier}, the time refreshes) or
 * {@code independent} (separate stacks, up to {@code max_stacks}, each with its own time; the level is their sum).
 * {@code cures} defaults to milk only; {@code []} means nothing cures it. With {@code per_level} (the default) attribute
 * amounts are multiplied by the level.
 */
public record EffectDefinition(Identifier id, Category category, int color, @Nullable Identifier icon, List<AttributeBonus> attributes,
                               boolean perLevel, List<EffectAction> actions, @Nullable Identifier element, Set<Identifier> cures,
                               Stacking stacking, int maxAmplifier, int maxStacks) {
    public enum Category { BENEFICIAL, HARMFUL, NEUTRAL }

    public enum Stacking { REFRESH, EXTEND, AMPLIFY, INDEPENDENT }

    public static EffectDefinition fromJson(Identifier id, JsonObject json) {
        Builder b = builder(id);
        if (json.has("category")) {
            b.category(Category.valueOf(GsonHelper.getAsString(json, "category").toUpperCase(Locale.ROOT)));
        }
        if (json.has("color")) {
            JsonElement c = json.get("color");
            String text = c.getAsString();
            b.color(c.getAsJsonPrimitive().isNumber() ? c.getAsInt() : Integer.parseInt(text.startsWith("#") ? text.substring(1) : text, 16));
        }
        if (json.has("icon")) {
            b.icon(Identifier.parse(GsonHelper.getAsString(json, "icon")));
        }
        if (json.has("stacking")) {
            b.stacking(Stacking.valueOf(GsonHelper.getAsString(json, "stacking").toUpperCase(Locale.ROOT)));
        }
        b.maxAmplifier(GsonHelper.getAsInt(json, "max_amplifier", b.maxAmplifier));
        b.maxStacks(GsonHelper.getAsInt(json, "max_stacks", b.maxStacks));
        if (json.has("element")) {
            b.element(Identifier.parse(GsonHelper.getAsString(json, "element")));
        }
        if (json.has("cures")) {
            b.cures.clear();
            json.getAsJsonArray("cures").forEach(e -> b.cures.add(Identifier.parse(e.getAsString())));
        }
        if (json.has("attributes")) {
            AttributeBonus.parseList(json.get("attributes")).forEach(b.attributes::add);
        }
        b.perLevel(GsonHelper.getAsBoolean(json, "per_level", true));
        if (json.has("actions")) {
            json.getAsJsonArray("actions").forEach(e -> b.action(EffectActions.parse(e.getAsJsonObject())));
        }
        return b.build();
    }

    public static Builder builder(Identifier id) {
        return new Builder(id);
    }

    /** Builds an effect in code; {@link #register} hands it to {@link Effects#register} (data with the same id wins). */
    public static final class Builder {
        private final Identifier id;
        private Category category = Category.NEUTRAL;
        private int color = 0xFFFFFF;
        private @Nullable Identifier icon;
        private final List<AttributeBonus> attributes = new ArrayList<>();
        private boolean perLevel = true;
        private final List<EffectAction> actions = new ArrayList<>();
        private @Nullable Identifier element;
        private final Set<Identifier> cures = new LinkedHashSet<>(Set.of(Effects.CURE_MILK));
        private Stacking stacking = Stacking.REFRESH;
        private int maxAmplifier = 255;
        private int maxStacks = 8;

        Builder(Identifier id) {
            this.id = id;
        }

        public Builder category(Category category) { this.category = category; return this; }
        public Builder color(int color) { this.color = color & 0xFFFFFF; return this; }
        public Builder icon(Identifier icon) { this.icon = icon; return this; }
        public Builder attribute(Holder<Attribute> attribute, double amount, AttributeBonus.Op operation) {
            this.attributes.add(AttributeBonus.of(attribute, amount, operation));
            return this;
        }
        public Builder perLevel(boolean perLevel) { this.perLevel = perLevel; return this; }
        public Builder action(EffectAction action) { this.actions.add(action); return this; }
        public Builder element(Identifier element) { this.element = element; return this; }
        /** Replaces the cure tags (milk by default). */
        public Builder cures(Identifier... cures) { this.cures.clear(); this.cures.addAll(List.of(cures)); return this; }
        public Builder stacking(Stacking stacking) { this.stacking = stacking; return this; }
        public Builder maxAmplifier(int maxAmplifier) { this.maxAmplifier = Math.max(0, maxAmplifier); return this; }
        public Builder maxStacks(int maxStacks) { this.maxStacks = Math.max(1, maxStacks); return this; }

        public EffectDefinition build() {
            return new EffectDefinition(this.id, this.category, this.color, this.icon, List.copyOf(this.attributes), this.perLevel,
                List.copyOf(this.actions), this.element, Set.copyOf(this.cures), this.stacking, this.maxAmplifier, this.maxStacks);
        }

        public EffectDefinition register() {
            EffectDefinition def = build();
            Effects.register(def);
            return def;
        }
    }
}
