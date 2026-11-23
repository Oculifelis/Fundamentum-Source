package com.fundamentum.music.client;

import com.fundamentum.music.JukeboxProgress;
import com.fundamentum.music.JukeboxProgress.Track;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.phys.Vec3;

/**
 * Client side: every jukebox song the player can hear, with its elapsed time, built only from the song start and stop
 * level events vanilla already sends (1010 and 1011, the same ones that start and stop the music, which reach {@code LevelRenderer.playStreamingMusic}; see
 * {@code mixin.JukeboxEventsMixin}). No scanning and no packets of its own. A song that started before the player came
 * within range (or joined) is not known until something calls {@link #sync} with the server's tick count (the Jade
 * plugin does when the player looks at the jukebox); vanilla does not play those either.
 * <p>
 * Elapsed time runs on the client level's game time plus the partial tick, so a bar drawn every frame moves smoothly.
 * Call from the render thread only.
 */
public final class JukeboxTracker {
    private JukeboxTracker() { }

    /** A server tick count closer than this to the client's own guess is ignored (network delay, not a correction). */
    private static final double SYNC_TOLERANCE_TICKS = 20.0;

    private record Entry(RecordItem song, double startTime) { }

    private static final Map<BlockPos, Entry> PLAYING = new HashMap<>();
    private static ClientLevel level;

    /** The song playing at {@code pos} and how far it is right now, or empty. */
    public static Optional<Track> at(BlockPos pos) {
        double now = now();
        Entry e = PLAYING.get(pos);
        if (e == null) {
            return Optional.empty();
        }
        Track t = new Track(pos, e.song, now - e.startTime);
        return t.finished() && t.elapsedTicks() > t.lengthTicks() + 40 ? Optional.empty() : Optional.of(t);
    }

    /** Every song the player can hear (all tracked jukeboxes whose song still reaches {@code from}), nearest first. */
    public static List<Track> audible(Vec3 from) {
        double now = now();
        List<Track> out = new ArrayList<>();
        for (Map.Entry<BlockPos, Entry> me : PLAYING.entrySet()) {
            Track t = new Track(me.getKey(), me.getValue().song, now - me.getValue().startTime);
            double r = t.hearingRange();
            if (!t.finished() && Vec3.atCenterOf(t.pos()).distanceToSqr(from) <= r * r) {
                out.add(t);
            }
        }
        out.sort((a, b) -> Double.compare(Vec3.atCenterOf(a.pos()).distanceToSqr(from), Vec3.atCenterOf(b.pos()).distanceToSqr(from)));
        return out;
    }

    /** The nearest playing jukebox whose song reaches {@code from} (within its hearing range), or empty. */
    public static Optional<Track> nearest(Vec3 from) {
        List<Track> all = audible(from);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** Every tracked song, heard or not. */
    public static List<Track> playing() {
        double now = now();
        List<Track> out = new ArrayList<>();
        PLAYING.forEach((pos, e) -> out.add(new Track(pos, e.song, now - e.startTime)));
        return out;
    }

    /**
     * Corrects (or starts) a jukebox from the server's own tick count, e.g. a song that began before the player
     * arrived. Small differences are ignored so a bar does not jitter by the network delay.
     */
    public static void sync(BlockPos pos, RecordItem song, double serverTicks) {
        double now = now();
        Entry e = PLAYING.get(pos);
        if (e != null && e.song == song && Math.abs((now - e.startTime) - serverTicks) < SYNC_TOLERANCE_TICKS) {
            return;
        }
        PLAYING.put(pos.immutable(), new Entry(song, now - serverTicks));
    }

    /** From the 1010 level event: a song starts at {@code pos}. */
    public static void started(BlockPos pos, RecordItem song) {
        double now = now();
        PLAYING.put(pos.immutable(), new Entry(song, now));
    }

    /** From the 1011 level event (and before every start): the song at {@code pos} stops. */
    public static void stopped(BlockPos pos) {
        now();
        PLAYING.remove(pos);
    }

    /** Client game time with the partial tick; forgets everything when the level changes. */
    private static double now() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            level = mc.level;
            PLAYING.clear();
        }
        if (level == null) {
            return 0.0;
        }
        PLAYING.values().removeIf(e -> level.getGameTime() - e.startTime > JukeboxProgress.lengthTicks(e.song) + 200);
        return level.getGameTime() + mc.getFrameTime();
    }
}
