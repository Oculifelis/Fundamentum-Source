package com.fundamentum.flail;

import java.util.Locale;

/**
 * How a flail's chain and heads move, chosen per flail with the {@code "physics"} field.
 * <ul>
 * <li>{@link #PENDULUM}: a solid flail. The head hangs on a rigid arm (one link, no bend), so it swings as one piece:
 * predictable, the heaviest hits, no wrapping.</li>
 * <li>{@link #LINKS}: a real chain of links that bends and trails. Slower to bring round, the head lags and cracks
 * through at the end of the arc; it can wrap a target.</li>
 * <li>{@link #LAUNCH}: the head is thrown out on the chain along the look (rope dart, meteor hammer) and reeled back.
 * Spinning first throws it faster and further.</li>
 * <li>{@link #HYBRID}: a chain of links while it is spun or swung, thrown out like {@link #LAUNCH} when a spin is let
 * go.</li>
 * </ul>
 */
public enum FlailPhysics {
    PENDULUM, LINKS, LAUNCH, HYBRID;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** True when the head can be thrown out on the chain. */
    public boolean launches() {
        return this == LAUNCH || this == HYBRID;
    }

    /** True when the chain is one rigid arm. */
    public boolean rigid() {
        return this == PENDULUM;
    }

    public static FlailPhysics parse(String id) {
        for (FlailPhysics p : values()) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        throw new IllegalArgumentException("unknown physics \"" + id + "\": use pendulum, links, launch or hybrid");
    }
}
