package com.fundamentum.client;

/**
 * Where a HUD box goes on screen (no Minecraft classes, so it is unit tested). Sizes are GUI pixels, the box already
 * scaled.
 */
public final class HudBox {
    /** Hotbar height in GUI pixels. */
    public static final int HOTBAR_H = 22;

    /** Which part of the screen a HUD sits against; {@link #fx}, {@link #fy} are the fraction of the screen (and box). */
    public enum Anchor {
        /** Beside the hotbar on the right, centred on it (past the attack indicator or a left-hander's offhand slot). */
        HOTBAR_RIGHT(-1, -1),
        TOP_LEFT(0, 0), TOP_CENTER(0.5F, 0), TOP_RIGHT(1, 0),
        MIDDLE_LEFT(0, 0.5F), MIDDLE_RIGHT(1, 0.5F),
        BOTTOM_LEFT(0, 1), BOTTOM_CENTER(0.5F, 1), BOTTOM_RIGHT(1, 1),
        /** The middle of the screen. */
        CENTER(0.5F, 0.5F);

        public final float fx;
        public final float fy;

        Anchor(float fx, float fy) {
            this.fx = fx;
            this.fy = fy;
        }
    }

    private HudBox() {
    }

    /**
     * The box's top left, {x, y}: against {@code anchor} on a {@code guiW} x {@code guiH} screen, moved by
     * {@code dx}, {@code dy}, kept on screen. {@code hotbarX} is the first free x right of the hotbar, for
     * {@link Anchor#HOTBAR_RIGHT}.
     */
    public static int[] place(Anchor anchor, int guiW, int guiH, int w, int h, int hotbarX, int dx, int dy) {
        int x;
        int y;
        if (anchor == Anchor.HOTBAR_RIGHT) {
            x = hotbarX + dx;
            y = guiH - HOTBAR_H + (HOTBAR_H - h) / 2 + dy;
        } else {
            x = Math.round(anchor.fx * (guiW - w)) + dx;
            y = Math.round(anchor.fy * (guiH - h)) + dy;
        }
        x = Math.max(0, Math.min(guiW - w, x));
        y = Math.max(0, Math.min(guiH - h, y));
        return new int[] {x, y};
    }

    /** The first free x right of a hotbar centred on a {@code guiW} wide screen, given what already sits there. */
    public static int hotbarRight(int guiW, boolean offhandSlotRight, boolean attackIndicatorRight) {
        int x = guiW / 2 + 91 + 6;
        if (offhandSlotRight) {
            return guiW / 2 + 91 + 29 + 4;
        }
        return attackIndicatorRight ? x + 22 : x;
    }

    /** {@code rgb} ("#RRGGBB" or "RRGGBB") with {@code opacity} (0-1, at least 0.1) as ARGB; white if it can't be read. */
    public static int argb(String rgb, double opacity) {
        int c = 0xFFFFFF;
        try {
            c = Integer.parseInt(rgb.replace("#", ""), 16) & 0xFFFFFF;
        } catch (RuntimeException ignored) {
            // keeps white
        }
        int a = Math.max(26, Math.min(255, (int) Math.round(opacity * 255)));
        return a << 24 | c;
    }
}
