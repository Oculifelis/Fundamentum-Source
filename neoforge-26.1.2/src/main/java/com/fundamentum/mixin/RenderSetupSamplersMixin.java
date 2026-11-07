package com.fundamentum.mixin;

import com.fundamentum.client.Emissive;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderSetup;
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
 */
@Mixin(RenderSetup.class)
public abstract class RenderSetupSamplersMixin {
    @Shadow
    @Final
    RenderPipeline pipeline;

    @Inject(method = "getTextures", at = @At("RETURN"), cancellable = true)
    private void fundamentum$packSamplers(CallbackInfoReturnable<Map<String, RenderSetup.TextureAndSampler>> cir) {
        if (!Emissive.irisLoaded()) {
            return;
        }
        Map<String, RenderSetup.TextureAndSampler> map = cir.getReturnValue();
        boolean overlay = !map.containsKey("Sampler1") && this.pipeline.getVertexFormat().contains(VertexFormatElement.UV1);
        boolean light = !map.containsKey("Sampler2") && this.pipeline.getVertexFormat().contains(VertexFormatElement.UV2);
        if (!overlay && !light) {
            return;
        }
        Map<String, RenderSetup.TextureAndSampler> out = new HashMap<>(map);
        Minecraft mc = Minecraft.getInstance();
        if (overlay) {
            out.put("Sampler1", new RenderSetup.TextureAndSampler(mc.gameRenderer.overlayTexture().getTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)));
        }
        if (light) {
            out.put("Sampler2", new RenderSetup.TextureAndSampler(mc.gameRenderer.lightmap(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)));
        }
        cir.setReturnValue(out);
    }
}
