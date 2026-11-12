package com.fundamentum.music.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;

/** Hooks the music player into the client: the tick, holding vanilla music off, and leaving a world. */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class MusicClientHooks {
    private MusicClientHooks() { }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        MusicPlayer.tick();
    }

    @SubscribeEvent
    public static void selectMusic(SelectMusicEvent event) {
        if (MusicPlayer.holdVanilla()) event.setMusic(null);
    }

    @SubscribeEvent
    public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MusicPlayer.reset();
    }
}
