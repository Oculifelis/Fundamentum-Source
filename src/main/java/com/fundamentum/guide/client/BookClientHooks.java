package com.fundamentum.guide.client;

import com.fundamentum.guide.book.BookResources;
import com.fundamentum.guide.book.GuideBooks;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Guide books on the client: loads books and styles on resource reload, opens the book screen, ticks progress. */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class BookClientHooks {
    private BookClientHooks() { }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) BookProgress.tick();
    }

    @Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        private ModEvents() { }

        @SubscribeEvent
        public static void setup(FMLClientSetupEvent event) {
            GuideBooks.setOpener(id -> Minecraft.getInstance().execute(() -> {
                BookScreen screen = BookScreen.of(id);
                if (screen != null) Minecraft.getInstance().setScreen(screen);
            }));
        }

        @SubscribeEvent
        public static void reload(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(new ResourceManagerReloadListener() {
                @Override
                public void onResourceManagerReload(ResourceManager manager) {
                    GuideBooks.set(Bestiary.add(BookResources.read(manager), manager));
                    com.fundamentum.quest.client.QuestScreens.load(manager);
                }
            });
        }
    }
}
