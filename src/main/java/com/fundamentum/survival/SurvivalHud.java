package com.fundamentum.survival;

import com.fundamentum.port.FId;
import com.fundamentum.stat.Stats;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;

/**
 * Where the survival meters go on the HUD, worked out the same way on every version. Meters stack upward above the
 * hotbar, over the hunger bar (side right, the default) or over the hearts (side left), clear of the armor and air rows.
 * A bar row is 7 pixels tall, a text row 10. Within a side, meters go by {@code hud.order} and then id.
 */
public final class SurvivalHud {
    private SurvivalHud() { }

    public static final int WIDTH = 81;
    public static final int BAR_HEIGHT = 5;
    public static final int BAR_ROW = 7;
    public static final int TEXT_ROW = 10;
    /** The first row's top, measured up from the bottom of the screen. */
    public static final int BOTTOM = 59;

    /** One meter to draw: a bar (with {@code fill} 0 to 1) or a line of text, at x, y. */
    public record Row(FId meter, int x, int y, int width, double fill, int color, Component label, String value, boolean bar) {
        /** The text for a text row: label and value. */
        public Component text() {
            return Component.empty().append(this.label).append(": " + this.value);
        }
    }

    /** The rows for these meters and values on a screen {@code w} by {@code h}; meters without a value are left out. */
    public static List<Row> rows(Map<FId, Meter> meters, Map<FId, Double> values, int w, int h) {
        List<Meter> shown = new ArrayList<>();
        for (Meter m : meters.values()) {
            if (!"none".equals(m.hud().style()) && values.containsKey(m.stat())) shown.add(m);
        }
        shown.sort(Comparator.<Meter>comparingInt(m -> m.hud().order()).thenComparing(m -> m.id().toString()));
        List<Row> out = new ArrayList<>();
        int left = h - BOTTOM;
        int right = h - BOTTOM;
        for (Meter m : shown) {
            boolean bar = "bar".equals(m.hud().style());
            boolean onLeft = "left".equals(m.hud().side());
            int x = onLeft ? w / 2 - 91 : w / 2 + 10;
            int y = onLeft ? left : right;
            int step = bar ? BAR_ROW : TEXT_ROW;
            double v = values.get(m.stat());
            Component label = Component.translatableWithFallback(m.hud().label(), fallbackName(m.id()));
            out.add(new Row(m.id(), x, bar ? y + (BAR_ROW - BAR_HEIGHT) : y, WIDTH, m.fill(v), 0xFF000000 | m.hud().color(), label,
                Stats.format(m.stat(), v), bar));
            if (onLeft) left -= step;
            else right -= step;
        }
        return out;
    }

    /**
     * One side's meters as a HUD element: rows placed inside a box {@link #WIDTH} wide, stacking upward from the box's
     * bottom (the first meter lowest), with the box's height. {@code left} picks the meters on the hearts side.
     */
    public static Stack stack(Map<FId, Meter> meters, Map<FId, Double> values, boolean left) {
        List<Meter> shown = new ArrayList<>();
        for (Meter m : meters.values()) {
            if (!"none".equals(m.hud().style()) && values.containsKey(m.stat()) && "left".equals(m.hud().side()) == left) shown.add(m);
        }
        shown.sort(Comparator.<Meter>comparingInt(m -> m.hud().order()).thenComparing(m -> m.id().toString()));
        int height = 0;
        for (Meter m : shown) height += "bar".equals(m.hud().style()) ? BAR_ROW : TEXT_ROW;
        List<Row> out = new ArrayList<>();
        int bottom = height;
        for (Meter m : shown) {
            boolean bar = "bar".equals(m.hud().style());
            int step = bar ? BAR_ROW : TEXT_ROW;
            int y = bottom - step;
            double v = values.get(m.stat());
            Component label = Component.translatableWithFallback(m.hud().label(), fallbackName(m.id()));
            out.add(new Row(m.id(), 0, bar ? y + (BAR_ROW - BAR_HEIGHT) : y, WIDTH, m.fill(v), 0xFF000000 | m.hud().color(), label,
                Stats.format(m.stat(), v), bar));
            bottom -= step;
        }
        return new Stack(List.copyOf(out), height);
    }

    /** One side's rows and the height of their box. */
    public record Stack(List<Row> rows, int height) {
    }

    /** "mymod:body_temperature" reads "Body Temperature" when no translation exists. */
    public static String fallbackName(FId id) {
        String path = id.path();
        int slash = path.lastIndexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
