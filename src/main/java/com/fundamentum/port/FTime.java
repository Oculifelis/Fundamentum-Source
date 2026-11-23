package com.fundamentum.port;

import net.minecraft.world.level.Level;

/** The time of day on any level, client or server, with one call on every version. */
public final class FTime {
    private FTime() { }

    /** Ticks since the world began, by the level's day clock (mod 24000 for the time of day). */
    public static long dayTime(Level level) {
        return level.getDefaultClockTime();
    }
}
