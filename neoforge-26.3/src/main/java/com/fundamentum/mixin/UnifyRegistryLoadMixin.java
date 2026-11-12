package com.fundamentum.mixin;

import com.fundamentum.unify.UnifyHooks;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceManagerRegistryLoadTask;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets unification's recipe hook see the resource manager of a datapack registry load (for the tags and the priority files). */
@Mixin(ResourceManagerRegistryLoadTask.class)
public abstract class UnifyRegistryLoadMixin {
    @Shadow @Final private ResourceManager resourceManager;

    @Inject(method = "load", at = @At("HEAD"))
    private void fundamentum$unifyManager(RegistryOps.RegistryInfoLookup context, Executor executor, CallbackInfoReturnable<CompletableFuture<?>> cir) {
        UnifyHooks.LOADING = this.resourceManager;
    }
}
