package com.fundamentum.spear;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Thrown spears are drawn by {@link ThrownSpearRenderer}. */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class SpearsClient {
    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Spears.THROWN_SPEAR.get(), ThrownSpearRenderer::new);
    }

    private SpearsClient() {}
}
