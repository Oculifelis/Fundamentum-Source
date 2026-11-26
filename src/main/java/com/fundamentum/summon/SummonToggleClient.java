package com.fundamentum.summon;

import com.fundamentum.client.Keys;
import com.fundamentum.port.FNet;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** The Toggle Summons key ({@link Keys#SUMMONS}): asks the server to flip the player's summon switch. */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class SummonToggleClient {
    private SummonToggleClient() { }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        while (Keys.SUMMONS.consumeClick()) {
            if (Minecraft.getInstance().player != null) {
                FNet.toServer(SummonToggle.Request.INSTANCE);
            }
        }
    }
}
