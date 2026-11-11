package com.fundamentum.thrown;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client side of {@link ThrownWeapons}: the generic thrown weapon's renderer. */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ThrownWeaponsClient {
    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ThrownWeapons.THROWN_WEAPON.get(), ThrownWeaponRenderer::new);
    }

    private ThrownWeaponsClient() {}
}
