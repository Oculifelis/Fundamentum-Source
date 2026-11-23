package com.fundamentum.mixin;

import com.fundamentum.whip.WhipShaders;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item model faces with full block light ({@code "forge_data": {"block_light": 15}} on an element, the 1.20.1 form of the
 * 26 builds' {@code "light_emission": 15}) draw unlit: full colour whatever the light, no directional shade. Forge only
 * bakes block light 15 into the face, which still goes through the lightmap, so a glowing handle looked torch-lit.
 * <p>
 * The 26 builds give such a quad its own item render type when it is baked. A 1.20.1 quad has no render type of its own
 * (the item renderer draws a whole model into one buffer), so here the item renderer holds those quads back while it
 * draws the model and then draws them through {@link WhipShaders#unlit}, translucent when the model's pass was. They are
 * drawn after the model's own buffer is done, so an immediate buffer source never has two batches open. Blocks placed in
 * the world are drawn by their chunk layer and are unchanged. Items drawn outside {@code ItemRenderer.render} (a mod
 * calling {@code renderModelLists} itself) are left as they are.
 * <p>
 * No refmap: targets name the official and SRG names with remap off; every injector is optional.
 */
@Mixin(ItemRenderer.class)
public abstract class QuadEmissionMixin {
    private static final String MODEL_LISTS_DEV = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderModelLists(Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/item/ItemStack;IILcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V";
    private static final String MODEL_LISTS_SRG = "Lnet/minecraft/client/renderer/entity/ItemRenderer;m_115189_(Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/item/ItemStack;IILcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V";
    private static final String FOIL_ARGS = "(Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/renderer/RenderType;ZZ)Lcom/mojang/blaze3d/vertex/VertexConsumer;";
    private static final String BULK = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/block/model/BakedQuad;FFFFIIZ)V";
    private static final Map<RenderType, Boolean> TRANSLUCENT = new IdentityHashMap<>();

    /** A held-back face: its colour and overlay as the model would have drawn it. */
    private record Held(PoseStack.Pose pose, BakedQuad quad, float r, float g, float b, float a, int overlay) {}

    private static MultiBufferSource fundamentum$buffers;
    private static RenderType fundamentum$pass;
    private static final List<Held> fundamentum$held = new ArrayList<>();

    /** Whether every vertex of {@code quad} carries block light 15 (block format: the lightmap is int 6 of 8). */
    private static boolean fundamentum$emissive(BakedQuad quad) {
        int[] v = quad.getVertices();
        if (v.length < 32) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if ((v[i * 8 + 6] & 0xFFFF) < 0xF0) {
                return false;
            }
        }
        return true;
    }

    private static boolean fundamentum$translucent(RenderType type) {
        if (type == null) {
            return false;
        }
        synchronized (TRANSLUCENT) {
            return TRANSLUCENT.computeIfAbsent(type, t -> t.toString().contains("translucent"));
        }
    }

    @Inject(method = {"render", "m_115143_"}, remap = false, require = 0, at = @At("HEAD"))
    private void fundamentum$begin(ItemStack stack, ItemDisplayContext context, boolean left, PoseStack pose, MultiBufferSource buffers,
                                   int light, int overlay, BakedModel model, CallbackInfo ci) {
        fundamentum$buffers = buffers;
        fundamentum$held.clear();
    }

    @Inject(method = {"render", "m_115143_"}, remap = false, require = 0, at = @At("RETURN"))
    private void fundamentum$end(ItemStack stack, ItemDisplayContext context, boolean left, PoseStack pose, MultiBufferSource buffers,
                                 int light, int overlay, BakedModel model, CallbackInfo ci) {
        fundamentum$buffers = null;
        fundamentum$pass = null;
        fundamentum$held.clear();
    }

    @ModifyArg(method = {"render", "m_115143_"}, remap = false, require = 0, index = 1,
        at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;getFoilBuffer" + FOIL_ARGS))
    private RenderType fundamentum$passDev(RenderType type) {
        fundamentum$pass = type;
        return type;
    }

    @ModifyArg(method = {"render", "m_115143_"}, remap = false, require = 0, index = 1,
        at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;m_115211_" + FOIL_ARGS))
    private RenderType fundamentum$passSrg(RenderType type) {
        fundamentum$pass = type;
        return type;
    }

    @ModifyArg(method = {"render", "m_115143_"}, remap = false, require = 0, index = 1,
        at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;getFoilBufferDirect" + FOIL_ARGS))
    private RenderType fundamentum$passDirectDev(RenderType type) {
        fundamentum$pass = type;
        return type;
    }

    @ModifyArg(method = {"render", "m_115143_"}, remap = false, require = 0, index = 1,
        at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;m_115222_" + FOIL_ARGS))
    private RenderType fundamentum$passDirectSrg(RenderType type) {
        fundamentum$pass = type;
        return type;
    }

    @Redirect(method = {"renderQuadList", "m_115162_"}, remap = false, require = 0, at = @At(value = "INVOKE", target = BULK, remap = false))
    private void fundamentum$holdBack(VertexConsumer buffer, PoseStack.Pose pose, BakedQuad quad, float r, float g, float b, float a,
                                      int light, int overlay, boolean readAlpha) {
        if (fundamentum$buffers != null && fundamentum$emissive(quad)) {
            fundamentum$held.add(new Held(pose, quad, r, g, b, a, overlay));
            return;
        }
        buffer.putBulkData(pose, quad, r, g, b, a, light, overlay, readAlpha);
    }

    @Inject(method = {"render", "m_115143_"}, remap = false, require = 0, at = {
        @At(value = "INVOKE", target = MODEL_LISTS_DEV, shift = At.Shift.AFTER, remap = false),
        @At(value = "INVOKE", target = MODEL_LISTS_SRG, shift = At.Shift.AFTER, remap = false)})
    private void fundamentum$drawHeld(ItemStack stack, ItemDisplayContext context, boolean left, PoseStack pose, MultiBufferSource buffers,
                                      int light, int overlay, BakedModel model, CallbackInfo ci) {
        if (fundamentum$held.isEmpty() || fundamentum$buffers == null) {
            return;
        }
        boolean translucent = fundamentum$translucent(fundamentum$pass);
        for (Held h : fundamentum$held) {
            VertexConsumer unlit = fundamentum$buffers.getBuffer(WhipShaders.unlit(h.quad().getSprite().atlasLocation(), translucent));
            unlit.putBulkData(h.pose(), h.quad(), h.r(), h.g(), h.b(), h.a(), LightTexture.FULL_BRIGHT, h.overlay(), true);
        }
        fundamentum$held.clear();
    }
}
