package com.fundamentum.guide.client;

import com.fundamentum.port.FId;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** The few drawing calls the guide needs, so the shared screen code draws the same on every version. */
public interface GuidePaint {
    Font font();

    void fill(int x0, int y0, int x1, int y1, int argb);

    void text(Component text, int x, int y, int argb, boolean shadow);

    void item(ItemStack stack, int x, int y);

    /** Draws the top-left {@code w} x {@code h} pixels of a {@code texW} x {@code texH} texture at x, y. */
    void texture(FId texture, int x, int y, int w, int h, int texW, int texH);

    void scissor(int x0, int y0, int x1, int y1);

    void endScissor();

    void tooltip(Component text, int mx, int my);

    void tooltip(ItemStack stack, int mx, int my);

    /** Moves then scales everything drawn until the matching {@link #pop()}. */
    void push(float tx, float ty, float sx, float sy);

    void pop();

    /** Shows the left-right resize cursor for this frame, on versions that have one. */
    default void resizeCursor() {
    }

    /**
     * Draws a mob of {@code type} in the screen box x0..x1, y0..y1 (feet near {@code feet}), turned toward the mouse.
     * False when the mob can't be made.
     */
    boolean entity(FId type, int x0, int y0, int x1, int y1, int feet, int mx, int my, float zoom);

    default boolean entity(FId type, int x0, int y0, int x1, int y1, int feet, int mx, int my) {
        return entity(type, x0, y0, x1, y1, feet, mx, my, 1F);
    }

    /** Something drawn on a {@code GuideSurface}: the screen's frame, tabs, list and arrows. */
    interface Layer {
        void paint(GuidePaint p, int mx, int my);

        /** A left click; false lets the widgets under it have the click. */
        default boolean click(double mx, double my) {
            return false;
        }

        default boolean scroll(double mx, double my, double amount) {
            return false;
        }

        /** The mouse moving with the left button held after a click this layer took. */
        default void drag(double mx, double my) {
        }

        /** The left button let go after a click this layer took. */
        default void release(double mx, double my) {
        }

        /** Whether the mouse is over something this layer handles (the widgets get the mouse everywhere else). */
        default boolean over(double mx, double my) {
            return false;
        }
    }
}
