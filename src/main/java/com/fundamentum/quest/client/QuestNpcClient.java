package com.fundamentum.quest.client;

import com.fundamentum.quest.QuestNpcs;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class QuestNpcClient {
    private QuestNpcClient() { }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(QuestNpcs.QUEST_GIVER.get(), QuestNpcRenderer::new);
    }
}
