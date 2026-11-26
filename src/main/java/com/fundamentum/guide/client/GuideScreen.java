package com.fundamentum.guide.client;

import com.fundamentum.guide.GuidePage;
import com.fundamentum.guide.GuideTheme;
import com.fundamentum.guide.Guides;
import com.fundamentum.port.FId;
import com.fundamentum.port.FVanilla;
import com.fundamentum.unlock.Unlocks;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The guide, one full-screen panel in the player's {@link GuideTheme}: a header with counts, a search line, every
 * mod's book as a folding tree on the left (mods, chapters, pages, drawn with console-style branches), the open page on
 * the right, and a footer with the keys and the page arrows. The keyboard moves through the tree. It reopens where it
 * was last closed. Client only; the same file on every version except 1.20.1 (the canvas, surface and painter are per
 * version).
 */
public final class GuideScreen extends Screen {
    /** The narrowest the list and the page may be dragged; a list dragged well under its minimum folds away. */
    private static final int NAV_MIN = 90, PAGE_MIN = 180;
    private static final int ROW = 14;
    private static final int STEP = 12;
    private static final int RECENT_SHOWN = 4;

    /** What the right side shows: the front page (no book), a book's front, or a page. */
    private record View(String book, FId page) {
        static final View HOME = new View(null, null);
    }

    private enum Kind { MOD, CHAPTER, PAGE, SECTION }

    /**
     * A tree row. A mod row opens its book and folds; a chapter or section row folds; a page row opens its page.
     * {@code tree} holds the branch marks for each level ('+' tee, '\\' elbow, '|' carried line, ' ' none).
     */
    private record Row(Kind kind, String label, View target, String fold, boolean open, int depth, String tree,
                       int count, int accent, String side) {
        Row withTree(String t) {
            return new Row(this.kind, this.label, this.target, this.fold, this.open, this.depth, t, this.count, this.accent, this.side);
        }

        String key() {
            if (this.fold != null) return this.fold;
            return this.target.page() != null ? this.target.page().toString() : "#front:" + this.target.book();
        }
    }

    private static View last = View.HOME;
    private static String lastSearch = "";

    private final Deque<View> history = new ArrayDeque<>();
    private View view;
    /** The view the tree was last built for, so the open page's book and chapter unfold once on arrival. */
    private View shownFor;
    private String query;
    private int listScroll;
    /** The keyboard's row, by key; null follows the open page. */
    private String cursorKey;
    private int cursor = -1;
    private EditBox search;
    private GuideCanvas canvas;
    private GuideSurface surface;
    /** The list's width as the player left it (0 = default), and whether it is folded away. */
    private int navWant = GuidePrefs.navWidth();
    private boolean folded = GuidePrefs.navFolded();
    /** Set while the divider between the list and the page is being dragged; the grab point's distance from it. */
    private boolean dragging;
    private double grab;

    // layout, set in init()
    private GuideTheme theme = GuideTheme.DEFAULT;
    private int accent = 0xFFFFFFFF;
    private int px, py, pw, ph;
    private int tx, ty, tw, th;
    private int cx, cw;
    private List<Row> rows = List.of();
    private List<GuidePage> chapterPages = List.of();
    private int pageIndex = -1;
    private int mods, pages, hits;

    public GuideScreen() {
        this(last);
    }

    private GuideScreen(View view) {
        super(Component.translatableWithFallback("screen.fundamentum.guide", "Guide"));
        this.view = view;
        this.query = lastSearch;
    }

    /**
     * A screen open at {@code target}: a page id opens that page; any other id opens the front of the book in its
     * namespace (by convention {@code <ns>:book}); null opens where the guide was last left.
     */
    public static GuideScreen at(FId target) {
        if (target == null) return new GuideScreen();
        Guides.Library lib = Guides.library();
        GuidePage page = lib.pages().get(target);
        GuideScreen s;
        if (page != null && Guides.visible(lib, page, unlocked())) s = new GuideScreen(new View(page.chapter().namespace(), page.id()));
        else if (lib.books().containsKey(target.namespace())) s = new GuideScreen(new View(target.namespace(), null));
        else s = new GuideScreen(View.HOME);
        s.query = "";
        return s;
    }

    static Predicate<FId> unlocked() {
        return Unlocks::clientHas;
    }

