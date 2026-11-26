package com.fundamentum.guide.client;

import com.fundamentum.guide.book.BookResources;
import com.fundamentum.guide.book.GuideBooks;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Guide books on the client: loads books and styles on resource reload, opens the book screen, ticks progress. */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class BookClientHooks {
    private BookClientHooks() { }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        GuideBooks.setOpener(id -> Minecraft.getInstance().execute(() -> {
            BookScreen screen = BookScreen.of(id);
            if (screen != null) Minecraft.getInstance().setScreen(screen);
        }));
    }

    @SubscribeEvent
    public static void reload(AddClientReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath("fundamentum", "books"), new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                GuideBooks.set(Bestiary.add(BookResources.read(manager), manager));
                com.fundamentum.quest.client.QuestScreens.load(manager);
            }
        });
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        BookProgress.tick();
    }
}
