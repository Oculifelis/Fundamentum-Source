package com.fundamentum.mixin;

import com.fundamentum.soulfire.SoulFireState;
import com.fundamentum.soulfire.SoulFlames;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class SoulFireExtractMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void fundamentum$soulFire(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
        ((SoulFireState) state).fundamentum$setSoulFire(state.displayFireAnimation && SoulFlames.isSoulBurning(entity));
    }
}