    @Override
    protected void init() {
        Guides.Library lib = Guides.library();
        Predicate<FId> unlocked = unlocked();
        this.theme = lib.themeFor(this.view.book(), GuidePrefs.theme(), GuidePrefs.ownThemes());
        Guides.Book openBook = this.view.book() == null ? null : lib.books().get(this.view.book());
        this.accent = this.theme.accent(openBook != null ? openBook.accent() : Guides.accentFor("fundamentum"));

        this.pw = Math.min(608, this.width - 16);
        this.ph = Math.min(336, this.height - 16);
        this.px = (this.width - this.pw) / 2;
        this.py = (this.height - this.ph) / 2;
        this.tw = this.folded ? 0 : navWidth(this.navWant);
        this.tx = this.px + 8;
        this.ty = this.py + 44;
        this.th = this.py + this.ph - 18 - this.ty;
        this.cx = this.folded ? this.px + 20 : this.tx + this.tw + 16;
        this.cw = this.px + this.pw - 8 - this.cx;

        GuidePage open = this.view.page() == null ? null : lib.pages().get(this.view.page());
        if (this.view != this.shownFor) {
            if (open != null && Guides.visible(lib, open, unlocked)) {
                GuidePrefs.visit(open.id());
                String key = open.chapter().toString();
                if (!GuidePrefs.isOpen(key, false)) GuidePrefs.toggle(key, false);
            }
            if (this.view.book() != null && !GuidePrefs.isOpen(bookKey(this.view.book()), false)) GuidePrefs.toggle(bookKey(this.view.book()), false);
        }
        boolean arrived = this.view != this.shownFor;
        this.shownFor = this.view;
        this.rows = tree(entries(lib, unlocked));
        placeCursor(arrived);
        pager(lib, unlocked);

        // the panel, tree, header and footer: drawn first, under the widgets below
        this.surface = addRenderableWidget(new GuideSurface(this.width, this.height, new Frame()));

        GuideTheme.Colours c = this.theme.colours();
        this.search = this.folded ? null : new EditBox(this.font, this.px + 16, this.py + 27, this.tw - 52, 9,
            Component.translatableWithFallback("guide.fundamentum.search", "Search"));
        if (this.search != null) {
            this.search.setBordered(false);
            this.search.setTextColor(c.text());
            this.search.setHint(Component.translatableWithFallback("guide.fundamentum.search_hint", "search")
                .withStyle(Style.EMPTY.withColor(c.muted() & 0xFFFFFF)));
            this.search.setValue(this.query);
            this.search.setResponder(text -> {
                if (text.equals(this.query)) return;
                this.query = text;
                this.listScroll = 0;
                this.cursorKey = null;
                rebuild();
                this.setFocused(this.search);
            });
            addRenderableWidget(this.search);
        }

        this.canvas = new GuideCanvas(this.cx, this.py + 52, this.cw, this.py + this.ph - 20 - (this.py + 52), this.theme, this::link);
        this.canvas.setContent(content(lib, unlocked, this.canvas.contentWidth()));
        addRenderableWidget(this.canvas);
    }

    /** The list's width: the player's choice (or the default for this screen size), kept between the two minimums. */
    private int navWidth(int want) {
        int w = want > 0 ? want : Math.min(196, Math.max(110, this.pw * 36 / 100));
        return Math.max(NAV_MIN, Math.min(w, this.pw - 32 - PAGE_MIN));
    }

    /** Where the divider between the list and the page sits. */
    private int divider() {
        return this.folded ? this.px + 10 : this.tx + this.tw + 6;
    }

    /** Lays the screen out again for a new list width, keeping the page where it was scrolled to. */
    private void relayout() {
        double at = this.canvas == null ? 0 : this.canvas.scrollFraction();
        boolean typing = this.search != null && this.search.isFocused();
        rebuild();
        if (this.canvas != null) this.canvas.setScrollFraction(at);
        if (this.dragging) this.setFocused(this.surface);
        else if (typing && this.search != null) this.setFocused(this.search);
    }

    /** Folds the list away, or brings it back. */
    private void toggleFold() {
        this.folded = !this.folded;
        GuidePrefs.setNav(this.navWant, this.folded);
        relayout();
    }

    private static String bookKey(String ns) {
        return "#book:" + ns;
    }

    private static ItemStack icon(FId id) {
        Item item = id == null ? null : FVanilla.item(id);
        return new ItemStack(item == null || item == Items.AIR ? Items.BOOK : item);
    }

    private ItemStack pageIcon(Guides.Library lib, GuidePage p) {
        if (p.icon() != null) return icon(p.icon());
        Guides.Book b = lib.books().get(p.chapter().namespace());
        if (b != null) for (Guides.Chapter ch : b.chapters()) if (ch.id().equals(p.chapter())) return icon(ch.icon());
        return icon(null);
    }

    /** Puts each row's branch marks on it, the way a console prints a file tree. */
    private static List<Row> tree(List<Row> in) {
        List<Row> out = new ArrayList<>(in.size());
        for (int i = 0; i < in.size(); i++) {
            Row r = in.get(i);
            StringBuilder t = new StringBuilder();
            for (int k = 1; k <= r.depth(); k++) t.append(more(in, i, k) ? (k == r.depth() ? '+' : '|') : (k == r.depth() ? '\\' : ' '));
            out.add(r.withTree(t.toString()));
        }
        return out;
    }

