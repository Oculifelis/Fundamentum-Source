package com.fundamentum.arrow;

import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Shot vanilla arrows (plain, tipped, spectral) are drawn as small 3D models (see {@link Arrow3DRenderer}). */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
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
