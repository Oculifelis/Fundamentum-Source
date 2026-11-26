package com.fundamentum.guide.client;

import com.fundamentum.guide.GuideTheme;
import com.fundamentum.port.FId;

/**
 * Draws a {@link GuideTheme}'s shapes as plain fills and tiled textures: the panel (flat, a container bevel, or tiled
 * block textures), the boxes behind text, item slots, scroll bars and arrows, plus the extras (gradient, animated
 * rainbow edge, beaded edge, ribbon). The same file on every version.
 */
public final class GuideSkin {
    private GuideSkin() { }

    /** The crafting table progress arrow, 22 x 15. */
    private static final String[] CRAFT_ARROW = {
        "..............G.......", "..............GG......", "..............GGG.....", "..............GGGG....",
        "..............GGGGG...", "..............GGGGGG..", "GGGGGGGGGGGGGGGGGGGG..", "GGGGGGGGGGGGGGGGGGGGG.",
        "GGGGGGGGGGGGGGGGGGGG..", "..............GGGGGG..", "..............GGGGG...", "..............GGGG....",
        "..............GGG.....", "..............GG......", "..............G......."};

    public static final int CRAFT_ARROW_W = 22;
    public static final int CRAFT_ARROW_H = 15;

    static void rect(GuidePaint p, int x, int y, int w, int h, int c) {
        if (w > 0 && h > 0 && (c >>> 24) != 0) p.fill(x, y, x + w, y + h, c);
    }

    /** A 1-pixel outline. */
    static void outline(GuidePaint p, int x, int y, int w, int h, int c) {
        rect(p, x, y, w, 1, c);
        rect(p, x, y + h - 1, w, 1, c);
        rect(p, x, y + 1, 1, h - 2, c);
        rect(p, x + w - 1, y + 1, 1, h - 2, c);
    }

    /** The whole panel in the theme's style, with its extras; {@code millis} drives the rainbow edge. */
    public static void background(GuidePaint p, GuideTheme t, int x, int y, int w, int h, long millis) {
        switch (t.style()) {
            case BEVEL -> frame(p, t.panel().fill(), t.panel(), x, y, w, h);
            case TEXTURED -> textured(p, t, x, y, w, h);
            default -> {
                if (t.gradientTop() != 0 || t.gradientBottom() != 0) {
                    for (int i = 0; i < h; i += 2) rect(p, x, y + i, w, Math.min(2, h - i), mix(t.gradientTop(), t.gradientBottom(), i / (float) Math.max(1, h - 1)));
                } else {
                    rect(p, x, y, w, h, t.colours().fill());
                }
                outline(p, x, y, w, h, t.colours().frame());
            }
        }
        if (t.rainbow() > 0) rainbow(p, t, x, y, w, h, millis);
        if (t.beads() != 0) beads(p, t.beads(), x, y, w, h);
        if (t.ribbon() != 0) ribbon(p, t.ribbon(), x + w - 28, y);
    }

    private static void textured(GuidePaint p, GuideTheme t, int x, int y, int w, int h) {
        GuideTheme.Texture tx = t.texture();
        if (tx == null) {
            rect(p, x, y, w, h, t.colours().fill());
            outline(p, x, y, w, h, t.colours().frame());
            return;
        }
        int b = tx.borderWidth();
        tile(p, tx.border() != null ? tx.border() : tx.background(), tx.size(), x, y, w, h);
        rect(p, x, y, w, h, tx.borderTint());
        tile(p, tx.background(), tx.size(), x + b, y + b, w - 2 * b, h - 2 * b);
        rect(p, x + b, y + b, w - 2 * b, h - 2 * b, tx.tint());
        if (b > 0) outline(p, x + b - 1, y + b - 1, w - 2 * b + 2, h - 2 * b + 2, 0x60000000);
        outline(p, x, y, w, h, t.colours().frame());
    }