    /** Whether another row at {@code depth} follows row {@code i} before the tree climbs above that depth. */
    private static boolean more(List<Row> rows, int i, int depth) {
        for (int j = i + 1; j < rows.size(); j++) {
            int d = rows.get(j).depth();
            if (d < depth) return false;
            if (d == depth) return true;
        }
        return false;
    }

    private static View pageView(GuidePage p) {
        return new View(p.chapter().namespace(), p.id());
    }

    /** The tree: pinned and recent pages, then every mod's book with its chapters and pages; or the search hits. */
    private List<Row> entries(Guides.Library lib, Predicate<FId> unlocked) {
        List<Row> out = new ArrayList<>();
        List<Guides.Book> books = Guides.books(lib, unlocked);
        this.mods = books.size();
        this.pages = 0;
        for (Guides.Book b : books) for (Guides.Chapter ch : Guides.chapters(lib, b.namespace(), unlocked)) this.pages += Guides.pages(lib, ch.id(), unlocked).size();
        if (!this.query.isBlank()) {
            List<GuidePage> found = Guides.search(lib, this.query, unlocked);
            this.hits = found.size();
            for (GuidePage p : found) {
                Guides.Book b = lib.books().get(p.chapter().namespace());
                out.add(new Row(Kind.PAGE, p.title(), pageView(p), null, true, 0, "", 0, accentOf(b, p.chapter().namespace()), b == null ? "" : b.title()));
            }
            return out;
        }
        section(out, lib, unlocked, "#pinned", Component.translatableWithFallback("guide.fundamentum.pinned", "Pinned").getString(),
            shown(lib, unlocked, GuidePrefs.pinned()), Integer.MAX_VALUE);
        List<GuidePage> recent = shown(lib, unlocked, GuidePrefs.recent());
        recent.removeIf(p -> p.id().equals(this.view.page()));
        section(out, lib, unlocked, "#recent", Component.translatableWithFallback("guide.fundamentum.recent", "Recent").getString(), recent, RECENT_SHOWN);
        for (Guides.Book b : books) {
            List<Guides.Chapter> chapters = Guides.chapters(lib, b.namespace(), unlocked);
            int n = 0;
            for (Guides.Chapter ch : chapters) n += Guides.pages(lib, ch.id(), unlocked).size();
            boolean open = GuidePrefs.isOpen(bookKey(b.namespace()), false);
            int acc = accentOf(b, b.namespace());
            out.add(new Row(Kind.MOD, b.title(), new View(b.namespace(), null), bookKey(b.namespace()), open, 0, "", n, acc, ""));
            if (!open) continue;
            for (Guides.Chapter ch : chapters) {
                List<GuidePage> ps = Guides.pages(lib, ch.id(), unlocked);
                boolean chOpen = GuidePrefs.isOpen(ch.id().toString(), false);
                out.add(new Row(Kind.CHAPTER, ch.title(), null, ch.id().toString(), chOpen, 1, "", ps.size(), acc, ""));
                if (!chOpen) continue;
                for (GuidePage p : ps) out.add(new Row(Kind.PAGE, p.title(), pageView(p), null, true, 2, "", 0, acc, ""));
            }
        }
        return out;
    }

    private int accentOf(Guides.Book b, String ns) {
        return this.theme.accent(b != null ? b.accent() : Guides.accentFor(ns));
    }

    /** A folding list of pages from any book (pinned, recent); open unless the player closed it. Nothing when empty. */
    private void section(List<Row> out, Guides.Library lib, Predicate<FId> unlocked, String key, String title, List<GuidePage> list, int max) {
        if (list.isEmpty()) return;
        boolean open = GuidePrefs.isOpen(key, true);
        out.add(new Row(Kind.SECTION, title, null, key, open, 0, "", list.size(), this.theme.colours().muted(), ""));
        if (!open) return;
        for (GuidePage p : list.subList(0, Math.min(max, list.size()))) {
            Guides.Book b = lib.books().get(p.chapter().namespace());
            out.add(new Row(Kind.PAGE, p.title(), pageView(p), null, true, 1, "", 0, accentOf(b, p.chapter().namespace()), ""));
        }
    }

    /** The pages of {@code ids} that exist and the player can see. */
    private static List<GuidePage> shown(Guides.Library lib, Predicate<FId> unlocked, List<FId> ids) {
        List<GuidePage> out = new ArrayList<>();
        for (FId id : ids) {
            GuidePage p = lib.pages().get(id);
            if (p != null && Guides.visible(lib, p, unlocked)) out.add(p);
        }
        return out;
    }

