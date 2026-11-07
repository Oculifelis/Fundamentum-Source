package com.fundamentum.mixin;

import com.fundamentum.soulfire.SoulFireSprites;
import com.fundamentum.soulfire.SoulFireState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A soul-burning entity's third-person flames use vanilla's soul_fire_0/1 sprites, same render type as vanilla fire.
 * 26.2: flames are built per group ({@code buildGroup} looks fire_0/fire_1 up once and hands them to {@code prepare}
 * for each submit), so the sprites are swapped per submit on the {@code prepare} call.
 */
@Mixin(FlameFeatureRenderer.class)
public abstract class SoulFlameMixin {
    @WrapOperation(method = "buildGroup", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/feature/FlameFeatureRenderer;prepare(Lnet/minecraft/client/renderer/feature/FlameFeatureRenderer$Submit;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"))
    private void fundamentum$soulSprites(FlameFeatureRenderer self, FlameFeatureRenderer.Submit submit, VertexConsumer buffer,
                                         TextureAtlasSprite fire1, TextureAtlasSprite fire2, Operation<Void> original,
                                         @Local(argsOnly = true) FeatureFrameContext context) {
        if (((SoulFireState) submit.entityRenderState()).fundamentum$soulFire()) {
            fire1 = context.atlasManager().get(SoulFireSprites.swap(ModelBakery.FIRE_0));
            fire2 = context.atlasManager().get(SoulFireSprites.swap(ModelBakery.FIRE_1));
        }
        original.call(self, submit, buffer, fire1, fire2);
    }
}
