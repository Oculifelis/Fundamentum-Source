package com.fundamentum.recipeview;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

/**
 * Items drawn on screens that are not inventories (a guide page, for one), so JEI, EMI and REI can answer their keys for them:
 * show recipes, show uses, bookmark. A screen registers a {@link Source}; each installed viewer asks the sources what is
 * under the mouse when one of its keys is pressed. Client only.
 */
public final class ViewHover {
    /** A stack under the mouse and the box it is drawn in, in screen coordinates. */
    public record Hit(ItemStack stack, int x, int y, int w, int h) {}

    /** Says what stack, if any, is under the mouse on a screen; null for none. */
    @FunctionalInterface
    public interface Source {
        Hit at(Screen screen, double mx, double my);
    }

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    private ViewHover() {}

    public static void source(Source s) {
        SOURCES.add(s);
    }

    /** The first stack a source reports under the mouse, or null. */
    public static Hit at(Screen screen, double mx, double my) {
        if (screen == null) return null;
        for (Source s : SOURCES) {
            try {
                Hit h = s.at(screen, mx, my);
                if (h != null && !h.stack().isEmpty()) return h;
            } catch (RuntimeException ignored) {
                // a broken source never breaks a viewer's key
            }
        }
        return null;
    }
}
