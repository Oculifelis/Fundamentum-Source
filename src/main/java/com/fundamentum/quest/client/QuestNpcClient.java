package com.fundamentum.quest.client;

import com.fundamentum.quest.QuestNpcs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.EntityRenderersEvent;

@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class QuestNpcClient {
    private QuestNpcClient() { }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(QuestNpcs.QUEST_GIVER.get(), QuestNpcRenderer::new);
    }
}
