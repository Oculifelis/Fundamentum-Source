package com.fundamentum.mixin;

import com.fundamentum.unify.UnifyHooks;
import com.google.gson.JsonElement;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands the recipe JSONs of a load to unification before the recipe manager decodes them. */
@Mixin(RecipeManager.class)
public abstract class UnifyRecipeManagerMixin {
    @Inject(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"))
    private void fundamentum$unify(Map<ResourceLocation, JsonElement> recipes, ResourceManager manager, ProfilerFiller profiler, CallbackInfo ci) {
        UnifyHooks.recipes(recipes, manager);
    }
}
