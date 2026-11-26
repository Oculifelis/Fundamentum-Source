package com.fundamentum.summon;

import com.fundamentum.client.Keys;
import com.fundamentum.port.FNet;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** The Toggle Summons key ({@link Keys#SUMMONS}): asks the server to flip the player's summon switch. */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SummonToggleClient {
    private SummonToggleClient() { }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        while (Keys.SUMMONS.consumeClick()) {
            if (Minecraft.getInstance().player != null) {
                FNet.toServer(SummonToggle.Request.INSTANCE);
            }
        }
    }
}
