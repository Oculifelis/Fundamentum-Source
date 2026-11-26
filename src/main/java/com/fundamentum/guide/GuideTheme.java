package com.fundamentum.guide;

import com.fundamentum.port.FId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How the guide screen looks: the panel (flat, bevelled like a container, or tiled with block textures), the text
 * colours, how a book's accent colour is shifted to read on it, the item slots and the page colours, plus extras such as
 * a gradient fill, an animated rainbow border or a beaded edge. Read from {@code assets/<ns>/fundamentum/guide_themes/<path>.json}
 * (id {@code ns:path}) and from loose files in {@code config/fundamentum/guide_themes/}. The player picks one in the
 * theme picker; a book may also name one with {@code "theme"} in its chapters.json. Every field is optional and falls
 * back to {@link #DEFAULT}, the vanilla container look. The same file on every version.
 */
public record GuideTheme(String name, List<String> tags, Style style, int backdrop, Colours colours, boolean shadow,
                         float accentShift, int selection, Frame panel, Frame well, int wellLine, Page page, Slot slot,
                         Texture texture, int gradientTop, int gradientBottom, float rainbow, float rainbowSpeed,
                         int beads, int ribbon) {

    public static final String FOLDER = "fundamentum/guide_themes";
    public static final FId DEFAULT_ID = FId.of("fundamentum", "default");

    /** flat: one fill and a hairline frame; bevel: container edges and sunken wells; textured: tiled blocks and see-through wells. */
    public enum Style { FLAT, BEVEL, TEXTURED }

    /**
     * Screen colours. {@code fill} the panel, {@code frame} its outline, {@code rule} the hairlines, {@code title} the
     * header, {@code muted} hints and counts, {@code text} open rows and the search text, {@code strong} the selected
     * row, {@code soft} closed rows, {@code mod} closed mod rows, {@code nav} the page arrows in the footer.
     */
    public record Colours(int fill, int frame, int rule, int title, int muted, int text, int strong, int soft, int mod, int nav) { }

    /** A bevelled box: fill, top-left light edge, bottom-right shadow edge, and the outline around it. */
    public record Frame(int fill, int light, int shadow, int outline) { }

    /** Page colours. {@code title} and {@code link} are 0 when they follow the book's accent. */
    public record Page(int text, int title, int heading, int link, int code, int muted, int ruleDark, int ruleLight, boolean shadow) {
        /** This palette with the accent filled in where the theme left it to the book. */
        public Page withAccent(int accent) {
            return new Page(this.text, this.title == 0 ? accent : this.title, this.heading == 0 ? accent : this.heading,
                this.link == 0 ? accent : this.link, this.code, this.muted, this.ruleDark, this.ruleLight, this.shadow);
        }
    }

    public record Slot(int fill, int edge) { }

    /**
     * Block textures tiled at {@code size} GUI pixels: {@code background} inside, {@code border} (or none) round the
     * edge {@code borderWidth} wide, each under its tint. {@code well} is the see-through box behind text.
     */
    public record Texture(FId background, FId border, int size, int tint, int borderTint, int borderWidth, int well) { }

    private static final String DEFAULT_JSON = """
        {
          "name": "Container",
          "tags": ["light", "minecraft"],
          "style": "bevel",
          "backdrop": "#A0101010",
          "colors": {"fill": "#C6C6C6", "frame": "#000000", "rule": "#8B8B8B", "title": "#404040", "muted": "#404040",
                     "text": "#FFFFFF", "strong": "#FFFFFF", "soft": "#E0E0E0", "mod": "#FFFFFF", "nav": "#404040"},
          "shadow": true,
          "accent": {"shift": -0.5, "selection": 60},
          "bevel": {"light": "#FFFFFF", "shadow": "#555555"},
          "well": {"fill": "#8B8B8B", "light": "#FFFFFF", "shadow": "#373737", "line": "#000000"},
          "page": {"text": "#FFFFFF", "heading": "#FFFFFF", "code": "#FFE080", "muted": "#E0E0E0",
                   "rule": ["#373737", "#FFFFFF"], "shadow": true},
          "slot": {"fill": "#8B8B8B", "edge": "#373737"}
        }
        """;

    public static final GuideTheme DEFAULT = parse(new JsonObject(), new ArrayList<>());

    /** Reads a theme; fields it leaves out come from the default. Bad values are reported and replaced by magenta. */
    public static GuideTheme parse(JsonObject json, List<String> problems) {
        JsonObject j = merge(JsonParser.parseString(DEFAULT_JSON).getAsJsonObject(), json);
        JsonObject c = j.getAsJsonObject("colors");
        JsonObject bevel = j.getAsJsonObject("bevel");
        JsonObject well = j.getAsJsonObject("well");
        JsonObject page = j.getAsJsonObject("page");
        JsonObject slot = j.getAsJsonObject("slot");
        JsonObject acc = j.getAsJsonObject("accent");
        JsonArray rule = page.getAsJsonArray("rule");
        Style style;
        try {
            style = Style.valueOf(j.get("style").getAsString().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            problems.add("style: \"" + j.get("style") + "\" is not flat, bevel or textured");
            style = Style.FLAT;
        }
        Colours colours = new Colours(col(c, "fill", problems), col(c, "frame", problems), col(c, "rule", problems),
            col(c, "title", problems), col(c, "muted", problems), col(c, "text", problems), col(c, "strong", problems),
            col(c, "soft", problems), col(c, "mod", problems), col(c, "nav", problems));
        int top = 0;
        int bottom = 0;
        if (j.has("gradient") && j.get("gradient").isJsonArray() && j.getAsJsonArray("gradient").size() == 2) {
            top = colour(j.getAsJsonArray("gradient").get(0), "gradient", problems);
            bottom = colour(j.getAsJsonArray("gradient").get(1), "gradient", problems);
        }
        float rainbow = 0;
        float speed = 0;
        if (j.has("rainbow") && j.get("rainbow").isJsonObject()) {
            JsonObject r = j.getAsJsonObject("rainbow");
            rainbow = num(r, "saturation", 0.55F);
            speed = num(r, "speed", 1F);
        }
        List<String> tags = new ArrayList<>();
        if (j.has("tags") && j.get("tags").isJsonArray()) {
            for (JsonElement e : j.getAsJsonArray("tags")) if (e.isJsonPrimitive()) tags.add(e.getAsString().toLowerCase(Locale.ROOT));
        }
        return new GuideTheme(j.has("name") ? j.get("name").getAsString() : "", List.copyOf(tags), style,
            colour(j.get("backdrop"), "backdrop", problems), colours, bool(j, "shadow"),
            Math.max(-1, Math.min(1, num(acc, "shift", 0))), Math.max(0, Math.min(255, (int) num(acc, "selection", 60))),
            new Frame(colours.fill(), col(bevel, "light", problems), col(bevel, "shadow", problems), colours.frame()),
            new Frame(col(well, "fill", problems), col(well, "light", problems), col(well, "shadow", problems), 0),
            col(well, "line", problems),
            new Page(col(page, "text", problems), opt(page, "title", problems), opt(page, "heading", problems),
                opt(page, "link", problems), col(page, "code", problems), col(page, "muted", problems),
                colour(rule.get(0), "page.rule", problems), colour(rule.get(rule.size() > 1 ? 1 : 0), "page.rule", problems),
                bool(page, "shadow")),
            new Slot(col(slot, "fill", problems), col(slot, "edge", problems)),
            texture(j, problems), top, bottom, rainbow, speed,
            j.has("beads") ? colour(j.get("beads"), "beads", problems) : 0,
            j.has("ribbon") ? colour(j.get("ribbon"), "ribbon", problems) : 0);
    }

    /** A book's accent shifted to read on this theme: darker for a light panel, lighter for some dark ones. */
    public int accent(int argb) {
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        float f = this.accentShift;
        if (f < 0) {
            r = (int) (r * (1 + f));
            g = (int) (g * (1 + f));
            b = (int) (b * (1 + f));
        } else if (f > 0) {
            r = (int) (r + (255 - r) * f);
            g = (int) (g + (255 - g) * f);
            b = (int) (b + (255 - b) * f);
        }
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** The shown name: the file's {@code "name"}, or the id's path in title case. */
    public String label(FId id) {
        if (!this.name.isEmpty()) return this.name;
        String[] words = id.path().replace('/', ' ').replace('_', ' ').split(" ");
        StringBuilder s = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (!s.isEmpty()) s.append(' ');
            s.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return s.toString();
    }

    /** The theme with this id, else the default (which packs may replace too). */
    public static GuideTheme of(Map<FId, GuideTheme> themes, FId id) {
        GuideTheme t = id == null ? null : themes.get(id);
        if (t != null) return t;
        t = themes.get(DEFAULT_ID);
        return t != null ? t : DEFAULT;
    }

    private static Texture texture(JsonObject j, List<String> problems) {
        if (!j.has("texture") || !j.get("texture").isJsonObject()) return null;
        JsonObject t = j.getAsJsonObject("texture");
        FId bg = t.has("background") ? FId.tryOf(t.get("background").getAsString()) : null;
        if (bg == null) {
            problems.add("texture: needs a \"background\" like minecraft:textures/block/deepslate_tiles.png");
            return null;
        }
        FId border = t.has("border") ? FId.tryOf(t.get("border").getAsString()) : null;
        return new Texture(bg, border, t.has("size") ? Math.max(1, t.get("size").getAsInt()) : 16,
            t.has("tint") ? colour(t.get("tint"), "texture.tint", problems) : 0,
            t.has("border_tint") ? colour(t.get("border_tint"), "texture.border_tint", problems) : 0,
            t.has("border_width") ? Math.max(0, Math.min(16, t.get("border_width").getAsInt())) : 5,
            t.has("well") ? colour(t.get("well"), "texture.well", problems) : 0xA0000000);
    }

    private static float num(JsonObject o, String key, float def) {
        try {
            return o != null && o.has(key) ? o.get(key).getAsFloat() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }

    private static int col(JsonObject o, String key, List<String> problems) {
        return colour(o.get(key), key, problems);
    }

    /** An optional colour: 0 when left out, so the caller fills in the accent. */
    private static int opt(JsonObject o, String key, List<String> problems) {
        return o.has(key) ? colour(o.get(key), key, problems) : 0;
    }

    /** "#RRGGBB" (opaque) or "#AARRGGBB". */
    static int colour(JsonElement e, String what, List<String> problems) {
        String s = e != null && e.isJsonPrimitive() ? e.getAsString().trim() : "";
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 6) return 0xFF000000 | Integer.parseUnsignedInt(s, 16);
            if (s.length() == 8) return Integer.parseUnsignedInt(s, 16);
        } catch (NumberFormatException ignored) {
            // reported below
        }
        problems.add(what + ": \"" + s + "\" is not a colour (#RRGGBB or #AARRGGBB)");
        return 0xFFFF00FF;
    }

    private static JsonObject merge(JsonObject base, JsonObject over) {
        for (Map.Entry<String, JsonElement> e : over.entrySet()) {
            JsonElement b = base.get(e.getKey());
            if (b != null && b.isJsonObject() && e.getValue().isJsonObject()) merge(b.getAsJsonObject(), e.getValue().getAsJsonObject());
            else base.add(e.getKey(), e.getValue());
        }
        return base;
    }
}
