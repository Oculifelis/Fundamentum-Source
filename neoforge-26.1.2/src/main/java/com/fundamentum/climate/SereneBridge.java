package com.fundamentum.climate;

import com.fundamentum.port.FClimate;
import java.lang.reflect.Method;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the season from Serene Seasons when it is installed, by reflection so Fundamentum needs no build of it.
 * Its four seasons map onto Fundamentum seasons with the same path (spring, summer, autumn, winter). Only builds of
 * Serene Seasons for 1.21.1 and 1.20.1 exist; on other versions this always reports it absent.
 */
public final class SereneBridge {
    private SereneBridge() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static Boolean available;
    private static Method getSeasonState;
    private static Method getSeason;

    public static synchronized boolean available() {
        if (available == null) {
            available = false;
            if (FClimate.modLoaded("sereneseasons")) {
                try {
                    Class<?> helper = Class.forName("sereneseasons.api.season.SeasonHelper");
                    getSeasonState = helper.getMethod("getSeasonState", Level.class);
                    available = true;
                } catch (ReflectiveOperationException | LinkageError e) {
                    LOG.warn("Serene Seasons is installed but its season could not be read: {}", e.toString());
                }
            }
        }
        return available;
    }

    /** The Serene Seasons season in this level in lower case (spring, summer, autumn, winter), or null. */
    public static String season(Level level) {
        if (!available()) return null;
        try {
            Object state = getSeasonState.invoke(null, level);
            if (state == null) return null;
            if (getSeason == null) getSeason = state.getClass().getMethod("getSeason");
            Object season = getSeason.invoke(state);
            return season == null ? null : season.toString().toLowerCase(java.util.Locale.ROOT);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Serene Seasons season could not be read: {}", e.toString());
            return null;
        }
    }
}
