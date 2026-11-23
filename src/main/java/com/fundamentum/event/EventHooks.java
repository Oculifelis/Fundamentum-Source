package com.fundamentum.event;

import com.fundamentum.port.FAttach;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FVanilla;
import com.mojang.serialization.Codec;
import net.minecraft.server.level.ServerPlayer;

/** The events Fundamentum fires itself: first join and dimension entered. */
public final class EventHooks {
    private EventHooks() { }

    /** Less play time than this (in ticks) on the first login with Fundamentum counts as a new player. */
    static final int NEW_PLAYER_TICKS = 200;

    /** Set once a player has had their first-join event (or was already playing when Fundamentum was added). */
    public static final FAttach<Boolean> JOINED = FAttach.builder(FId.of("fundamentum", "joined"), () -> false)
        .codec(Codec.BOOL).copyOnDeath().build();

    private static boolean initialised;

    /** Called once while Fundamentum loads: the loader bus, the advancement trigger and the events fired here. */
    public static void init() {
        if (initialised) return;
        initialised = true;
        Events.bootstrap();
        FundamentumEvent.init();
        EventTrigger.init();
        FLife.playerLoggedIn(EventHooks::loggedIn);
        FLife.playerChangedDimension((player, from, to) -> FEvents.post(new Events.DimensionEntered(player, from, to)));
    }

    /**
     * Fires {@link Events.FirstJoin} the first time a player logs in. A player who already has play time from before
     * Fundamentum was installed is marked as joined without the event. Returns true when the event fired.
     */
    public static boolean loggedIn(ServerPlayer player) {
        if (JOINED.get(player)) return false;
        JOINED.set(player, true);
        if (FVanilla.playTime(player) >= NEW_PLAYER_TICKS) return false;
        FEvents.post(new Events.FirstJoin(player));
        return true;
    }
}
