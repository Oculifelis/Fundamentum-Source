package com.fundamentum.thrown;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client side of {@link ThrownWeapons}: the generic thrown weapon's renderer. */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class ThrownWeaponsClient {
    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ThrownWeapons.THROWN_WEAPON.get(), ThrownWeaponRenderer::new);
    }

    private ThrownWeaponsClient() {}
}
