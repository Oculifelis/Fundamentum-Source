package com.fundamentum.guide.client;

import com.fundamentum.guide.GuideTheme;
import com.fundamentum.guide.Guides;
import com.fundamentum.port.FId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Picks the guide's theme: every loaded theme as a small preview, filtered by a search and by tag chips (match all or
 * any). Clicking a preview applies it at once. Also holds the "books may use their own theme" switch and says where
 * loose theme files go. Opened from the guide with T or the Theme button; Escape returns to the guide. Client only; the
 * same file on every version.
 */
public final class GuideThemePicker extends Screen {
    private static final int COLS = 4;
    private static final int GAP = 6;

    private static final Set<String> chosenTags = new LinkedHashSet<>();
    private static boolean matchAll = true;
    private static String lastSearch = "";

    private final Screen parent;
    private String query = lastSearch;
    private int scroll;
    private EditBox search;

    // layout, set in init()
    private GuideTheme theme = GuideTheme.DEFAULT;
    private int px, py, pw, ph;
    private int gx, gy, gw, gh, cellW, cellH;
    private final List<Chip> chips = new ArrayList<>();
    private List<Map.Entry<FId, GuideTheme>> shown = List.of();
    private int total;

    private record Chip(String tag, int x, int y, int w) { }

    public GuideThemePicker(Screen parent) {
        super(Component.translatableWithFallback("screen.fundamentum.guide_themes", "Guide themes"));
        this.parent = parent;
    }

    private static FId current() {
        FId id = GuidePrefs.theme();
        return id == null ? GuideTheme.DEFAULT_ID : id;
    }

