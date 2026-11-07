package com.fundamentum.music;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

/**
 * How far a jukebox is through its song: the song's name, total length and elapsed time, for any disc (vanilla or
 * modded) because the length comes from the {@code jukebox_song} registry entry ({@link JukeboxSong#lengthInSeconds}).
 * <p>
 * Server side: {@link #of(JukeboxBlockEntity)} reads the jukebox's own song player (the client copy of a jukebox block
 * entity never knows its song, vanilla does not sync it). Client side: {@code music.client.JukeboxTracker} keeps every
 * jukebox the player can hear, from the song start and stop events vanilla already sends, and
 * {@code music.client.JukeboxBar} draws a {@link Track} as name, bar, scrubber and "1:12 / 3:05".
 */
public final class JukeboxProgress {
    private JukeboxProgress() { }

    /** A jukebox song in progress. {@code elapsedTicks} may be fractional (client side, between ticks). */
    public record Track(BlockPos pos, Holder<JukeboxSong> song, double elapsedTicks) {
        /** The song's name as the disc shows it, e.g. "C418 - cat". */
        public Component name() { return song.value().description(); }

        public int lengthTicks() { return JukeboxProgress.lengthTicks(song.value()); }

        public float lengthSeconds() { return song.value().lengthInSeconds(); }

        public double elapsedSeconds() { return Math.min(elapsedTicks, lengthTicks()) / 20.0; }

        /** 0 at the start, 1 at the end (clamped). */
        public float progress() {
            int len = lengthTicks();
            return len <= 0 ? 1.0F : (float) Math.max(0.0, Math.min(1.0, elapsedTicks / len));
        }

        /** "1:12 / 3:05". */
        public String timeText() { return JukeboxProgress.timeText(elapsedSeconds(), lengthSeconds()); }

        /** How far away the song can still be heard, in blocks (64 for vanilla discs). */
        public float hearingRange() { return JukeboxProgress.hearingRange(song.value()); }

        public boolean finished() { return elapsedTicks >= lengthTicks(); }
    }

    /** Song and server tick count, as sent to clients (e.g. by the Jade plugin). */
    public static final StreamCodec<RegistryFriendlyByteBuf, Track> TRACK_STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, Track::pos,
        JukeboxSong.STREAM_CODEC, Track::song,
        ByteBufCodecs.DOUBLE, Track::elapsedTicks,
        Track::new);

    /** Server side: the jukebox's song and how far it is, or empty when nothing plays. */
    public static Optional<Track> of(JukeboxBlockEntity jukebox) {
        JukeboxSongPlayer player = jukebox.getSongPlayer();
        if (!player.isPlaying() || jukebox.getLevel() == null) {
            return Optional.empty();
        }
        return song(jukebox.getLevel().registryAccess(), jukebox.getTheItem())
            .map(song -> new Track(jukebox.getBlockPos(), song, player.getTicksSinceSongStarted()));
    }

    /** The song a disc (any item with the jukebox_playable component) plays. {@code registries} is unused on 26.x (1.21.1 needs it). */
    public static Optional<Holder<JukeboxSong>> song(HolderLookup.Provider registries, ItemStack stack) {
        return JukeboxSong.fromStack(stack);
    }

    public static int lengthTicks(JukeboxSong song) {
        return song.lengthInTicks();
    }

    /** Range a jukebox plays the song at: vanilla plays discs at volume 4, so 64 blocks unless the sound fixes one. */
    public static float hearingRange(JukeboxSong song) {
        return song.soundEvent().value().getRange(4.0F);
    }

    /** "m:ss" (or "h:mm:ss" for an hour or more). */
    public static String clock(double seconds) {
        long s = (long) Math.max(0, Math.floor(seconds));
        if (s >= 3600) {
            return String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        }
        return String.format("%d:%02d", s / 60, s % 60);
    }

    /** "1:12 / 3:05". */
    public static String timeText(double elapsedSeconds, double lengthSeconds) {
        return clock(Math.min(elapsedSeconds, lengthSeconds)) + " / " + clock(Math.ceil(lengthSeconds));
    }
}
