package com.fundamentum.multiblock.client;

import com.fundamentum.client.ClientRefresh;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** The version side of the ghost preview: runs {@link MultiblockProjector} (and the client safety timer) once per client tick. */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MultiblockClientTick {
    private MultiblockClientTick() { }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ClientRefresh.tick();
        MultiblockProjector.tick();
    }
}
