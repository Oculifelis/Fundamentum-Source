package com.fundamentum.food;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * What a food or drink gives when it is finished: hunger points, saturation points (added to the player's
 * saturation, capped at their hunger as vanilla does) and the effects it may apply, each with its chance. Immutable;
 * the {@code with...} methods return changed copies. {@code drink} says the item is drunk (it cannot be changed).
 */
public record FoodValues(int nutrition, float saturation, boolean drink, List<Effect> effects) {

    /** One effect the food applies, with its chance from 0 to 1. */
    public record Effect(MobEffectInstance effect, float chance) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Effect e && e.chance == this.chance && e.effect.equals(this.effect);
        }

        @Override
        public int hashCode() {
            return this.effect.hashCode() * 31 + Float.hashCode(this.chance);
        }
    }

    public FoodValues {
        effects = List.copyOf(effects);
    }

    public FoodValues withNutrition(int value) {
        return new FoodValues(value, this.saturation, this.drink, this.effects);
    }

    public FoodValues withSaturation(float value) {
        return new FoodValues(this.nutrition, value, this.drink, this.effects);
    }

    public FoodValues withEffects(List<Effect> value) {
        return new FoodValues(this.nutrition, this.saturation, this.drink, value);
    }

    /** A copy with one more effect. */
    public FoodValues withEffect(MobEffectInstance effect, float chance) {
        List<Effect> l = new ArrayList<>(this.effects);
        l.add(new Effect(effect, chance));
        return withEffects(l);
    }

    /** A copy with hunger and saturation multiplied (hunger rounded, never below 0). */
    public FoodValues scaled(double factor) {
        return new FoodValues((int) Math.max(0, Math.round(this.nutrition * factor)), (float) Math.max(0, this.saturation * factor), this.drink,
            this.effects);
    }
}