    /** Tiles a texture over a box at its own pixel size (GUI textures clamp, so edge tiles are cut short). */
    static void tile(GuidePaint p, FId texture, int s, int x, int y, int w, int h) {
        for (int ty = 0; ty < h; ty += s) {
            for (int tx = 0; tx < w; tx += s) p.texture(texture, x + tx, y + ty, Math.min(s, w - tx), Math.min(s, h - ty), s, s);
        }
    }

    /** An animated rainbow edge, 1 pixel wide, in 4-pixel steps round the panel. */
    private static void rainbow(GuidePaint p, GuideTheme t, int x, int y, int w, int h, long millis) {
        float shift = (millis % 100000L) / 1000F * t.rainbowSpeed() * 0.15F;
        int around = 2 * (w + h);
        for (int i = 0; i < w; i += 4) {
            rect(p, x + i, y, Math.min(4, w - i), 1, hsv(shift + i / (float) around, t.rainbow()));
            rect(p, x + w - i - Math.min(4, w - i), y + h - 1, Math.min(4, w - i), 1, hsv(shift + (w + h + i) / (float) around, t.rainbow()));
        }
        for (int i = 0; i < h; i += 4) {
            rect(p, x + w - 1, y + i, 1, Math.min(4, h - i), hsv(shift + (w + i) / (float) around, t.rainbow()));
            rect(p, x, y + h - i - Math.min(4, h - i), 1, Math.min(4, h - i), hsv(shift + (2 * w + h + i) / (float) around, t.rainbow()));
        }
    }

    /** A row of beads just inside the edge, every 6 pixels. */
    private static void beads(GuidePaint p, int c, int x, int y, int w, int h) {
        for (int i = 6; i < w - 6; i += 6) {
            rect(p, x + i, y + 2, 2, 2, c);
            rect(p, x + i, y + h - 4, 2, 2, c);
        }
        for (int i = 6; i < h - 6; i += 6) {
            rect(p, x + 2, y + i, 2, 2, c);
            rect(p, x + w - 4, y + i, 2, 2, c);
        }
    }

    /** A bookmark ribbon hanging from the top edge, 8 x 22 with a notched end. */
    private static void ribbon(GuidePaint p, int c, int x, int y) {
        rect(p, x, y, 8, 18, c);
        rect(p, x, y + 18, 3, 4, c);
        rect(p, x + 5, y + 18, 3, 4, c);
        rect(p, x + 3, y + 18, 2, 1, c);
        rect(p, x + 7, y, 1, 18, 0x40000000);
    }

    private static void frame(GuidePaint p, int fill, GuideTheme.Frame f, int x, int y, int w, int h) {
        rect(p, x + 1, y + 1, w - 2, h - 2, fill);
        rect(p, x + 2, y + 1, w - 4, 1, f.light());
        rect(p, x + 1, y + 2, w - 3, 1, f.light());
        rect(p, x + 1, y + 3, 2, h - 6, f.light());
        rect(p, x + 3, y + 3, 1, 1, f.light());
        rect(p, x + 3, y + h - 3, w - 4, 2, f.shadow());
        rect(p, x + w - 3, y + 3, 2, h - 6, f.shadow());
        rect(p, x + w - 4, y + h - 4, 1, 1, f.shadow());
        rect(p, x + 2, y, w - 4, 1, f.outline());
        rect(p, x + 2, y + h - 1, w - 4, 1, f.outline());
        rect(p, x, y + 2, 1, h - 4, f.outline());
        rect(p, x + w - 1, y + 2, 1, h - 4, f.outline());
        rect(p, x + 1, y + 1, 1, 1, f.outline());
        rect(p, x + w - 2, y + 1, 1, 1, f.outline());
        rect(p, x + 1, y + h - 2, 1, 1, f.outline());
        rect(p, x + w - 2, y + h - 2, 1, 1, f.outline());
    }

    /** Whether this theme draws boxes behind its parts (bevel and textured) rather than hairlines (flat). */
    public static boolean boxed(GuideTheme t) {
        return t.style() != GuideTheme.Style.FLAT;
    }