    /** Finds the keyboard's row again after a rebuild; on arrival at a page it moves to that page's row. */
    private void placeCursor(boolean arrived) {
        this.cursor = -1;
        if (!arrived && this.cursorKey != null) {
            for (int i = 0; i < this.rows.size(); i++) if (this.rows.get(i).key().equals(this.cursorKey)) this.cursor = i;
        }
        if (this.cursor < 0) {
            // the open page's own row, past the pinned and recent copies of it
            for (int i = this.rows.size() - 1; i >= 0; i--) {
                Row r = this.rows.get(i);
                if (r.target() != null && r.target().equals(this.view)) {
                    this.cursor = i;
                    break;
                }
            }
        }
        this.cursorKey = this.cursor >= 0 ? this.rows.get(this.cursor).key() : null;
        if (arrived) reveal();
        this.listScroll = Math.max(0, Math.min(this.listScroll, maxListScroll()));
    }

    /** Scrolls the tree so the keyboard's row shows. */
    private void reveal() {
        if (this.cursor < 0) return;
        int top = this.cursor * ROW;
        if (top < this.listScroll) this.listScroll = top;
        if (top + ROW > this.listScroll + this.th) this.listScroll = top + ROW - this.th;
        this.listScroll = Math.max(0, Math.min(this.listScroll, maxListScroll()));
    }

    private int maxListScroll() {
        return Math.max(0, this.rows.size() * ROW - this.th);
    }

    private void pager(Guides.Library lib, Predicate<FId> unlocked) {
        this.chapterPages = List.of();
        this.pageIndex = -1;
        GuidePage page = this.view.page() == null ? null : lib.pages().get(this.view.page());
        if (page == null || !Guides.visible(lib, page, unlocked)) return;
        this.chapterPages = Guides.pages(lib, page.chapter(), unlocked);
        for (int i = 0; i < this.chapterPages.size(); i++) if (this.chapterPages.get(i).id().equals(page.id())) this.pageIndex = i;
    }

    private GuideTheme.Page palette() {
        return this.theme.page().withAccent(this.accent);
    }

    private GuideLayout.Result content(Guides.Library lib, Predicate<FId> unlocked, int width) {
        GuideTheme.Page pal = palette();
        if (this.view.page() != null) {
            GuidePage page = lib.pages().get(this.view.page());
            if (page != null && Guides.visible(lib, page, unlocked)) return GuideLayout.body(page, this.font, width, pal);
            return GuideLayout.lines(List.of(Component.translatableWithFallback("guide.fundamentum.locked", "This page is not available yet.")), this.font, width, pal);
        }
        List<Component> lines = new ArrayList<>();
        if (this.view.book() != null && lib.books().containsKey(this.view.book())) {
            for (Guides.Chapter ch : Guides.chapters(lib, this.view.book(), unlocked)) {
                lines.add(Component.literal(ch.title()).withStyle(ChatFormatting.BOLD));
                for (GuidePage p : Guides.pages(lib, ch.id(), unlocked)) lines.add(Component.literal("  " + p.title()));
                lines.add(Component.empty());
            }
            return GuideLayout.lines(lines, this.font, width, pal);
        }
        if (Guides.books(lib, unlocked).isEmpty()) {
            lines.add(Component.translatableWithFallback("guide.fundamentum.empty", "No guide pages are loaded."));
        } else {
            lines.add(Component.translatableWithFallback("guide.fundamentum.pick", "Pick a mod on the left, or type to search."));
        }
        return GuideLayout.lines(lines, this.font, width, pal);
    }

    /** The page area's title, icon and the line under it. */
    private String headTitle(Guides.Library lib) {
        GuidePage page = this.view.page() == null ? null : lib.pages().get(this.view.page());
        if (page != null) return page.title();
        Guides.Book b = this.view.book() == null ? null : lib.books().get(this.view.book());
        return b != null ? b.title() : this.title.getString();
    }

    private ItemStack headIcon(Guides.Library lib) {
        GuidePage page = this.view.page() == null ? null : lib.pages().get(this.view.page());
        if (page != null) return pageIcon(lib, page);
        Guides.Book b = this.view.book() == null ? null : lib.books().get(this.view.book());
        return b != null ? icon(b.icon()) : new ItemStack(Items.BOOKSHELF);
    }

    private String crumbs(Guides.Library lib) {
        GuidePage page = this.view.page() == null ? null : lib.pages().get(this.view.page());
        if (page == null) return "";
        Guides.Book b = lib.books().get(page.chapter().namespace());
        Guides.Chapter ch = lib.chapter(page.chapter());
        String chapter = ch == null ? "" : ch.title();
        return b == null ? chapter : b.title() + "  >  " + chapter;
    }

