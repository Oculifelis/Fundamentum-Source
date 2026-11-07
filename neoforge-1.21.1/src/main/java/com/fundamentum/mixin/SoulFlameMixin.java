package com.fundamentum.mixin;

import com.fundamentum.soulfire.SoulFireSprites;
import com.fundamentum.soulfire.SoulFlames;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A soul-burning entity's third-person flames use vanilla's soul_fire_0/1 sprites, same render type as vanilla fire. */
@Mixin(EntityRenderDispatcher.class)
public abstract class SoulFlameMixin {
    @Redirect(method = "renderFlame", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/resources/model/Material;sprite()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
    private TextureAtlasSprite fundamentum$soulSprite(Material sprite, PoseStack poseStack, MultiBufferSource bufferSource, Entity entity, Quaternionf rotation) {
        return (SoulFlames.isSoulBurning(entity) ? SoulFireSprites.swap(sprite) : sprite).sprite();
    }
}
