package com.fundamentum.mixin;

import com.fundamentum.soulfire.SoulFireSprites;
import com.fundamentum.soulfire.SoulFlames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The first-person screen fire uses vanilla's soul_fire_1 sprite while the player is soul burning. */
@Mixin(ScreenEffectRenderer.class)
public abstract class SoulScreenFireMixin {
    @Shadow @Final private Minecraft minecraft;

    @Redirect(method = "renderScreenEffect", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/resources/model/sprite/SpriteGetter;get(Lnet/minecraft/client/resources/model/sprite/SpriteId;)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
    private TextureAtlasSprite fundamentum$soulScreenFire(SpriteGetter sprites, SpriteId sprite) {
        boolean soul = this.minecraft.player != null && SoulFlames.isSoulBurning(this.minecraft.player);
        return sprites.get(soul ? SoulFireSprites.swap(sprite) : sprite);
    }
}
