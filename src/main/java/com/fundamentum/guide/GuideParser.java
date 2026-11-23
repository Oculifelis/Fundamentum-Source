package com.fundamentum.guide;

import com.fundamentum.port.FId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a guide page. The file starts with front matter between {@code ---} lines ({@code title:}, {@code chapter:},
 * {@code icon:}, {@code items:} (comma separated), {@code unlock:}, {@code order:}), then the body: {@code #} headings,
 * paragraphs (lines joined until a blank line), {@code -} bullets, {@code ---} dividers, and embeds on a line of their
 * own: {@code {{item ...}}}, {@code {{recipe ...}}}, {@code {{multiblock ...}}}, {@code {{entity ...}}} and
 * {@code {{image ...}}}. Inside text: {@code **bold**}, {@code *italic*}, {@code `code`} and {@code [text](ns:page)}.
 * Nothing here throws; what can't be read goes into the page's problems. The same file on every version.
 */
public final class GuideParser {
    private GuideParser() { }

    /** Parses {@code source} as page {@code id}; a page without a chapter goes to {@code <ns>:main}. */
    public static GuidePage parse(FId id, String source) {
        List<String> problems = new ArrayList<>();
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int start = 0;
        String title = null;
        FId chapter = null;
        FId icon = null;
        List<FId> items = new ArrayList<>();
        FId unlock = null;
        int order = 0;
        if (lines.length > 0 && lines[0].trim().equals("---")) {
            int end = -1;
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].trim().equals("---")) {
                    end = i;
                    break;
                }
            }
            if (end < 0) {
                problems.add("front matter has no closing ---");
            } else {
                for (int i = 1; i < end; i++) {
                    String line = lines[i].trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int colon = line.indexOf(':');
                    if (colon < 0) {
                        problems.add("line " + (i + 1) + ": expected key: value");
                        continue;
                    }
                    String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String value = unquote(line.substring(colon + 1).trim());
                    switch (key) {
                        case "title" -> title = value;
                        case "chapter" -> chapter = id(value, id.namespace(), "chapter", problems);
                        case "icon" -> icon = id(value, "minecraft", "icon", problems);
                        case "items", "item" -> {
                            for (String s : value.split(",")) {
                                if (s.isBlank()) continue;
                                FId item = id(s.trim(), "minecraft", "items", problems);
                                if (item != null) items.add(item);
                            }
                        }
                        case "unlock" -> unlock = id(value, id.namespace(), "unlock", problems);
                        case "order" -> {
                            try {
                                order = Integer.parseInt(value);
                            } catch (NumberFormatException e) {
                                problems.add("order is not a number: " + value);
                            }
                        }
                        default -> problems.add("unknown front matter key " + key);
                    }
                }
                start = end + 1;
            }
        }
        List<GuidePage.Block> blocks = new ArrayList<>();
        StringBuilder para = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.trim();
            int lineNo = i + 1;
            boolean special = line.isEmpty() || line.startsWith("#") || line.startsWith("- ") || line.startsWith("* ")
                || line.equals("---") || (line.startsWith("{{") && line.endsWith("}}"));
            if (special && para.length() > 0) {
                blocks.add(new GuidePage.Paragraph(spans(para.toString(), id.namespace(), problems)));
                para.setLength(0);
            }
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') level++;
                blocks.add(new GuidePage.Heading(Math.min(3, level), spans(line.substring(level).trim(), id.namespace(), problems)));
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                blocks.add(new GuidePage.ListItem(spans(line.substring(2).trim(), id.namespace(), problems)));
            } else if (line.equals("---")) {
                blocks.add(new GuidePage.Rule());
            } else if (line.startsWith("{{") && line.endsWith("}}")) {
                GuidePage.Block embed = embed(line.substring(2, line.length() - 2).trim(), id.namespace(), lineNo, problems);
                if (embed != null) blocks.add(embed);
            } else {
                if (para.length() > 0) para.append(' ');
                para.append(line);
            }
        }
        if (para.length() > 0) blocks.add(new GuidePage.Paragraph(spans(para.toString(), id.namespace(), problems)));
        if (title == null) {
            for (GuidePage.Block b : blocks) {
                if (b instanceof GuidePage.Heading h) {
                    title = plain(h.text());
                    break;
                }
            }
        }
        if (title == null || title.isBlank()) title = id.path();
        if (chapter == null) chapter = FId.of(id.namespace(), "main");
        return new GuidePage(id, title, chapter, icon, List.copyOf(items), unlock, order, List.copyOf(blocks), List.copyOf(problems));
    }

    /** The text of spans without styles. */
    public static String plain(List<GuidePage.Span> spans) {
        StringBuilder b = new StringBuilder();
        for (GuidePage.Span s : spans) b.append(s.text());
        return b.toString();
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** An id, with {@code defaultNs} added when the text has no namespace. */
    static FId id(String text, String defaultNs, String what, List<String> problems) {
        String t = text.trim();
        FId id = FId.tryOf(t.contains(":") ? t : defaultNs + ":" + t);
        if (id == null) problems.add("bad " + what + " id " + t);
        return id;
    }

    private static GuidePage.Block embed(String body, String ns, int lineNo, List<String> problems) {
        String[] parts = body.split("\\s+");
        String kind = parts[0].toLowerCase(Locale.ROOT);
        String where = "line " + lineNo + ": ";
        switch (kind) {
            case "item", "items" -> {
                List<FId> ids = new ArrayList<>();
                for (int i = 1; i < parts.length; i++) {
                    FId id = id(parts[i], "minecraft", "item", problems);
                    if (id != null) ids.add(id);
                }
                if (ids.isEmpty()) {
                    problems.add(where + "item embed names no item");
                    return null;
                }
                return new GuidePage.ItemEmbed(List.copyOf(ids));
            }
            case "recipe" -> {
                if (parts.length < 3) {
                    problems.add(where + "recipe embed needs a category and a key");
                    return null;
                }
                FId cat = FId.tryOf(parts[1]);
                if (cat == null) {
                    problems.add(where + "bad recipe category " + parts[1]);
                    return null;
                }
                return new GuidePage.RecipeEmbed(cat.toString(), parts[2]);
            }
            case "multiblock" -> {
                if (parts.length < 2) {
                    problems.add(where + "multiblock embed needs a pattern id");
                    return null;
                }
                FId id = id(parts[1], ns, "multiblock", problems);
                return id == null ? null : new GuidePage.MultiblockEmbed(id);
            }
            case "entity" -> {
                if (parts.length < 2) {
                    problems.add(where + "entity embed needs an entity type");
                    return null;
                }
                FId id = id(parts[1], "minecraft", "entity", problems);
                int scale = 30;
                for (int i = 2; i < parts.length; i++) {
                    if (parts[i].startsWith("scale=")) {
                        try {
                            scale = Math.max(4, Math.min(120, Integer.parseInt(parts[i].substring(6))));
                        } catch (NumberFormatException e) {
                            problems.add(where + "bad scale " + parts[i]);
                        }
                    }
                }
                return id == null ? null : new GuidePage.EntityEmbed(id, scale);
            }
            case "image" -> {
                if (parts.length < 2) {
                    problems.add(where + "image embed needs a texture");
                    return null;
                }
                FId tex = id(parts[1], ns, "image", problems);
                int w = 64;
                int h = 64;
                if (parts.length > 2) {
                    String[] size = parts[2].toLowerCase(Locale.ROOT).split("x");
                    try {
                        w = Integer.parseInt(size[0]);
                        h = size.length > 1 ? Integer.parseInt(size[1]) : w;
                    } catch (NumberFormatException e) {
                        problems.add(where + "bad image size " + parts[2]);
                    }
                }
                return tex == null ? null : new GuidePage.ImageEmbed(tex, Math.max(1, w), Math.max(1, h));
            }
            default -> {
                problems.add(where + "unknown embed " + kind);
                return null;
            }
        }
    }

    /** Splits inline text into styled spans: {@code **bold**}, {@code *italic*}, {@code `code`}, {@code [text](page)}. */
    public static List<GuidePage.Span> spans(String text, String ns, List<String> problems) {
        List<GuidePage.Span> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean bold = false;
        boolean italic = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                cur.append(text.charAt(i + 1));
                i += 2;
            } else if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end < 0) {
                    cur.append(c);
                    i++;
                    continue;
                }
                flush(out, cur, bold, italic);
                out.add(new GuidePage.Span(text.substring(i + 1, end), bold, italic, true, null));
                i = end + 1;
            } else if (c == '*' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                flush(out, cur, bold, italic);
                bold = !bold;
                i += 2;
            } else if (c == '*') {
                flush(out, cur, bold, italic);
                italic = !italic;
                i++;
            } else if (c == '[') {
                int close = text.indexOf("](", i);
                int paren = close < 0 ? -1 : text.indexOf(')', close);
                if (close < 0 || paren < 0) {
                    cur.append(c);
                    i++;
                    continue;
                }
                flush(out, cur, bold, italic);
                String label = text.substring(i + 1, close);
                String target = text.substring(close + 2, paren).trim();
                FId link = id(target, ns, "link", problems);
                out.add(new GuidePage.Span(label, bold, italic, false, link));
                i = paren + 1;
            } else {
                cur.append(c);
                i++;
            }
        }
        flush(out, cur, bold, italic);
        return List.copyOf(out);
    }

    private static void flush(List<GuidePage.Span> out, StringBuilder cur, boolean bold, boolean italic) {
        if (cur.length() == 0) return;
        out.add(new GuidePage.Span(cur.toString(), bold, italic, false, null));
        cur.setLength(0);
    }
}
