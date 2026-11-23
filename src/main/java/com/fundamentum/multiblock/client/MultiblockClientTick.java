package com.fundamentum.multiblock.client;

import com.fundamentum.client.ClientRefresh;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** The version side of the ghost preview: runs {@link MultiblockProjector} (and the client's safety timer) once per client tick. */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class MultiblockClientTick {
    private MultiblockClientTick() { }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        ClientRefresh.tick();
        MultiblockProjector.tick();
    }
}
