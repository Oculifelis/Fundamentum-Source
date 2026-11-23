package com.fundamentum.port;

import com.fundamentum.food.FoodValues;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;

/**
 * Food values and effects with the same calls on every version. On 26.x and 1.21.1 food is the stack's food data
 * component; on 1.20.1 it is the item's FoodProperties. Saturation is always in points (what is added to the
 * player's saturation), not the 1.20.1 modifier.
 */
public final class FFood {
    private FFood() { }

    /** True when the stack is drunk rather than eaten (potions, milk, honey bottles, modded drinks). */
    public static boolean drink(ItemStack stack) {
        return !stack.isEmpty() && stack.getUseAnimation() == UseAnim.DRINK;
    }

    /** The stack's own food values for this entity, or null when it is neither food nor a drink. */
    public static FoodValues base(LivingEntity entity, ItemStack stack) {
        if (stack.isEmpty()) return null;
        FoodProperties food = stack.get(DataComponents.FOOD);
        boolean drink = drink(stack);
        if (food == null && !drink) return null;
        List<FoodValues.Effect> effects = new ArrayList<>();
        if (food != null) {
            for (FoodProperties.PossibleEffect e : food.effects()) effects.add(new FoodValues.Effect(new MobEffectInstance(e.effect()), e.probability()));
        }
        return new FoodValues(food == null ? 0 : food.nutrition(), food == null ? 0 : food.saturation(), drink, effects);
    }

    /** A new effect instance, or null when the effect id is unknown. */
    public static MobEffectInstance effect(FId id, int duration, int amplifier) {
        Holder<MobEffect> h = BuiltInRegistries.MOB_EFFECT.getHolder(id.vanilla()).<Holder<MobEffect>>map(x -> x).orElse(null);
        return h == null ? null : new MobEffectInstance(h, duration, amplifier);
    }

    public static FId effectId(MobEffectInstance effect) {
        return FId.from(BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()));
    }

    /** Copies of the entity's active effects. */
    public static List<MobEffectInstance> active(LivingEntity entity) {
        List<MobEffectInstance> out = new ArrayList<>();
        for (MobEffectInstance i : entity.getActiveEffects()) out.add(new MobEffectInstance(i));
        return out;
    }

    /** Puts the effects of the given types back the way they were in {@code before} (removed when absent there). */
    public static void restore(LivingEntity entity, Set<FId> types, List<MobEffectInstance> before) {
        for (FId type : types) {
            Holder<MobEffect> h = BuiltInRegistries.MOB_EFFECT.getHolder(type.vanilla()).<Holder<MobEffect>>map(x -> x).orElse(null);
            if (h == null) continue;
            entity.removeEffect(h);
            for (MobEffectInstance i : before) {
                if (i.getEffect().equals(h)) entity.addEffect(new MobEffectInstance(i));
            }
        }
    }

    /** Sends the player's inventory again (after an eat was cancelled, so the client's stack comes back). */
    public static void resync(ServerPlayer player) {
        if (player.connection != null) player.inventoryMenu.sendAllDataToRemote();
    }

    /** The recipe data folder: {@code recipe} on 1.21 and later, {@code recipes} on 1.20.1. */
    public static String recipeFolder() {
        return "recipe";
    }

    /**
     * The server's live recipes for guessing food groups: each result item with its ingredient slots, every slot the
     * item ids it accepts (at most 16). Covers recipes from data and recipes other mods add in code. The first recipe
     * for an item wins; special recipes and recipes that cannot be read are skipped.
     */
    public static java.util.Map<FId, List<List<String>>> liveRecipes(net.minecraft.server.MinecraftServer server) {
        java.util.Map<FId, List<List<String>>> out = new java.util.HashMap<>();
        for (net.minecraft.world.item.crafting.RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            try {
                net.minecraft.world.item.crafting.Recipe<?> recipe = holder.value();
                if (recipe.isSpecial()) continue;
                ItemStack result = recipe.getResultItem(server.registryAccess());
                if (result == null || result.isEmpty()) continue;
                FId id = FId.tryOf(BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
                if (id == null || out.containsKey(id)) continue;
                List<List<String>> slots = new ArrayList<>();
                for (net.minecraft.world.item.crafting.Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) continue;
                    List<String> alts = java.util.Arrays.stream(ingredient.getItems()).limit(16)
                        .map(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString()).distinct().toList();
                    if (!alts.isEmpty()) slots.add(alts);
                }
                if (!slots.isEmpty()) out.put(id, slots);
            } catch (RuntimeException e) {
                // a recipe that cannot show its result or ingredients is left out
            }
        }
        return out;
    }
}
