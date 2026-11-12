package com.fundamentum.music.client;

import com.fundamentum.music.MusicContext;
import com.fundamentum.music.MusicDirector;
import com.fundamentum.music.MusicState;
import com.fundamentum.port.FId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;

/**
 * Client side of the music system: every half second it works out which track the rules (and the server's boss and
 * combat state) pick, crossfades to it, and holds vanilla music off while one of ours plays. The per-version
 * {@code MusicClientHooks} calls in here. The same file on every version.
 */
public final class MusicPlayer {
    private MusicPlayer() { }

    private static final int CHECK_EVERY = 10;
    private static MusicTrack current;
    private static MusicDirector.Choice playing;
    private static final List<MusicTrack> fading = new ArrayList<>();
    private static MusicState lastState = MusicState.NONE;
    private static int timer;

    /** Called every client tick. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        fading.removeIf(MusicTrack::isStopped);
        if (mc.player == null || mc.level == null) {
            if (current != null) reset();
            return;
        }
        MusicState state = MusicDirector.clientState();
        boolean changed = state != lastState;
        lastState = state;
        if (!changed && ++timer < CHECK_EVERY) return;
        timer = 0;
        MusicDirector.Choice choice = null;
        if (MusicDirector.enabled()) {
            MusicContext context = MusicContext.of(mc.player, Set.copyOf(state.structures()), state.bossFight(),
                state.bossType().isEmpty() ? null : FId.tryOf(state.bossType()), state.combat());
            choice = MusicDirector.choose(MusicDirector.clientRules(), context, MusicDirector.bossChoice(state));
        }
        play(mc, choice);
    }

    private static void play(Minecraft mc, MusicDirector.Choice choice) {
        if (current != null && current.isStopped()) current = null;
        if (choice != null && current != null && current.id.equals(choice.sound())) {
            playing = choice;
            return;
        }
        if (current != null) {
            int out = playing == null ? 40 : playing.fadeOut();
            // a boss track that ends uses the fade the server gave for the stop
            if (playing != null && playing.boss() && choice != null && !choice.boss()) out = lastState.bossFade();
            if (playing != null && playing.boss() && choice == null) out = lastState.bossFade();
            current.fadeOut(out);
            fading.add(current);
            current = null;
        }
        playing = choice;
        if (choice == null) return;
        if (MusicDirector.stopVanilla()) mc.getMusicManager().stopPlaying();
        current = new MusicTrack(choice.sound(), choice.fadeIn());
        mc.getSoundManager().play(current);
    }

    /** True while one of our tracks plays and vanilla music should stay quiet. */
    public static boolean holdVanilla() {
        return current != null && !current.isStopped() && MusicDirector.stopVanilla();
    }

    /** The track playing now, or null. */
    public static FId playing() {
        return current == null ? null : current.id;
    }

    /** Stops everything at once and forgets the server's rules (on leaving a world). */
    public static void reset() {
        if (current != null) current.end();
        fading.forEach(MusicTrack::end);
        fading.clear();
        current = null;
        playing = null;
        lastState = MusicState.NONE;
        timer = 0;
        MusicDirector.clientReset();
    }
}
