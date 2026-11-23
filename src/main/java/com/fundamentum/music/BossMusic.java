package com.fundamentum.music;

import com.fundamentum.port.FId;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import org.jspecify.annotations.Nullable;

/**
 * Boss music: the server tells one player which track to play (or to stop). The track takes part in the music system
 * ({@link MusicDirector}) at boss priority, so the client crossfades into it and a music rule can still win over it.
 * The boss package sends this; anything else may too.
 */
public final class BossMusic {
    private BossMusic() { }

    /** Kept for callers from before the music system; the music system does the work now. */
    public static void init(IEventBus modBus) {
        MusicDirector.init();
    }

    /** Starts (or crossfades to) {@code sound} for this player. */
    public static void play(ServerPlayer player, Identifier sound, int fadeTicks) {
        play(player, sound, fadeTicks, null);
    }

    /** Starts (or crossfades to) {@code sound} for this player; {@code boss} lets music rules match on the boss type. */
    public static void play(ServerPlayer player, Identifier sound, int fadeTicks, @Nullable LivingEntity boss) {
        MusicDirector.bossPlay(player, FId.from(sound), fadeTicks, MusicDirector.typeOf(boss));
    }

    /** Fades this player's boss music out. */
    public static void stop(ServerPlayer player, int fadeTicks) {
        MusicDirector.bossStop(player, fadeTicks);
    }

    /** The boss track this player was last told to play, or null (server side). */
    public static @Nullable Identifier current(ServerPlayer player) {
        FId id = MusicDirector.bossSound(player);
        return id == null ? null : id.vanilla();
    }
}
