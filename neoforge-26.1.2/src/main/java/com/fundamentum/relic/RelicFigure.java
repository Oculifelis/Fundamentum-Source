package com.fundamentum.relic;

/**
 * A boss entity that sets itself up for its relic: the renderer makes a stand-in (never added to the world) for each
 * phase a relic declares ({@code figure.phases}) and calls {@link #relicPose} on it once, before drawing it. A boss without
 * it is drawn as it is made; a mod can also set up another mod's entity with
 * {@code com.fundamentum.relic.client.RelicClient.poser}.
 */
public interface RelicFigure {
    /** Puts this stand-in in the look of {@code phase} (1 for a boss's first form). */
    void relicPose(int phase);
}
