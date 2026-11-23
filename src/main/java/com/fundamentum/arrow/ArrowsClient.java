package com.fundamentum.arrow;

import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Shot vanilla arrows (plain, tipped, spectral) are drawn as small 3D models (see {@link Arrow3DRenderer}). */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class ArrowsClient {
    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(Arrow3DModel.LAYER, Arrow3DModel::createBodyLayer);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EntityType.ARROW, Arrow3DRenderer::tippable);
        event.registerEntityRenderer(EntityType.SPECTRAL_ARROW, ctx -> Arrow3DRenderer.of(ctx, Arrow3DRenderer.SPECTRAL));
    }

    private ArrowsClient() {}
}
