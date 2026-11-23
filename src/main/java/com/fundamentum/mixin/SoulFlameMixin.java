package com.fundamentum.mixin;

import com.fundamentum.soulfire.SoulFireSprites;
import com.fundamentum.soulfire.SoulFireState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.client.resources.model.sprite.SpriteId;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A soul-burning entity's third-person flames use vanilla's soul_fire_0/1 sprites, same render type as vanilla fire. */
@Mixin(FlameFeatureRenderer.class)
public abstract class SoulFlameMixin {
    @Redirect(method = "renderFlame", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/resources/model/sprite/AtlasManager;get(Lnet/minecraft/client/resources/model/sprite/SpriteId;)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
    private TextureAtlasSprite fundamentum$soulSprite(AtlasManager atlas, SpriteId sprite, PoseStack.Pose pose, MultiBufferSource bufferSource,
                                                      EntityRenderState state, Quaternionf rotation, AtlasManager atlasManager) {
        return atlas.get(((SoulFireState) state).fundamentum$soulFire() ? SoulFireSprites.swap(sprite) : sprite);
    }
}
