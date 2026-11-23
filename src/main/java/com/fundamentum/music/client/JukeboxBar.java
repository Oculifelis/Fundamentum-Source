package com.fundamentum.music.client;

import com.fundamentum.music.JukeboxProgress.Track;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Draws a jukebox {@link Track}: an optional name line, then a bar with a scrubber at the elapsed point and the time,
 * "1:12 / 3:05", to its right. Plain GUI drawing, usable from a GUI layer, a screen or a Jade element alike.
 * <pre>
 *   C418 - cat
 *   [=======|-----------]  1:12 / 3:05
 * </pre>
 */
public final class JukeboxBar {
    private JukeboxBar() { }

    public static final int BAR_HEIGHT = 9;
    public static final int GAP = 4;
    public static final int TRACK = 0xFF1E1E24;
    public static final int EDGE = 0xFF000000;
    public static final int FILL = 0xFFE0A93A;
    public static final int KNOB = 0xFFFFFFFF;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int NAME = 0xFFFFE08A;

    /** Width of the time text for this track at its longest ("3:05 / 3:05"). */
    public static int timeWidth(Font font, Track track) {
        return font.width(com.fundamentum.music.JukeboxProgress.timeText(track.lengthSeconds(), track.lengthSeconds()));
    }

    /** Total width of {@link #draw} for this bar width. */
    public static int width(Font font, Track track, int barWidth, boolean withName) {
        int row = barWidth + GAP + timeWidth(font, track);
        return withName ? Math.max(row, font.width(track.name())) : row;
    }

    /** Total height of {@link #draw}. */
    public static int height(Font font, boolean withName) {
        return withName ? font.lineHeight + 2 + BAR_HEIGHT : BAR_HEIGHT;
    }

    /** Draws the track with its top left at x, y; returns the height used. */
    public static int draw(GuiGraphicsExtractor g, Font font, Track track, int x, int y, int barWidth, boolean withName) {
        if (withName) {
            g.text(font, track.name(), x, y, NAME, true);
            y += font.lineHeight + 2;
        }
        drawBar(g, x, y, barWidth, track.progress());
        g.text(font, track.timeText(), x + barWidth + GAP, y + (BAR_HEIGHT - font.lineHeight) / 2 + 1, TEXT, true);
        return height(font, withName);
    }

    /** Just the bar and scrubber, {@code width} x {@link #BAR_HEIGHT}, progress 0..1. */
    public static void drawBar(GuiGraphicsExtractor g, int x, int y, int width, float progress) {
        int top = y + 2;
        int bottom = y + BAR_HEIGHT - 2;
        g.fill(x, top, x + width, bottom, EDGE);
        g.fill(x + 1, top + 1, x + width - 1, bottom - 1, TRACK);
        int knob = x + 1 + Math.round(Math.max(0F, Math.min(1F, progress)) * (width - 4));
        if (knob > x + 1) {
            g.fill(x + 1, top + 1, knob, bottom - 1, FILL);
        }
        g.fill(knob - 1, y, knob + 3, y + BAR_HEIGHT, EDGE);
        g.fill(knob, y + 1, knob + 2, y + BAR_HEIGHT - 1, KNOB);
    }
}
