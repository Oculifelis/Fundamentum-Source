package com.fundamentum.mixin;

import com.fundamentum.whip.WhipShaders;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item model faces with block light 15 (an element face's or a flat layer's {@code neoforge_data}, {@code "block_light": 15};
 * 1.21.1 has no {@code light_emission}) draw unlit (1.22.2): full colour whatever the light, through
 * {@link WhipShaders#unlit}. Vanilla only raises their block light to 15, which still goes through the lightmap, so a
 * glowing handle looked torch-lit and dimmed at night. Blocks placed in the world are drawn by their chunk layer and are
 * unchanged. On this version a quad has no render type of its own: while {@link ItemRenderer#render} draws a model, its
 * buffers and render type are kept, and each emissive quad goes to the unlit buffer instead of the item's.
 */
@Mixin(ItemRenderer.class)
public abstract class QuadEmissionMixin {
    @Unique
    private static MultiBufferSource fundamentum$buffers;
    @Unique
    private static boolean fundamentum$translucent;

    @Inject(method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderModelLists(Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/item/ItemStack;IILcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"),
        require = 0)
    private void fundamentum$keepBuffers(ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack pose, MultiBufferSource buffers,
                                         int light, int overlay, BakedModel model, CallbackInfo ci, @Local RenderType type) {
        fundamentum$buffers = buffers;
        fundamentum$translucent = type.sortOnUpload();
    }

    @Inject(method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At("RETURN"), require = 0)
    private void fundamentum$dropBuffers(ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack pose, MultiBufferSource buffers,
                                         int light, int overlay, BakedModel model, CallbackInfo ci) {
        fundamentum$buffers = null;
    }

    @WrapOperation(method = "renderQuadList", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/block/model/BakedQuad;FFFFIIZ)V"),
        require = 0)
    private void fundamentum$unlitWhenEmissive(VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad, float r, float g, float b, float a,
                                               int light, int overlay, boolean readColor, Operation<Void> original) {
        MultiBufferSource buffers = fundamentum$buffers;
        if (buffers != null && fundamentum$emissive(quad)) {
            VertexConsumer unlit = buffers.getBuffer(WhipShaders.unlit(quad.getSprite().atlasLocation(), fundamentum$translucent));
            original.call(unlit, pose, quad, r, g, b, a, LightTexture.FULL_BRIGHT, overlay, readColor);
        } else {
            original.call(consumer, pose, quad, r, g, b, a, light, overlay, readColor);
        }
    }

    /** Block light 15 baked into every corner (Neo's face data writes it into the quad's lightmap coordinates). */
    @Unique
    private static boolean fundamentum$emissive(BakedQuad quad) {
        int[] v = quad.getVertices();
        if (v.length < 4 * IQuadTransformer.STRIDE) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if ((v[i * IQuadTransformer.STRIDE + IQuadTransformer.UV2] & 0xFFFF) < 0xF0) {
                return false;
            }
        }
        return true;
    }
}
