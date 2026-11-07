package com.fundamentum.mixin;

import com.fundamentum.soulfire.SoulFireSprites;
import com.fundamentum.soulfire.SoulFlames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteGetter;
import net.minecraft.client.resources.model.sprite.SpriteId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The first-person screen fire uses vanilla's soul_fire_1 sprite while the player is soul burning.
 * 26.2: the method is {@code submit}.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class SoulScreenFireMixin {
    @Redirect(method = "submit", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/resources/model/sprite/SpriteGetter;get(Lnet/minecraft/client/resources/model/sprite/SpriteId;)Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
    private TextureAtlasSprite fundamentum$soulScreenFire(SpriteGetter sprites, SpriteId sprite) {
        Minecraft mc = Minecraft.getInstance();
        boolean soul = mc.player != null && SoulFlames.isSoulBurning(mc.player);
        return sprites.get(soul ? SoulFireSprites.swap(sprite) : sprite);
    }
}
