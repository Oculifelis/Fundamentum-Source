package com.fundamentum.guide.client;

import com.fundamentum.guide.GuidePage;
import com.fundamentum.guide.Guides;
import com.fundamentum.port.FId;
import com.fundamentum.unlock.Unlocks;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The guide: a search box and a list on the left (the books, a book's chapters and pages, or search results), the open
 * page on the right. Links and the Back button move through pages. It reopens where it was last closed. Client only;
 * nearly the same file on every version (the canvas that draws pages is per version, and 1.20.1 adds the backdrop).
 */
public final class GuideScreen extends Screen {
    private static final int LIST_W = 124;
    private static final int ROW = 20;

    /** What the right side shows: the front page (no book), a book's front, or a page. */
    private record View(String book, FId page) {
        static final View HOME = new View(null, null);
    }

    private static View last = View.HOME;
    private static String lastSearch = "";

    private final Deque<View> history = new ArrayDeque<>();
    private View view;
    private String query;
    private int listPage;
    private EditBox search;

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

    /** 1.20.1 screens draw no backdrop by themselves (later versions do), so the guide dims the world here. */
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected void init() {
        int w = Math.min(460, this.width - 16);
        int left = (this.width - w) / 2;
        int top = 8;
        int bottom = this.height - 28;
        Guides.Library lib = Guides.library();
        Predicate<FId> unlocked = unlocked();

        this.search = new EditBox(this.font, left, top, LIST_W, 16, Component.translatableWithFallback("guide.fundamentum.search", "Search"));
        this.search.setHint(Component.translatableWithFallback("guide.fundamentum.search", "Search"));
        this.search.setValue(this.query);
        this.search.setResponder(text -> {
            if (text.equals(this.query)) return;
            this.query = text;
            this.listPage = 0;
            rebuild();
            this.setFocused(this.search);
        });
        addRenderableWidget(this.search);

        // left list
        List<Object[]> entries = listEntries(lib, unlocked);
        int perPage = Math.max(1, (bottom - (top + 22)) / ROW);
        int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        this.listPage = Math.max(0, Math.min(this.listPage, pages - 1));
        int y = top + 22;
        for (int i = this.listPage * perPage; i < Math.min(entries.size(), (this.listPage + 1) * perPage); i++) {
            Object[] e = entries.get(i);
            Component label = (Component) e[0];
            View target = (View) e[1];
            if (target == null) {
                addRenderableWidget(new StringWidget(left + 2, y + 6, LIST_W - 2, 10, label, this.font));
            } else {
                Button b = addRenderableWidget(Button.builder(label, btn -> go(target)).bounds(left, y, LIST_W, 18).build());
                b.active = !target.equals(this.view);
            }
            y += ROW;
        }

        // page
        int cx = left + LIST_W + 6;
        int cw = w - LIST_W - 6;
        GuideCanvas canvas = new GuideCanvas(cx, top, cw, bottom - top - 2, this::link);
        canvas.setContent(content(lib, unlocked, canvas.contentWidth()));
        addRenderableWidget(canvas);

        // buttons
        int by = this.height - 24;
        Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            this.listPage--;
            rebuild();
        }).bounds(left, by, 20, 20).build());
        prev.active = this.listPage > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            this.listPage++;
            rebuild();
        }).bounds(left + 24, by, 20, 20).build());
        next.active = this.listPage < pages - 1;
        Button back = addRenderableWidget(Button.builder(Component.translatableWithFallback("gui.back", "Back"), b -> {
            if (!this.history.isEmpty()) {
                this.view = this.history.pop();
                this.listPage = 0;
                rebuild();
            }
        }).bounds(left + w - 164, by, 80, 20).build());
        back.active = !this.history.isEmpty();
        addRenderableWidget(Button.builder(Component.translatableWithFallback("gui.done", "Done"), b -> onClose())
            .bounds(left + w - 80, by, 80, 20).build());
    }

    /** List rows: {label, view to open} or {label, null} for a heading. */
    private List<Object[]> listEntries(Guides.Library lib, Predicate<FId> unlocked) {
        List<Object[]> out = new ArrayList<>();
        if (!this.query.isBlank()) {
            List<GuidePage> found = Guides.search(lib, this.query, unlocked);
            out.add(new Object[] {Component.translatableWithFallback("guide.fundamentum.results", "%s found", found.size()), null});
            for (GuidePage p : found) out.add(new Object[] {Component.literal(p.title()), new View(p.chapter().namespace(), p.id())});
            return out;
        }
        if (this.view.book() == null) {
            for (Guides.Book b : Guides.books(lib, unlocked)) out.add(new Object[] {Component.literal(b.title()), new View(b.namespace(), null)});
            return out;
        }
        out.add(new Object[] {Component.translatableWithFallback("guide.fundamentum.all", "All guides"), View.HOME});
        for (Guides.Chapter c : Guides.chapters(lib, this.view.book(), unlocked)) {
            out.add(new Object[] {Component.literal(c.title()), null});
            for (GuidePage p : Guides.pages(lib, c.id(), unlocked)) out.add(new Object[] {Component.literal(p.title()), new View(this.view.book(), p.id())});
        }
        return out;
    }

    private GuideLayout.Result content(Guides.Library lib, Predicate<FId> unlocked, int width) {
        if (this.view.page() != null) {
            GuidePage page = lib.pages().get(this.view.page());
            if (page != null && Guides.visible(lib, page, unlocked)) return GuideLayout.page(page, this.font, width);
            return GuideLayout.lines(List.of(Component.translatableWithFallback("guide.fundamentum.locked", "This page is not available yet.")), this.font, width);
        }
        List<Component> lines = new ArrayList<>();
        if (this.view.book() != null) {
            Guides.Book b = lib.books().get(this.view.book());
            if (b != null) {
                lines.add(Component.literal(b.title()).withStyle(net.minecraft.ChatFormatting.BOLD));
                lines.add(Component.empty());
                for (Guides.Chapter c : Guides.chapters(lib, b.namespace(), unlocked)) {
                    lines.add(Component.literal(c.title() + " (" + Guides.pages(lib, c.id(), unlocked).size() + ")"));
                }
                return GuideLayout.lines(lines, this.font, width);
            }
        }
        List<Guides.Book> books = Guides.books(lib, unlocked);
        lines.add(Component.translatableWithFallback("screen.fundamentum.guide", "Guide").withStyle(net.minecraft.ChatFormatting.BOLD));
        lines.add(Component.empty());
        if (books.isEmpty()) {
            lines.add(Component.translatableWithFallback("guide.fundamentum.empty", "No guide pages are loaded."));
        } else {
            lines.add(Component.translatableWithFallback("guide.fundamentum.pick", "Pick a guide on the left, or type to search."));
        }
        return GuideLayout.lines(lines, this.font, width);
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
        this.query = "";
        this.listPage = 0;
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    @Override
    public void removed() {
        last = this.view;
        lastSearch = this.query;
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
