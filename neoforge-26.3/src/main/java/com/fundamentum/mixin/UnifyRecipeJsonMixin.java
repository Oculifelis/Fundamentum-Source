package com.fundamentum.mixin;

import com.fundamentum.unify.UnifyHooks;
import com.google.gson.JsonElement;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hands each datapack registry element's JSON to unification as it is parsed, so recipe outputs are rewritten before decoding. */
@Mixin(targets = "net.minecraft.resources.RegistryLoadTask$PendingRegistration")
public abstract class UnifyRecipeJsonMixin {
    @ModifyExpressionValue(method = "loadFromResource",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/StrictJsonParser;parse(Ljava/io/Reader;)Lcom/google/gson/JsonElement;"))
    private static JsonElement fundamentum$unify(JsonElement json, @Local(argsOnly = true) ResourceKey<?> elementKey) {
        return UnifyHooks.recipeJson(elementKey, json);
    }
}
