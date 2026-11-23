package com.fundamentum.mixin;

import com.fundamentum.whip.WhipShaders;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item model faces with {@code "light_emission": 15} (an element's, or a flat layer's {@code neoforge_data}) draw unlit
 * (1.22.2): full colour whatever the light, no directional shade. Vanilla only raises their block light to 15, which
 * still goes through the lightmap, so a glowing handle looked torch-lit and dimmed at night. Blocks placed in the world
 * are drawn by their chunk layer and are unchanged.
 */
@Mixin(BakedQuad.MaterialInfo.class)
public abstract class QuadEmissionMixin {
    @Shadow
    @Final
    @Mutable
    private RenderType itemRenderType;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void fundamentum$unlitWhenEmissive(TextureAtlasSprite sprite, ChunkSectionLayer layer, RenderType itemRenderType, int tintIndex,
                                              boolean shade, int lightEmission, CallbackInfo ci) {
        if (lightEmission >= 15) {
            this.itemRenderType = WhipShaders.unlit(sprite.atlasLocation(), itemRenderType.hasBlending());
        }
    }
}