    @Override
    protected void init() {
        Guides.Library lib = Guides.library();
        this.theme = GuideTheme.of(lib.themes(), GuidePrefs.theme());
        this.pw = Math.min(608, this.width - 16);
        this.ph = Math.min(336, this.height - 16);
        this.px = (this.width - this.pw) / 2;
        this.py = (this.height - this.ph) / 2;

        // tag chips under the search line, wrapping
        Set<String> tags = new TreeSet<>();
        for (GuideTheme t : lib.themes().values()) tags.addAll(t.tags());
        chosenTags.retainAll(tags);
        this.chips.clear();
        int x = this.px + 8;
        int y = this.py + 40;
        for (String tag : tags) {
            int w = this.font.width(tag) + 8;
            if (x + w > this.px + this.pw - 8) {
                x = this.px + 8;
                y += 13;
            }
            this.chips.add(new Chip(tag, x, y, w));
            x += w + 3;
        }

        // the matching themes, by name
        String q = this.query.trim().toLowerCase(Locale.ROOT);
        List<Map.Entry<FId, GuideTheme>> list = new ArrayList<>();
        for (Map.Entry<FId, GuideTheme> e : lib.themes().entrySet()) {
            GuideTheme t = e.getValue();
            if (!q.isEmpty() && !t.label(e.getKey()).toLowerCase(Locale.ROOT).contains(q)
                && t.tags().stream().noneMatch(tag -> tag.contains(q))) continue;
            if (!chosenTags.isEmpty()) {
                boolean ok = matchAll ? t.tags().containsAll(chosenTags) : chosenTags.stream().anyMatch(t.tags()::contains);
                if (!ok) continue;
            }
            list.add(e);
        }
        list.sort((a, b) -> a.getValue().label(a.getKey()).compareToIgnoreCase(b.getValue().label(b.getKey())));
        this.shown = list;
        this.total = lib.themes().size();

        this.gx = this.px + 8;
        this.gy = y + 18;
        this.gw = this.pw - 16 - 7;
        this.gh = this.py + this.ph - 30 - this.gy;
        this.cellW = (this.gw - (COLS - 1) * GAP) / COLS;
        this.cellH = this.cellW * 9 / 16;
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));

        addRenderableWidget(new GuideSurface(this.width, this.height, new Frame()));

        GuideTheme.Colours c = this.theme.colours();
        this.search = new EditBox(this.font, this.px + 16, this.py + 25, 150, 9,
            Component.translatableWithFallback("guide.fundamentum.search", "Search"));
        this.search.setBordered(false);
        this.search.setTextColor(c.text());
        this.search.setHint(Component.translatableWithFallback("guide.fundamentum.theme_search", "search themes")
            .withStyle(Style.EMPTY.withColor(c.muted() & 0xFFFFFF)));
        this.search.setValue(this.query);
        this.search.setResponder(text -> {
            if (text.equals(this.query)) return;
            this.query = text;
            lastSearch = text;
            this.scroll = 0;
            rebuild();
            this.setFocused(this.search);
            this.search.setFocused(true);
        });
        addRenderableWidget(this.search);
    }

    private int rowsOf() {
        return (this.shown.size() + COLS - 1) / COLS;
    }

    private int maxScroll() {
        return Math.max(0, rowsOf() * (this.cellH + GAP) - GAP - this.gh);
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private final class Frame implements GuidePaint.Layer {
        private int doneX, doneW, modeX, modeW, ownY, ownW, folderX, folderW;

        @Override
        public void paint(GuidePaint p, int mx, int my) {
            GuideTheme t = theme;
            GuideTheme.Colours c = t.colours();
            boolean sh = t.shadow();
            int accent = t.accent(Guides.accentFor("fundamentum"));
            GuideSkin.rect(p, 0, 0, width, height, t.backdrop());
            GuideSkin.background(p, t, px, py, pw, ph, System.currentTimeMillis());
            if (GuideSkin.boxed(t)) GuideSkin.box(p, t, gx - 3, gy - 3, pw - 10, gh + 6);
            else {
                GuideSkin.rect(p, px + 6, py + 20, pw - 12, 1, c.rule());
                GuideSkin.rect(p, px + 6, py + ph - 26, pw - 12, 1, c.rule());
            }

            // header
            p.text(Component.literal(title.getString()).withStyle(ChatFormatting.BOLD), px + 8, py + 7, c.title(), sh);
            String done = Component.translatableWithFallback("gui.done", "Done").getString();
            this.doneW = font.width(done);
            this.doneX = px + pw - 8 - this.doneW;
            word(p, done, this.doneX, py + 7, mx, my, c.soft(), accent, sh);
            String count = Component.translatableWithFallback("guide.fundamentum.theme_count", "%s of %s", shown.size(), total).getString();
            p.text(Component.literal(count), this.doneX - 14 - font.width(count), py + 7, c.muted(), sh);

            // search and the all/any switch
            p.text(Component.literal("/"), px + 8, py + 25, c.muted(), sh);
            String mode = (matchAll
                ? Component.translatableWithFallback("guide.fundamentum.match_all", "Match all tags")
                : Component.translatableWithFallback("guide.fundamentum.match_any", "Match any tag")).getString();
            this.modeW = font.width(mode);
            this.modeX = px + pw - 8 - this.modeW;
            word(p, mode, this.modeX, py + 25, mx, my, c.soft(), accent, sh);

            for (Chip chip : chips) {
                boolean on = chosenTags.contains(chip.tag());
                boolean over = in(mx, my, chip.x(), chip.y(), chip.w(), 11);
                GuideSkin.rect(p, chip.x(), chip.y(), chip.w(), 11, on ? GuideSkin.alpha(accent, Math.min(255, t.selection() + 60))
                    : GuideSkin.alpha(c.muted(), over ? 0x40 : 0x20));
                if (on) GuideSkin.outline(p, chip.x(), chip.y(), chip.w(), 11, accent);
                p.text(Component.literal(chip.tag()), chip.x() + 4, chip.y() + 2, on ? c.strong() : c.soft(), sh);
            }

            // the previews
            p.scissor(gx, gy, gx + gw, gy + gh);
            FId cur = current();
            Component tip = null;
            long now = System.currentTimeMillis();
            for (int i = 0; i < shown.size(); i++) {
                int x = gx + (i % COLS) * (cellW + GAP);
                int y = gy + (i / COLS) * (cellH + GAP) - scroll;
                if (y + cellH <= gy || y >= gy + gh) continue;
                FId id = shown.get(i).getKey();
                GuideTheme th = shown.get(i).getValue();
                preview(p, th, id, x, y, now);
                boolean over = in(mx, my, x, y, cellW, cellH) && my >= gy && my < gy + gh;
                if (id.equals(cur)) {
                    GuideSkin.outline(p, x - 2, y - 2, cellW + 4, cellH + 4, c.strong());
                    GuideSkin.outline(p, x - 1, y - 1, cellW + 2, cellH + 2, accent);
                } else if (over) {
                    GuideSkin.outline(p, x - 1, y - 1, cellW + 2, cellH + 2, c.muted());
                }
                if (over && !th.tags().isEmpty()) tip = Component.literal(String.join(", ", th.tags()));
            }
            p.endScissor();
            int max = maxScroll();
            if (max > 0) {
                int full = rowsOf() * (cellH + GAP);
                GuideSkin.scrollbar(p, t, gx + gw + 1, gy, gh, Math.max(10, gh * gh / Math.max(1, full)), scroll / (double) max);
            }
            if (shown.isEmpty()) {
                String none = Component.translatableWithFallback("guide.fundamentum.no_themes", "No theme matches.").getString();
                p.text(Component.literal(none), gx + (gw - font.width(none)) / 2, gy + gh / 2 - 4, c.muted(), sh);
            }

            // footer: the books switch, the folder, more later
            int fy = py + ph - 18;
            this.ownY = fy;
            boolean own = GuidePrefs.ownThemes();
            String ownLabel = Component.translatableWithFallback("guide.fundamentum.own_themes", "Books may use their own theme").getString();
            this.ownW = 12 + font.width(ownLabel);
            GuideSkin.outline(p, px + 8, fy - 1, 9, 9, c.soft());
            if (own) GuideSkin.rect(p, px + 10, fy + 1, 5, 5, accent);
            boolean overOwn = in(mx, my, px + 6, fy - 2, this.ownW + 4, 12);
            p.text(Component.literal(ownLabel), px + 20, fy, overOwn ? accent : c.text(), sh);

            String folder = Component.translatableWithFallback("guide.fundamentum.theme_folder", "More themes: config/fundamentum/guide_themes").getString();
            this.folderX = px + 20 + font.width(ownLabel) + 18;
            int folderRoom = px + pw - 8 - this.folderX;
            if (font.width(folder) > folderRoom) folder = font.plainSubstrByWidth(folder, Math.max(0, folderRoom - font.width("..."))) + "...";
            this.folderW = font.width(folder);
            boolean overFolder = in(mx, my, this.folderX - 2, fy - 2, this.folderW + 4, 12);
            p.text(Component.literal(folder), this.folderX, fy, overFolder ? accent : c.muted(), sh);
            String later = Component.translatableWithFallback("guide.fundamentum.get_more", "Get more (coming later)").getString();
            int lx = px + pw - 8 - font.width(later);
            if (lx > this.folderX + this.folderW + 10) p.text(Component.literal(later), lx, fy, GuideSkin.alpha(c.muted(), 0x80), false);

            if (overFolder) {
                tip = Component.translatableWithFallback("guide.fundamentum.theme_help",
                    "A theme is a .json file. Drop one in this folder and reload resources (F3+T). Click for how to install and make themes.");
            }
            if (tip != null) p.tooltip(tip, mx, my);
        }

        /** One theme in small: its panel, its name, a line of text and a chip in the book's colour. */
        private void preview(GuidePaint p, GuideTheme th, FId id, int x, int y, long now) {
            GuideTheme.Colours c = th.colours();
            GuideSkin.rect(p, x, y, cellW, cellH, th.backdrop() | 0xFF000000);
            GuideSkin.background(p, th, x, y, cellW, cellH, now);
            if (GuideSkin.boxed(th)) GuideSkin.box(p, th, x + 4, y + 16, cellW - 8, cellH - 20);
            else GuideSkin.rect(p, x + 4, y + 15, cellW - 8, 1, c.rule());
            int acc = th.accent(Guides.accentFor("fundamentum"));
            GuideSkin.rect(p, x + 6, y + 7, 4, 4, acc);
            String name = th.label(id);
            if (font.width(name) + name.length() > cellW - 17) {   // bold text is one pixel wider per letter
                while (name.length() > 1 && font.width(name + "..") + name.length() + 2 > cellW - 17) name = name.substring(0, name.length() - 1);
                name = name.stripTrailing() + "..";
            }
            p.text(Component.literal(name).withStyle(ChatFormatting.BOLD), x + 13, y + 5, c.title(), th.shadow());
            GuideTheme.Page pal = th.page().withAccent(acc);
            int ty = y + 20;
            if (ty + 9 < y + cellH - 4) p.text(Component.literal("Aa").withStyle(ChatFormatting.BOLD), x + 8, ty, pal.heading(), pal.shadow());
            if (ty + 9 < y + cellH - 4) p.text(Component.literal("text"), x + 24, ty, pal.text(), pal.shadow());
            if (ty + 21 < y + cellH - 4) p.text(Component.literal("link").withStyle(Style.EMPTY.withUnderlined(true)), x + 8, ty + 11, pal.link(), pal.shadow());
            if (ty + 21 < y + cellH - 4) p.text(Component.literal("more"), x + 32, ty + 11, c.muted(), th.shadow());
        }

        private void word(GuidePaint p, String s, int x, int y, int mx, int my, int colour, int accent, boolean sh) {
            boolean over = in(mx, my, x - 2, y - 2, font.width(s) + 4, 12);
            p.text(Component.literal(s).withStyle(over ? Style.EMPTY.withUnderlined(true) : Style.EMPTY), x, y, over ? accent : colour, sh);
        }

        @Override
        public boolean click(double mx, double my) {
            if (in(mx, my, this.doneX - 2, py + 5, this.doneW + 4, 12)) {
                onClose();
                return true;
            }
            if (in(mx, my, this.modeX - 2, py + 23, this.modeW + 4, 12)) {
                matchAll = !matchAll;
                scroll = 0;
                rebuild();
                return true;
            }
            for (Chip chip : chips) {
                if (in(mx, my, chip.x(), chip.y(), chip.w(), 11)) {
                    if (!chosenTags.remove(chip.tag())) chosenTags.add(chip.tag());
                    scroll = 0;
                    rebuild();
                    return true;
                }
            }
            if (in(mx, my, this.folderX - 2, this.ownY - 2, this.folderW + 4, 12)) {
                if (minecraft != null) minecraft.gui.setScreen(GuideScreen.at(FId.of("fundamentum", "themes/install")));
                return true;
            }
            if (in(mx, my, px + 6, this.ownY - 2, this.ownW + 4, 12)) {
                GuidePrefs.setOwnThemes(!GuidePrefs.ownThemes());
                return true;
            }
            if (in(mx, my, gx, gy, gw, gh)) {
                int col = (int) ((mx - gx) / (cellW + GAP));
                int row = (int) ((my - gy + scroll) / (cellH + GAP));
                int cx = gx + col * (cellW + GAP);
                int cy = gy + row * (cellH + GAP) - scroll;
                int i = row * COLS + col;
                if (col < COLS && i >= 0 && i < shown.size() && in(mx, my, cx, cy, cellW, cellH)) {
                    GuidePrefs.setTheme(shown.get(i).getKey());
                    rebuild();
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean over(double mx, double my) {
            return in(mx, my, px, py, pw, ph) && !in(mx, my, px + 16, py + 23, 150, 12);
        }

        @Override
        public boolean scroll(double mx, double my, double amount) {
            if (!in(mx, my, gx, gy, gw + 8, gh)) return false;
            scroll = (int) Math.max(0, Math.min(maxScroll(), scroll - amount * (cellH + GAP) / 2));
            return true;
        }
    }
}