    private String fit(String s, int width) {
        if (this.font.width(s) <= width) return s;
        return this.font.plainSubstrByWidth(s, Math.max(0, width - this.font.width(".."))) + "..";
    }

    private void link(FId target) {
        Guides.Library lib = Guides.library();
        GuidePage page = lib.pages().get(target);
        if (page != null && Guides.visible(lib, page, unlocked())) go(new View(page.chapter().namespace(), page.id()));
        else if (lib.books().containsKey(target.namespace()) && page == null) go(new View(target.namespace(), null));
    }

    private void go(View target) {
        if (target.equals(this.view) && this.query.isBlank()) return;
        this.history.push(this.view);
        this.view = target;
        this.cursorKey = null;
        rebuild();
    }

    private void back() {
        if (this.history.isEmpty()) return;
        this.view = this.history.pop();
        this.cursorKey = null;
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private void step(int page) {
        if (this.pageIndex < 0) return;
        int to = this.pageIndex + page;
        if (to < 0 || to >= this.chapterPages.size()) return;
        go(pageView(this.chapterPages.get(to)));
    }

    private void togglePin() {
        if (this.view.page() == null) return;
        GuidePrefs.togglePin(this.view.page());
        rebuild();
    }

    private void themes() {
        if (this.minecraft != null) this.minecraft.setScreen(new GuideThemePicker(this));
    }

    /** Clicking or pressing Right on a row: a mod opens its book (and unfolds); a fold toggles; a page opens. */
    private void activate(int i, boolean fromKey) {
        if (i < 0 || i >= this.rows.size()) return;
        Row r = this.rows.get(i);
        this.cursorKey = r.key();
        if (r.kind() == Kind.MOD && !Objects.equals(this.view.book(), r.target().book())) {
            if (!r.open()) GuidePrefs.toggle(r.fold(), false);
            go(r.target());
            this.cursorKey = r.key();
            rebuild();
            return;
        }
        if (r.fold() != null) {
            if (fromKey && r.open()) {
                // already open: step into it
                if (i + 1 < this.rows.size() && this.rows.get(i + 1).depth() > r.depth()) {
                    this.cursorKey = this.rows.get(i + 1).key();
                    this.cursor = i + 1;
                    reveal();
                }
                return;
            }
            GuidePrefs.toggle(r.fold(), r.kind() == Kind.SECTION);
            rebuild();
            return;
        }
        go(r.target());
    }

    /** Left: fold the row, or move to the row it sits under. */
    private void fold() {
        if (this.cursor < 0 || this.cursor >= this.rows.size()) return;
        Row r = this.rows.get(this.cursor);
        if (r.fold() != null && r.open()) {
            GuidePrefs.toggle(r.fold(), r.kind() == Kind.SECTION);
            this.cursorKey = r.key();
            rebuild();
            return;
        }
        for (int j = this.cursor - 1; j >= 0; j--) {
            if (this.rows.get(j).depth() < r.depth()) {
                this.cursor = j;
                this.cursorKey = this.rows.get(j).key();
                reveal();
                return;
            }
        }
    }

    private void move(int by) {
        if (this.rows.isEmpty()) return;
        this.cursor = this.cursor < 0 ? 0 : Math.max(0, Math.min(this.rows.size() - 1, this.cursor + by));
        this.cursorKey = this.rows.get(this.cursor).key();
        reveal();
    }

    /** The guide's keys; letters go to the search box while it is being typed in. */
    private boolean key(int code) {
        boolean typing = this.search != null && this.search.isFocused();
        switch (code) {
            case InputConstants.KEY_UP -> move(-1);
            case InputConstants.KEY_DOWN -> move(1);
            case InputConstants.KEY_PAGEUP -> move(-Math.max(1, this.th / ROW - 1));
            case InputConstants.KEY_PAGEDOWN -> move(Math.max(1, this.th / ROW - 1));
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                if (typing) this.setFocused(null);
                activate(this.cursor, false);
            }
            default -> {
                if (typing) return false;
                switch (code) {
                    case InputConstants.KEY_RIGHT -> activate(this.cursor, true);
                    case InputConstants.KEY_LEFT -> fold();
                    case InputConstants.KEY_BACKSPACE -> back();
                    case InputConstants.KEY_P -> togglePin();
                    case InputConstants.KEY_T -> themes();
                    case InputConstants.KEY_COMMA -> step(-1);
                    case InputConstants.KEY_PERIOD -> step(1);
                    case InputConstants.KEY_LBRACKET -> toggleFold();
                    case InputConstants.KEY_SLASH -> {
                        if (this.folded) toggleFold();
                        this.setFocused(this.search);
                        this.search.setFocused(true);
                    }
                    default -> {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (key(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        last = this.view;
        lastSearch = this.query;
        super.removed();
    }

    /** 1.20.1 screens draw no backdrop by themselves (later versions do), so the guide dims the world here. */
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Everything on the screen that is not a widget, and the clicks on it. */
    private final class Frame implements GuidePaint.Layer {
        // clickable words, placed as they are drawn
        private int themeX, themeW, pinX, pinW, backX, backW, prevX, prevW, nextX, nextW;

        @Override
        public void paint(GuidePaint p, int mx, int my) {
            GuideTheme t = theme;
            GuideTheme.Colours c = t.colours();
            boolean sh = t.shadow();
            Guides.Library lib = Guides.library();
            GuideSkin.rect(p, 0, 0, width, height, t.backdrop());
            GuideSkin.background(p, t, px, py, pw, ph, System.currentTimeMillis());
            int foot = py + ph - 14;

            // the areas behind the text: boxes on bevel and textured themes, hairlines on flat ones
            if (GuideSkin.boxed(t)) {
                GuideSkin.box(p, t, px + 4, py + 4, pw - 8, 18);
                if (!folded) GuideSkin.box(p, t, px + 4, py + 24, tw + 8, foot - 2 - (py + 24));
                GuideSkin.box(p, t, cx - 4, py + 24, px + pw - 4 - (cx - 4), foot - 2 - (py + 24));
                GuideSkin.box(p, t, px + 4, foot, pw - 8, 12);
            } else {
                GuideSkin.rect(p, px + 6, py + 20, pw - 12, 1, c.rule());
                GuideSkin.rect(p, divider(), py + 24, 1, foot - 4 - (py + 24), c.rule());
                GuideSkin.rect(p, px + 6, foot, pw - 12, 1, c.rule());
            }

            // header: title, counts, the theme button
            p.text(Component.literal(title.getString()).withStyle(ChatFormatting.BOLD), px + 8, py + 8, c.title(), sh);
            String themeLabel = Component.translatableWithFallback("guide.fundamentum.theme", "Theme").getString();
            this.themeW = font.width(themeLabel);
            this.themeX = px + pw - 8 - this.themeW;
            boolean overTheme = in(mx, my, this.themeX - 2, py + 6, this.themeW + 4, 12);
            p.text(Component.literal(themeLabel).withStyle(overTheme ? Style.EMPTY.withUnderlined(true) : Style.EMPTY), this.themeX, py + 8, overTheme ? accent : c.soft(), sh);
            String stats = Component.translatableWithFallback("guide.fundamentum.stats", "%s mods   %s pages", mods, pages).getString();
            p.text(Component.literal(stats), this.themeX - 14 - font.width(stats), py + 8, c.muted(), sh);

            if (!folded) paintList(p, mx, my, t, c, sh);
            paintDivider(p, mx, my, foot, c);

            // the page head: icon, title, where it sits, the pin button
            p.item(headIcon(lib), cx, py + 27);
            p.text(Component.literal(fit(headTitle(lib), cw - 70)).withStyle(ChatFormatting.BOLD), cx + 22, py + 28, palette().title(), sh);
            String crumbs = crumbs(lib);
            if (!crumbs.isEmpty()) p.text(Component.literal(fit(crumbs, cw - 26)), cx + 22, py + 39, c.muted(), sh);
            this.pinW = 0;
            if (view.page() != null) {
                String pin = (GuidePrefs.isPinned(view.page())
                    ? Component.translatableWithFallback("guide.fundamentum.unpin", "Unpin")
                    : Component.translatableWithFallback("guide.fundamentum.pin", "Pin")).getString();
                this.pinW = font.width(pin);
                this.pinX = px + pw - 10 - this.pinW;
                boolean overPin = in(mx, my, this.pinX - 2, py + 26, this.pinW + 4, 12);
                p.text(Component.literal(pin).withStyle(overPin ? Style.EMPTY.withUnderlined(true) : Style.EMPTY), this.pinX, py + 28, overPin ? accent : c.soft(), sh);
            }

            // footer: the keys, then back and the page arrows
            int fy = foot + 3;
            int x = px + pw - 8;
            this.nextW = this.prevW = this.backW = 0;
            if (pageIndex >= 0 && chapterPages.size() > 1) {
                String next = Component.translatableWithFallback("guide.fundamentum.next", "next >").getString();
                String prev = Component.translatableWithFallback("guide.fundamentum.prev", "< prev").getString();
                String count = (pageIndex + 1) + "/" + chapterPages.size();
                if (pageIndex < chapterPages.size() - 1) {
                    this.nextW = font.width(next);
                    this.nextX = x - this.nextW;
                    word(p, next, this.nextX, fy, mx, my, c.nav(), sh);
                }
                x -= font.width(next) + 8;
                p.text(Component.literal(count), x - font.width(count), fy, c.muted(), sh);
                x -= font.width(count) + 8;
                if (pageIndex > 0) {
                    this.prevW = font.width(prev);
                    this.prevX = x - this.prevW;
                    word(p, prev, this.prevX, fy, mx, my, c.nav(), sh);
                }
                x -= font.width(prev) + 12;
            }
            if (!history.isEmpty()) {
                String b = Component.translatableWithFallback("gui.back", "Back").getString();
                this.backW = font.width(b);
                this.backX = x - this.backW;
                word(p, b, this.backX, fy, mx, my, c.nav(), sh);
                x -= this.backW + 12;
            }
            String keys = Component.translatableWithFallback("guide.fundamentum.keys", "Arrows move   Right open   Left fold   [ list   P pin   T theme").getString();
            p.text(Component.literal(fit(keys, x - (px + 8))), px + 8, fy, c.muted(), sh);
        }

        /** The search line and the tree. */
        private void paintList(GuidePaint p, int mx, int my, GuideTheme t, GuideTheme.Colours c, boolean sh) {
            p.text(Component.literal("/"), px + 8, py + 27, c.muted(), sh);
            if (!query.isBlank()) {
                String n = Component.translatableWithFallback("guide.fundamentum.hits", "%s hits", hits).getString();
                p.text(Component.literal(n), tx + tw - font.width(n), py + 27, c.muted(), sh);
            }
            GuideSkin.rect(p, tx, py + 38, tw, 1, c.rule());

            // the tree
            int max = maxListScroll();
            int right = tx + tw - (max > 0 ? 7 : 0);
            p.scissor(tx - 2, ty, tx + tw, ty + th);
            int y = ty - listScroll;
            for (int i = 0; i < rows.size(); i++, y += ROW) {
                if (y + ROW <= ty || y >= ty + th) continue;
                Row r = rows.get(i);
                int x = tx + r.depth() * STEP;
                branches(p, r, y, c.rule());
                boolean sel = i == cursor;
                boolean over = in(mx, my, tx, y, right - tx, ROW) && my >= ty && my < ty + th;
                if (sel) {
                    GuideSkin.rect(p, x - 2, y, right - x + 2, ROW, GuideSkin.alpha(r.accent(), t.selection()));
                    GuideSkin.rect(p, x - 2, y, 1, ROW, r.accent());
                } else if (over) {
                    GuideSkin.rect(p, x - 2, y, right - x + 2, ROW, GuideSkin.alpha(c.muted(), 0x28));
                }
                String side = r.kind() == Kind.PAGE ? r.side() : String.valueOf(r.count());
                int sideW = side.isEmpty() ? 0 : font.width(side) + 6;
                int lx = x + 2;
                int colour;
                switch (r.kind()) {
                    case MOD -> {
                        GuideSkin.rect(p, x, y + 5, 4, 4, r.accent());
                        lx = x + 9;
                        colour = r.open() ? c.text() : c.mod();
                    }
                    case CHAPTER -> colour = r.open() ? c.text() : c.soft();
                    case SECTION -> colour = c.muted();
                    default -> colour = c.soft();
                }
                if (sel) colour = c.strong();
                Component label = Component.literal(fit(r.label(), right - lx - sideW - 2));
                if (sel || r.kind() == Kind.MOD) label = label.copy().withStyle(ChatFormatting.BOLD);
                p.text(label, lx, y + 3, colour, sh);
                if (!side.isEmpty()) {
                    String s = fit(side, Math.max(20, (right - lx) / 2));
                    p.text(Component.literal(s), right - 2 - font.width(s), y + 3, c.muted(), sh);
                }
            }
            p.endScissor();
            if (max > 0) {
                int thumb = Math.max(10, th * th / Math.max(1, rows.size() * ROW));
                GuideSkin.scrollbar(p, t, tx + tw - 6, ty, th, thumb, listScroll / (double) max);
            }
        }

        /** The divider's grab strip, the line it lights while hovered or dragged, and the fold tab in its middle. */
        private void paintDivider(GuidePaint p, int mx, int my, int foot, GuideTheme.Colours c) {
            int x = divider();
            int top = py + 24, bottom = foot - 2;
            int mid = tabMid();
            boolean overTab = overTab(mx, my);
            boolean overBar = !overTab && overDivider(mx, my);
            if (overBar || dragging) {
                GuideSkin.rect(p, x, top, 1, bottom - top, accent);
                p.resizeCursor();
            }
            GuideSkin.rect(p, x - 3, mid - 8, 7, 17, GuideSkin.alpha(c.muted(), overTab ? 0x50 : 0x1C));
            int col = overTab ? accent : c.muted();
            for (int i = 0; i < 3; i++) {
                int ax = folded ? x - 1 + i : x + 1 - i;
                GuideSkin.rect(p, ax, mid - 2 + i, 1, 1, col);
                GuideSkin.rect(p, ax, mid + 2 - i, 1, 1, col);
            }
        }

        private int tabMid() {
            return (py + 24 + py + ph - 16) / 2;
        }

        private boolean overTab(double mx, double my) {
            return in(mx, my, divider() - 3, tabMid() - 8, 7, 17);
        }

        private boolean overDivider(double mx, double my) {
            return in(mx, my, divider() - 3, py + 24, 7, ph - 40);
        }

        private void word(GuidePaint p, String s, int x, int y, int mx, int my, int colour, boolean sh) {
            boolean over = in(mx, my, x - 2, y - 2, font.width(s) + 4, 12);
            p.text(Component.literal(s).withStyle(over ? Style.EMPTY.withUnderlined(true) : Style.EMPTY), x, y, over ? accent : colour, sh);
        }

        /** A row's branches: a tee or an elbow on its own level, and the lines carried past it. */
        private void branches(GuidePaint p, Row r, int y, int col) {
            String tree = r.tree();
            int mid = y + ROW / 2;
            for (int k = 1; k <= tree.length(); k++) {
                int x = tx + (k - 1) * STEP + 4;
                switch (tree.charAt(k - 1)) {
                    case '|' -> GuideSkin.rect(p, x, y, 1, ROW, col);
                    case '+' -> {
                        GuideSkin.rect(p, x, y, 1, ROW, col);
                        GuideSkin.rect(p, x + 1, mid, STEP - 7, 1, col);
                    }
                    case '\\' -> {
                        GuideSkin.rect(p, x, y, 1, mid - y + 1, col);
                        GuideSkin.rect(p, x + 1, mid, STEP - 7, 1, col);
                    }
                    default -> { }
                }
            }
        }

        @Override
        public boolean click(double mx, double my) {
            if (overTab(mx, my)) {
                toggleFold();
                return true;
            }
            if (overDivider(mx, my)) {
                dragging = true;
                grab = mx - divider();
                return true;
            }
            if (in(mx, my, this.themeX - 2, py + 6, this.themeW + 4, 12)) {
                themes();
                return true;
            }
            if (this.pinW > 0 && in(mx, my, this.pinX - 2, py + 26, this.pinW + 4, 12)) {
                togglePin();
                return true;
            }
            int fy = py + ph - 11;
            if (this.backW > 0 && in(mx, my, this.backX - 2, fy - 2, this.backW + 4, 12)) {
                back();
                return true;
            }
            if (this.prevW > 0 && in(mx, my, this.prevX - 2, fy - 2, this.prevW + 4, 12)) {
                step(-1);
                return true;
            }
            if (this.nextW > 0 && in(mx, my, this.nextX - 2, fy - 2, this.nextW + 4, 12)) {
                step(1);
                return true;
            }
            if (in(mx, my, tx, ty, tw - 7, th)) {
                int i = (int) ((my - ty + listScroll) / ROW);
                if (i >= 0 && i < rows.size()) {
                    cursor = i;
                    activate(i, false);
                }
                return true;
            }
            return false;
        }

        @Override
        public boolean over(double mx, double my) {
            return in(mx, my, tx, ty, tw, th) || overDivider(mx, my) || in(mx, my, this.themeX - 2, py + 6, this.themeW + 4, 12)
                || (this.pinW > 0 && in(mx, my, this.pinX - 2, py + 26, this.pinW + 4, 12))
                || in(mx, my, px, py + ph - 14, pw, 14);
        }

        @Override
        public boolean scroll(double mx, double my, double amount) {
            if (!in(mx, my, tx, ty, tw, th)) return false;
            listScroll = (int) Math.max(0, Math.min(maxListScroll(), listScroll - amount * ROW * 2));
            return true;
        }

        @Override
        public void drag(double mx, double my) {
            if (!dragging) return;
            int w = (int) Math.round(mx - grab) - 6 - tx;
            boolean fold = w < NAV_MIN - 40;
            int want = fold ? navWant : navWidth(w);
            if (fold == folded && (fold || want == tw)) return;
            folded = fold;
            if (!fold) navWant = want;
            relayout();
        }

        @Override
        public void release(double mx, double my) {
            if (!dragging) return;
            dragging = false;
            GuidePrefs.setNav(navWant, folded);
        }
    }
}
