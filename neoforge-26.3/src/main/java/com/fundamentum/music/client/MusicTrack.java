package com.fundamentum.music.client;

import com.fundamentum.music.MusicDirector;
import com.fundamentum.port.FId;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** One looping music track that fades in, fades out and follows the music volume setting. The same file on every version. */
final class MusicTrack extends AbstractTickableSoundInstance {
    final FId id;
    private float level = 0.001f;
    private float target = 1f;
    private float step;

    MusicTrack(FId id, int fadeIn) {
        super(SoundEvent.createVariableRangeEvent(id.vanilla()), SoundSource.MUSIC, RandomSource.create());
        this.id = id;
        this.looping = true;
        this.delay = 0;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.step = 1f / Math.max(1, fadeIn);
        this.volume = scaled();
    }

    void fadeOut(int ticks) {
        this.target = 0f;
        this.step = Math.max(this.level, 0.001f) / Math.max(1, ticks);
    }

    void end() {
        stop();
    }

    boolean fadingOut() {
        return this.target == 0f;
    }

    private float scaled() {
        return Math.max(0.0001f, this.level * (float) MusicDirector.volume());
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (this.level < this.target) {
            this.level = Math.min(this.target, this.level + this.step);
        } else if (this.level > this.target) {
            this.level = Math.max(this.target, this.level - this.step);
            if (this.level <= 0.0001f) stop();
        }
        this.volume = scaled();
    }
}
