package com.fundamentum.mixin;

import com.fundamentum.client.Emissive;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.blaze3d.systems.SamplerCache;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * With Iris loaded (1.25.3): every render type whose vertex format carries overlay or lightmap coordinates binds the
 * overlay ({@code Sampler1}) and lightmap ({@code Sampler2}) textures, even when vanilla's setup leaves them out. Iris
 * swaps in the shader pack's program for a pipeline, and that program asks for a sampler per vertex element; vanilla's
 * {@code item_cutout} (any held or dropped item drawn into Iris's shadow map) binds no overlay, so the frame failed with
 * "IllegalStateException: Missing sampler Sampler1" in Iris's ShadowRenderer. Without Iris nothing changes.
 * 26.3: RenderSetup.getTextures became prepareTextures, which gets the overlay and lightmap views and returns a list of
 * PreparedRenderType.Texture; the pipeline's vertex format is its binding 0, and elements are looked up by name.
 */
@Mixin(RenderSetup.class)
public abstract class RenderSetupSamplersMixin {
    @Shadow
    @Final
    RenderPipeline pipeline;

    @Inject(method = "prepareTextures", at = @At("RETURN"), cancellable = true)
    private void fundamentum$packSamplers(TextureManager textureManager, SamplerCache samplerCache, GpuTextureView overlayTexture,
                                          GpuTextureView lightmapTexture, CallbackInfoReturnable<List<PreparedRenderType.Texture>> cir) {
        if (!Emissive.irisLoaded()) {
            return;
        }
        VertexFormat format = this.pipeline.getVertexFormatBinding(0);
        if (format == null) {
            return;
        }
        List<PreparedRenderType.Texture> list = cir.getReturnValue();
        boolean hasOverlay = false;
        boolean hasLight = false;
        for (PreparedRenderType.Texture texture : list) {
            hasOverlay |= texture.name().equals("Sampler1");
            hasLight |= texture.name().equals("Sampler2");
        }
        boolean overlay = !hasOverlay && format.contains("UV1");
        boolean light = !hasLight && format.contains("UV2");
        if (!overlay && !light) {
            return;
        }
        List<PreparedRenderType.Texture> out = new ArrayList<>(list);
        if (overlay) {
            out.add(new PreparedRenderType.Texture("Sampler1", overlayTexture, samplerCache.getClampToEdge(FilterMode.LINEAR)));
        }
        if (light) {
            out.add(new PreparedRenderType.Texture("Sampler2", lightmapTexture, samplerCache.getClampToEdge(FilterMode.LINEAR)));
        }
        cir.setReturnValue(out);
    }
}
