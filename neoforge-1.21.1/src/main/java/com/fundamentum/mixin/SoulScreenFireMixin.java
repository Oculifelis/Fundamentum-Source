package com.fundamentum.mixin;

import com.fundamentum.soulfire.SoulFireSprites;
import com.fundamentum.soulfire.SoulFlames;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The first-person screen fire uses vanilla's soul_fire_1 sprite while the player is soul burning. */
@Mixin(ScreenEffectRenderer.class)
public abstract class SoulScreenFireMixin {
    @Redirect(method = "renderFire", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/resources/model/Material;sprite()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
    private static TextureAtlasSprite fundamentum$soulScreenFire(Material sprite, Minecraft minecraft, PoseStack poseStack) {
        boolean soul = minecraft.player != null && SoulFlames.isSoulBurning(minecraft.player);
        return (soul ? SoulFireSprites.swap(sprite) : sprite).sprite();
    }
}