    /** The box behind a part of the screen (list, page, header, footer): a sunken well, a see-through box, or nothing. */
    public static void box(GuidePaint p, GuideTheme t, int x, int y, int w, int h) {
        switch (t.style()) {
            case BEVEL -> well(p, t, x, y, w, h, t.well().fill());
            case TEXTURED -> {
                rect(p, x, y, w, h, t.texture() == null ? t.well().fill() : t.texture().well());
                outline(p, x, y, w, h, t.wellLine());
            }
            default -> { }
        }
    }

    /** A sunken well (shadow top-left, light bottom-right) with {@code inner} inside. */
    public static void well(GuidePaint p, GuideTheme t, int x, int y, int w, int h, int inner) {
        GuideTheme.Frame f = t.well();
        rect(p, x, y, w - 1, 1, f.shadow());
        rect(p, x, y + 1, 1, h - 2, f.shadow());
        rect(p, x + 1, y + h - 1, w - 1, 1, f.light());
        rect(p, x + w - 1, y + 1, 1, h - 2, f.light());
        rect(p, x + 1, y + 1, w - 2, h - 2, inner);
    }

    /** An item slot ({@code size} 18) or a big output slot (26). */
    public static void slot(GuidePaint p, GuideTheme t, int x, int y, int size) {
        if (t.style() == GuideTheme.Style.BEVEL) {
            well(p, t, x, y, size, size, t.slot().fill());
            rect(p, x + size - 1, y, 1, 1, t.slot().fill());
            rect(p, x, y + size - 1, 1, 1, t.slot().fill());
        } else {
            rect(p, x + 1, y + 1, size - 2, size - 2, t.slot().fill());
            outline(p, x, y, size, size, t.slot().edge());
        }
    }

    /** A scroll bar {@code 4} wide: {@code at} 0..1 down a track {@code h} tall, thumb {@code thumb} tall. */
    public static void scrollbar(GuidePaint p, GuideTheme t, int x, int y, int h, int thumb, double at) {
        rect(p, x + 2, y, 3, h, t.colours().rule());
        int ty = y + (int) Math.round((h - thumb) * Math.max(0, Math.min(1, at)));
        rect(p, x + 2, ty, 3, thumb, t.colours().muted());
    }

    /** A 2-line rule (dark over light); a theme with one colour gets one line. */
    public static void rule(GuidePaint p, int x, int y, int w, int dark, int light) {
        rect(p, x, y, w, 1, dark);
        if (light != dark) rect(p, x, y + 1, w, 1, light);
    }

    /** The crafting table progress arrow, 22 x 15. */
    public static void craftArrow(GuidePaint p, GuideTheme t, int x, int y) {
        map(p, CRAFT_ARROW, x, y, t.style() == GuideTheme.Style.BEVEL ? t.well().fill() : t.colours().soft());
    }

    private static void map(GuidePaint p, String[] rows, int x, int y, int g) {
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            int n = row.length();
            int c = 0;
            while (c < n) {
                char ch = row.charAt(c);
                int e = c;
                while (e < n && row.charAt(e) == ch) e++;
                if (ch == 'G') rect(p, x + c, y + r, e - c, 1, g);
                c = e;
            }
        }
    }

    /** {@code a} to {@code b} by {@code f} (0..1), channel by channel. */
    static int mix(int a, int b, float f) {
        int out = 0;
        for (int s = 0; s < 32; s += 8) {
            int ca = a >>> s & 0xFF;
            int cb = b >>> s & 0xFF;
            out |= Math.round(ca + (cb - ca) * f) << s;
        }
        return out;
    }

    /** {@code argb} with its alpha replaced. */
    static int alpha(int argb, int a) {
        return (a & 0xFF) << 24 | argb & 0xFFFFFF;
    }

    private static int hsv(float hue, float sat) {
        float h = (hue % 1F + 1F) % 1F * 6F;
        int i = (int) h;
        float f = h - i;
        float v = 1F;
        float p = v * (1 - sat);
        float q = v * (1 - sat * f);
        float u = v * (1 - sat * (1 - f));
        float r;
        float g;
        float b;
        switch (i) {
            case 0 -> { r = v; g = u; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = u; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = u; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return 0xFF000000 | Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }
}
