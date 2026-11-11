package com.fundamentum.music.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.Nullable;

/**
 * Plays the boss track the server asks for: the new track fades in while the old one fades out, vanilla music is held
 * off while a boss track plays, and everything stops on leaving the world.
 */
public final class BossMusicPlayer {
    private BossMusicPlayer() { }

    private static @Nullable Track current;
    private static final List<Track> fading = new ArrayList<>();
    private static boolean listening;

    public static void handle(String sound, int fade) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (!listening) {
                listening = true;
                MinecraftForge.EVENT_BUS.addListener(BossMusicPlayer::playSound);
                MinecraftForge.EVENT_BUS.addListener(BossMusicPlayer::loggedOut);
            }
            int ticks = Math.max(1, fade);
            if (current != null) {
                if (!sound.isEmpty() && current.id.toString().equals(sound)) return;
                current.fadeOut(ticks);
                fading.add(current);
                current = null;
            }
            if (sound.isEmpty()) return;
            ResourceLocation id = ResourceLocation.tryParse(sound);
            if (id == null) return;
            mc.getMusicManager().stopPlaying();
            current = new Track(id, ticks);
            mc.getSoundManager().play(current);
        });
    }

    /** Forge 47 has no music selection event: vanilla music that tries to start under a boss track is dropped. */
    private static void playSound(PlaySoundEvent event) {
        fading.removeIf(Track::isStopped);
        SoundInstance s = event.getSound();
        if (current != null && s != null && !(s instanceof Track) && s.getSource() == SoundSource.MUSIC) event.setSound(null);
    }

    private static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (current != null) current.end();
        fading.forEach(Track::end);
        fading.clear();
        current = null;
    }

    static final class Track extends AbstractTickableSoundInstance {
        final ResourceLocation id;
        private float target = 1f;
        private float step;

        Track(ResourceLocation id, int fadeIn) {
            super(SoundEvent.createVariableRangeEvent(id), SoundSource.MUSIC, RandomSource.create());
            this.id = id;
            this.looping = true;
            this.delay = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.volume = 0.001f;
            this.step = 1f / fadeIn;
        }

        void fadeOut(int ticks) {
            this.target = 0f;
            this.step = Math.max(this.volume, 0.001f) / ticks;
        }

        void end() {
            stop();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            if (this.volume < this.target) {
                this.volume = Math.min(this.target, this.volume + this.step);
            } else if (this.volume > this.target) {
                this.volume = Math.max(this.target, this.volume - this.step);
                if (this.volume <= 0.0001f) stop();
            }
        }
    }
}
