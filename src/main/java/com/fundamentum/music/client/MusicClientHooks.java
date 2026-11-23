package com.fundamentum.music.client;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Hooks the music player into the client: the tick, holding vanilla music off, and leaving a world. */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class MusicClientHooks {
    private MusicClientHooks() { }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) MusicPlayer.tick();
    }

    /** Forge 1.20.1 has no music selection event, so other music sounds are dropped as they start. */
    @SubscribeEvent
    public static void playSound(PlaySoundEvent event) {
        SoundInstance s = event.getSound();
        if (s != null && !(s instanceof MusicTrack) && s.getSource() == SoundSource.MUSIC && MusicPlayer.holdVanilla()) event.setSound(null);
    }

    @SubscribeEvent
    public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MusicPlayer.reset();
    }
}
