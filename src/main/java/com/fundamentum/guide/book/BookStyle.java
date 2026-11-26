package com.fundamentum.guide.book;

import com.fundamentum.port.FId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a guide book looks: its textures, colours, frame window and animation, read from
 * {@code assets/<ns>/fundamentum/book_styles/<id>.json}. Its layer textures are
 * {@code <ns>:textures/gui/book/<id>/<layer>.png}. Every position is relative to the screen centre. The same file on every
 * version.
 */
public record BookStyle(FId id, String name, Map<String, Integer> colors, int titleText, Integer titleShadow, boolean titleUpper,
                        int cardText, int stampText, int ruleColor, String bullet, boolean roman, boolean hunter, boolean logHeader,
                        String frame, Window window, Caption caption, int stampLabelDy, Map<String, Layer> layers, int openTicks,
                        int turnTicks, int closeTicks, int stampTicks, Map<String, FId> sounds) {

    public static final String FOLDER = "fundamentum/book_styles";

    /** Where the mob is drawn inside the portrait frame. */
    public record Window(int x, int y, int w, int h, boolean oval, int ground) { }

    public record Caption(int x, int y, int color) { }

    /** One texture of the style: drawn at x, y (from the centre), w x h of a tw x th file. */
    public record Layer(String name, int x, int y, int w, int h, int tw, int th) { }

    public int color(String key) {
        return this.colors.getOrDefault(key, 0x3a2414);
    }

    public Layer layer(String name) {
        return this.layers.get(name);
    }

    public FId texture(String layer) {
        return FId.of(this.id.namespace(), "textures/gui/book/" + this.id.path() + "/" + layer + ".png");
    }

    public FId sound(String key) {
        return this.sounds.get(key);
    }

    public static BookStyle parse(FId id, String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        Map<String, Integer> colors = new LinkedHashMap<>();
        if (o.has("colors")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("colors").entrySet()) colors.put(e.getKey(), hex(e.getValue().getAsString()));
        }
        JsonObject w = o.getAsJsonObject("window");
        Window window = w == null ? new Window(-165, -67, 74, 70, false, 56)
            : new Window(i(w, "x"), i(w, "y"), i(w, "w"), i(w, "h"), w.has("oval") && w.get("oval").getAsBoolean(), i(w, "ground"));
        Caption caption = null;
        if (o.has("caption") && o.get("caption").isJsonObject()) {
            JsonObject c = o.getAsJsonObject("caption");
            caption = new Caption(i(c, "x"), i(c, "y"), hex(BookData.str(c, "color", "3a4a7a")));
        }
        Map<String, Layer> layers = new LinkedHashMap<>();
        if (o.has("layers")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("layers").entrySet()) {
                JsonObject l = e.getValue().getAsJsonObject();
                layers.put(e.getKey(), new Layer(e.getKey(), i(l, "x"), i(l, "y"), i(l, "w"), i(l, "h"), i(l, "tw"), i(l, "th")));
            }
        }
        JsonObject anim = o.has("animation") ? o.getAsJsonObject("animation") : new JsonObject();
        Map<String, FId> sounds = new LinkedHashMap<>();
        if (o.has("sounds")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("sounds").entrySet()) {
                FId s = e.getValue().isJsonNull() ? null : FId.tryOf(e.getValue().getAsString());
                if (s != null) sounds.put(e.getKey(), s);
            }
        }
        String shadow = BookData.str(o, "title_shadow", null);
        return new BookStyle(id, BookData.str(o, "name", id.path()), Map.copyOf(colors), hex(BookData.str(o, "title_text", "f8e8c8")),
            shadow == null ? null : hex(shadow), !o.has("title_upper") || o.get("title_upper").getAsBoolean(),
            hex(BookData.str(o, "card_text", "3a2414")), hex(BookData.str(o, "stamp_text", "3a2414")),
            hex(BookData.str(o, "rule_color", "d4bf90")), BookData.str(o, "bullet", "-"), "roman".equals(BookData.str(o, "numerals", "")),
            "hunter".equals(BookData.str(o, "right", "")), "log_entry".equals(BookData.str(o, "header", "")),
            BookData.str(o, "frame", "plain"), window, caption, o.has("stamp_label_dy") ? o.get("stamp_label_dy").getAsInt() : 16,
            layers, iOr(anim, "open_ticks", 16), iOr(anim, "turn_ticks", 12), iOr(anim, "close_ticks", 16), iOr(anim, "stamp_ticks", 11),
            Map.copyOf(sounds));
    }

    static int hex(String s) {
        String t = s.startsWith("#") ? s.substring(1) : s;
        return Integer.parseInt(t, 16) & 0xFFFFFF;
    }

    private static int i(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsInt() : 0;
    }

    private static int iOr(JsonObject o, String key, int fallback) {
        return o.has(key) ? o.get(key).getAsInt() : fallback;
    }

    /** "ix", "- ix -" style page numbers (roman) or plain digits. */
    public String pageNumber(int n) {
        return this.roman ? "- " + roman(n) + " -" : Integer.toString(n);
    }

    public static String roman(int n) {
        if (n <= 0) return Integer.toString(n);
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < v.length; k++) {
            while (n >= v[k]) {
                b.append(s[k]);
                n -= v[k];
            }
        }
        return b.toString();
    }
}
