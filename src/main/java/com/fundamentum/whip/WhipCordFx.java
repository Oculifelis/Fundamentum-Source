package com.fundamentum.whip;

/**
 * How a whip's cord is drawn (1.22.0). Every mode samples the whip's cord texture and colours; the shaders
 * ({@code assets/fundamentum/shaders/core/whip_cord.fsh}) animate it with the game time. {@code v} runs along the cord.
 */
public enum WhipCordFx {
    /** The texture as painted, lit by the world. */
    NONE,
    /** The texture, full bright. */
    GLOW,
    /** Bright bands pulse along the cord toward the tip, full bright and additive (lasers, magic). */
    ENERGY,
    /** Translucent, with a moving shimmer and pale foam at the edges (sea whips). */
    WATER,
    /** Faint, translucent streaks that swirl along it (breeze and gust whips). */
    WIND,
    /** A flickering, crackling bright core, additive (storm whips). */
    LIGHTNING,
    /** Living flame: tongues that lick up and out along the cord, white-hot at the core, additive (fire whips). */
    FIRE
}
