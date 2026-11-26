package com.fundamentum.guide.client;

import com.fundamentum.guide.book.BookData;
import com.fundamentum.guide.book.BookStyle;
import com.fundamentum.port.FId;
import com.fundamentum.port.FVanilla;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * An open guide book: the cover opening, the contents spread with category tabs, one spread per entry (portrait, cards,
 * health, drops, facts and progress stamps), page turns, the stamp press and the close. Every position is relative to
 * the screen centre; the book shrinks on small screens. Client only, the same file on every version.
 */
public final class BookView implements GuidePaint.Layer {
    private static final int BOOK_W = 384;
    private static final int BOOK_H = 266;
    private static final int CONTENTS = -1;

    private enum Mode { OPENING, OPEN, TURNING, CLOSING, CLOSED }

    private final BookData.Book book;
    private final BookStyle style;
    private final Map<FId, ItemStack> stacks = new HashMap<>();
    private int width;
    private int height;
    private float scale = 1;

    private Mode mode = Mode.OPENING;
    private long modeAt = System.currentTimeMillis();
    private int page = CONTENTS;
    private int fromPage = CONTENTS;
    private int category;
    private int scroll;

    private int pressStamp = -1;
    private long pressAt;
    private boolean pressLanded;

    public BookView(BookData.Book book, BookStyle style) {
        this.book = book;
        this.style = style;
        sound("open");
    }

    public void resize(int w, int h) {
        this.width = w;
        this.height = h;
        this.scale = Math.min(1F, Math.min((w - 8) / (float) BOOK_W, (h - 8) / (float) BOOK_H));
    }

    /** True once the close animation has finished. */
    public boolean finished() {
        return this.mode == Mode.CLOSED;
    }

    public void close() {
        if (this.mode == Mode.CLOSING || this.mode == Mode.CLOSED) return;
        setMode(Mode.CLOSING);
        sound("close");
    }

    // ---- navigation

    /** The entries a reader can open, in reading order (locked ones are left out). */
    private List<BookData.Entry> readable() {
        List<BookData.Entry> out = new ArrayList<>();
        for (BookData.Entry e : this.book.entries()) if (unlocked(e)) out.add(e);
        return out;
    }

    private static boolean unlocked(BookData.Entry e) {
        return (e.unlock() == null || com.fundamentum.unlock.Unlocks.clientHas(e.unlock())) && Bestiary.unlocked(e);
    }

    private void turnTo(int target) {
        if (target == this.page || this.mode != Mode.OPEN) return;
        this.fromPage = this.page;
        this.page = target;
        this.scroll = 0;
        this.pressStamp = -1;
        if (target != CONTENTS) {
            BookData.Entry e = readable().get(target);
            int cat = categoryIndex(e.category());
            if (cat >= 0) this.category = cat;
        }
        setMode(Mode.TURNING);
        sound("turn");
    }

    public void next() {
        int n = readable().size();
        if (this.page == CONTENTS) {
            int first = firstOf(this.category);
            if (first >= 0) turnTo(first);
            else if (n > 0) turnTo(0);
        } else if (this.page + 1 < n) {
            turnTo(this.page + 1);
        }
    }

    public void previous() {
        if (this.page == CONTENTS) return;
        turnTo(this.page == 0 ? CONTENTS : this.page - 1);
    }

    public void contents() {
        turnTo(CONTENTS);
    }

    private int firstOf(int cat) {
        if (cat < 0 || cat >= this.book.categories().size()) return -1;
        String id = this.book.categories().get(cat).id();
        List<BookData.Entry> list = readable();
        for (int i = 0; i < list.size(); i++) if (list.get(i).category().equals(id)) return i;
        return -1;
    }

    private int categoryIndex(String id) {
        for (int i = 0; i < this.book.categories().size(); i++) if (this.book.categories().get(i).id().equals(id)) return i;
        return -1;
    }

    private void setMode(Mode m) {
        this.mode = m;
        this.modeAt = System.currentTimeMillis();
    }

    private float progress(int ticks) {
        float k = (System.currentTimeMillis() - this.modeAt) / (ticks * 50F);
        return Math.max(0F, Math.min(1F, k));
    }

    private static float ease(float k) {
        return (float) (0.5 - 0.5 * Math.cos(Math.PI * k));
    }

    private void sound(String key) {
        FId id = this.style.sound(key);
        if (id == null) return;
        try {
            var event = FVanilla.sound(id);
            if (event != null) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, 1F));
        } catch (RuntimeException ex) {
            // a missing sound only stays quiet
        }
    }

    // ---- painting

    @Override
    public void paint(GuidePaint p, int mx, int my) {
        if (this.width == 0) return;
        float lmx = (mx - this.width / 2F) / this.scale;
        float lmy = (my - this.height / 2F) / this.scale;
        p.push(this.width / 2F, this.height / 2F, this.scale, this.scale);
        int[] entityBox = null;
        switch (this.mode) {
            case OPENING, CLOSING -> {
                float k = progress(this.mode == Mode.OPENING ? this.style.openTicks() : this.style.closeTicks());
                if (k >= 1F) {
                    setMode(this.mode == Mode.OPENING ? Mode.OPEN : Mode.CLOSED);
                    if (this.mode == Mode.OPEN) {
                        BookProgress.requestStats();
                        entityBox = paintOpen(p, lmx, lmy, mx, my);
                    }
                } else {
                    paintCover(p, this.mode == Mode.OPENING ? ease(k) : 1F - ease(k));
                }
            }
            case OPEN -> entityBox = paintOpen(p, lmx, lmy, mx, my);
            case TURNING -> {
                float k = progress(this.style.turnTicks());
                if (k >= 1F) {
                    setMode(Mode.OPEN);
                    entityBox = paintOpen(p, lmx, lmy, mx, my);
                } else {
                    paintTurn(p, ease(k));
                }
            }
            case CLOSED -> { }
        }
        p.pop();
        if (entityBox != null) {
            BookData.Entry e = readable().get(this.page);
            int x0 = sx(entityBox[0]);
            int y0 = sy(entityBox[1]);
            int x1 = sx(entityBox[0] + entityBox[2]);
            int y1 = sy(entityBox[1] + entityBox[3]);
            if (!p.entity(e.entity(), x0, y0, x1, y1, sy(entityBox[1] + entityBox[4]), mx, my, Bestiary.zoom(e.id()))) {
                drawIconBig(p, e.icon(), (x0 + x1) / 2, (y0 + y1) / 2);
            }
        }
        if (this.mode == Mode.OPEN) tooltips(p, lmx, lmy, mx, my);
    }

    private int sx(int x) {
        return Math.round(this.width / 2F + x * this.scale);
    }

    private int sy(int y) {
        return Math.round(this.height / 2F + y * this.scale);
    }

    private void drawIconBig(GuidePaint p, FId icon, int cx, int cy) {
        if (icon == null) return;
        float s = 2F * this.scale;
        p.push(cx - 8 * s, cy - 8 * s, s, s);
        p.item(stack(icon), 0, 0);
        p.pop();
    }

    /** The closed book swinging open: the cover folds toward the spine, then the open spread unfolds from it. */
    private void paintCover(GuidePaint p, float open) {
        if (open < 0.5F) {
            float sxs = 1F - open * 2F;
            if (sxs <= 0.01F) return;
            BookStyle.Layer c = this.style.layer("closed");
            p.push(0, 0, sxs, 1F);
            layer(p, "closed");
            p.pop();
            if (c == null) p.fill(0, -110, (int) (180 * sxs), 110, 0xFF000000 | this.style.color("leather"));
        } else {
            float sxs = (open - 0.5F) * 2F;
            if (sxs <= 0.01F) return;
            p.push(0, 0, sxs, 1F);
            layer(p, "spread");
            p.pop();
        }
    }

    /** A page flap sweeping across the spine: the new right page shows under it first, then the new left page. */
    private void paintTurn(GuidePaint p, float k) {
        boolean forward = this.page > this.fromPage || this.page != CONTENTS && this.fromPage == CONTENTS;
        layer(p, "spread");
        int oldLeft = forward ? this.fromPage : this.page;
        int newRight = forward ? this.page : this.fromPage;
        if (!forward) {
            oldLeft = this.page;
            newRight = this.fromPage;
        }
        int page = 0xFF000000 | this.style.color("page");
        int edge = 0xFF000000 | this.style.color("rule");
        if (forward) {
            if (k < 0.5F) {
                paintLeft(p, this.fromPage, -999, -999, false);
                paintRight(p, this.page, -999, -999, false);
                int w = (int) (176 * (1F - k * 2F));
                p.fill(2, -112, 2 + w, 112, page);
                p.fill(2 + w - 1, -112, 2 + w, 112, edge);
            } else {
                paintLeft(p, this.page, -999, -999, false);
                paintRight(p, this.page, -999, -999, false);
                int w = (int) (176 * (k - 0.5F) * 2F);
                p.fill(-2 - w, -112, -2, 112, page);
                p.fill(-2 - w, -112, -1 - w, 112, edge);
            }
        } else {
            if (k < 0.5F) {
                paintLeft(p, this.page, -999, -999, false);
                paintRight(p, this.fromPage, -999, -999, false);
                int w = (int) (176 * (1F - k * 2F));
                p.fill(-2 - w, -112, -2, 112, page);
                p.fill(-2 - w, -112, -1 - w, 112, edge);
            } else {
                paintLeft(p, this.page, -999, -999, false);
                paintRight(p, this.page, -999, -999, false);
                int w = (int) (176 * (k - 0.5F) * 2F);
                p.fill(2, -112, 2 + w, 112, page);
                p.fill(1 + w, -112, 2 + w, 112, edge);
            }
        }
        if (oldLeft == newRight) return;
    }

    /** The open book; returns where the mob portrait goes {x, y, w, h, ground} or null. */
    private int[] paintOpen(GuidePaint p, float mx, float my, int smx, int smy) {
        layer(p, "spread");
        if (this.page == CONTENTS) paintTabs(p, mx, my);
        paintLeft(p, this.page, mx, my, true);
        paintRight(p, this.page, mx, my, true);
        paintArrows(p, mx, my);
        if (this.page == CONTENTS) return null;
        BookData.Entry e = readable().get(this.page);
        if (e.entity() == null && e.icon() == null) return null;
        if (Bestiary.sheet(e.id()) != null) return new int[] {-166, -68, 152, 74, 70};
        BookStyle.Window w = this.style.window();
        return new int[] {w.x(), w.y(), w.w(), w.h(), w.ground()};
    }

    private void paintLeft(GuidePaint p, int pageNo, float mx, float my, boolean live) {
        if (pageNo == CONTENTS) {
            titleBanner(p, tr(this.book.title()));
            centred(p, tr(this.book.subtitle()), -90, -70, this.style.color("dim"));
            int y = -58;
            for (String line : wrap(p, tr(this.book.intro()), 156)) {
                if (y > 88) break;
                text(p, line, -168, y, this.style.color("ink"));
                y += 10;
            }
            centred(p, this.style.pageNumber(1), -90, 99, this.style.color("dim"));
            return;
        }
        List<BookData.Entry> list = readable();
        if (pageNo >= list.size()) return;
        BookData.Entry e = list.get(pageNo);
        Bestiary.Sheet sheet = Bestiary.sheet(e.id());
        if (sheet != null) {
            sheetLeft(p, pageNo, e, sheet, live);
            return;
        }
        titleBanner(p, tr(e.title()));
        if (!e.subtitle().isEmpty()) centred(p, tr(e.subtitle()), -90, -81 + 0, this.style.color("dim"));
        layer(p, "portrait");
        if (!live && e.icon() != null) {
            BookStyle.Window w = this.style.window();
            p.item(stack(e.icon()), w.x() + w.w() / 2 - 8, w.y() + w.h() / 2 - 8);
        }
        if (this.style.caption() != null && !e.caption().isEmpty()) {
            BookStyle.Caption c = this.style.caption();
            centred(p, tr(e.caption()), c.x(), c.y(), c.color());
        }
        int cy = -70;
        for (int i = 0; i < Math.min(3, e.cards().size()); i++) {
            card(p, e.cards().get(i), -84, cy);
            cy += 26;
        }
        if (e.health() > 0) row(p, HEART, e.health(), -168, 14, 0xC4303C, 0x5A1A20);
        if (e.armor() > 0) row(p, SHIELD, e.armor(), -168, 25, 0x8A8E9C, 0x3E404A);
        if (!e.drops().isEmpty()) {
            text(p, tr("book.fundamentum.drops", "Drops"), -168, 40, this.style.color("ink"));
            dotted(p, -168, 50, 156);
            int y = 54;
            for (int i = 0; i < Math.min(2, e.drops().size()); i++) {
                BookData.Drop d = e.drops().get(i);
                slot(p, -168, y);
                p.item(stack(d.item()), -166, y + 2);
                String label = d.label().isEmpty() ? stack(d.item()).getHoverName().getString() : tr(d.label());
                text(p, clip(p, label, 112), -144, y + 6, this.style.color("ink"));
                if (!d.chance().isEmpty()) text(p, d.chance(), -14 - p.font().width(d.chance()), y + 6, this.style.color("dim"));
                y += 21;
            }
        }
        centred(p, this.style.pageNumber(pageNo * 2 + 3), -90, 99, this.style.color("dim"));
    }

    private void paintRight(GuidePaint p, int pageNo, float mx, float my, boolean live) {
        int ink = this.style.color("ink");
        if (pageNo == CONTENTS) {
            String head = this.book.categories().isEmpty() ? tr("book.fundamentum.contents", "Contents")
                : tr(this.book.categories().get(Math.min(this.category, this.book.categories().size() - 1)).title());
            text(p, head, 10, -100, ink);
            rule(p);
            List<BookData.Entry> all = this.book.categories().isEmpty() ? this.book.entries()
                : this.book.in(this.book.categories().get(Math.min(this.category, this.book.categories().size() - 1)).id());
            List<BookData.Entry> read = readable();
            int y = -81;
            for (int i = this.scroll; i < all.size() && y <= 75; i++) {
                BookData.Entry e = all.get(i);
                boolean open = unlocked(e);
                boolean hover = live && open && mx >= 8 && mx < 168 && my >= y - 1 && my < y + 11;
                String name = open ? tr(e.title()) : "???";
                int[] done = stampsDone(e);
                String count = open && done[1] > 0 ? done[0] + "/" + done[1] : "";
                int cw = count.isEmpty() ? 0 : p.font().width(count) + 6;
                text(p, clip(p, name, 156 - cw), 10, y, open ? (hover ? this.style.color("accent") : ink) : this.style.color("dim"));
                if (!count.isEmpty()) {
                    text(p, count, 166 - p.font().width(count), y, done[0] >= done[1] ? this.style.color("accent") : this.style.color("dim"));
                }
                y += 12;
                if (open && read.indexOf(e) < 0) continue;
            }
            centred(p, this.style.pageNumber(2), 88, 99, this.style.color("dim"));
            return;
        }
        List<BookData.Entry> list = readable();
        if (pageNo >= list.size()) return;
        BookData.Entry e = list.get(pageNo);
        Bestiary.Sheet sheet = Bestiary.sheet(e.id());
        if (sheet != null) {
            sheetRight(p, pageNo, e, sheet, live);
            return;
        }
        String head = this.style.logHeader() ? tr("book.fundamentum.log_entry", "Log entry #%s").replace("%s", Integer.toString(pageNo + 1))
            : tr("book.fundamentum.field_notes", "Field notes");
        text(p, head, 10, -100, ink);
        rule(p);
        int y = -81;
        for (String fact : e.facts()) {
            List<String> lines = wrap(p, tr(fact), 150);
            if (y + 10 * lines.size() > 10) break;
            text(p, this.style.bullet(), 10, y, this.style.color("accent"));
            for (String line : lines) {
                text(p, line, 18, y, ink);
                y += 10;
            }
            y += 3;
        }
        if (this.style.hunter() && e.entity() != null) {
            int kills = BookProgress.kills(e.entity());
            String slain = tr("book.fundamentum.slain", "Slain") + " " + kills;
            if (y <= 0) {
                text(p, slain, 10, y, this.style.color("dim"));
                tally(p, 18 + p.font().width(slain), y, kills);
            }
        } else if (!e.flavour().isEmpty()) {
            for (String line : wrap(p, tr(e.flavour()), 156)) {
                if (y > 2) break;
                text(p, line, 10, y, this.style.color("dim"));
                y += 10;
            }
        }
        if (!e.stamps().isEmpty()) {
            text(p, tr("book.fundamentum.progress", "Progress"), 10, 14, ink);
            dotted(p, 10, 24, 156);
            for (int i = 0; i < Math.min(4, e.stamps().size()); i++) stamp(p, e, i, live);
        }
        centred(p, this.style.pageNumber(pageNo * 2 + 4), 88, 99, this.style.color("dim"));
    }

    /** Bestiary stat sheet, left page: title, a wide portrait frame (the model is drawn over it), hearts and the lore line. */
    private void sheetLeft(GuidePaint p, int pageNo, BookData.Entry e, Bestiary.Sheet sheet, boolean live) {
        titleBanner(p, tr(e.title()));
        centred(p, tr(e.subtitle()), -90, -81, this.style.color("dim"));
        p.fill(-168, -70, -12, 8, 0xFF000000 | this.style.color("card_edge"));
        p.fill(-167, -69, -13, 7, 0xFF000000 | this.style.color("card"));
        if (!live && e.icon() != null) drawIconBig(p, e.icon(), -90, -31);
        int health = Bestiary.health(sheet.entity());
        if (health > 0) row(p, HEART, health, -168, 14, 0xC4303C, 0x5A1A20);
        String key = "bestiary." + sheet.entity().namespace() + "." + sheet.entity().path();
        if (net.minecraft.locale.Language.getInstance().has(key)) {
            int y = 28;
            for (String line : wrap(p, tr(key), 156)) {
                if (y > 88) break;
                text(p, line, -168, y, this.style.color("dim"));
                y += 10;
            }
        }
        centred(p, this.style.pageNumber(pageNo * 2 + 3), -90, 99, this.style.color("dim"));
    }

    /** Bestiary stat sheet, right page: the stat table, where the mob comes from, then the Seen and Slain stamps. */
    private void sheetRight(GuidePaint p, int pageNo, BookData.Entry e, Bestiary.Sheet sheet, boolean live) {
        int ink = this.style.color("ink");
        int dim = this.style.color("dim");
        text(p, tr("book.fundamentum.bestiary.stats", "Stats"), 10, -100, ink);
        rule(p);
        int y = -81;
        for (Bestiary.Row r : Bestiary.rows(sheet.entity())) {
            String k = tr(r.key(), r.fallback());
            String v = r.value();
            if (v.startsWith("@")) {
                int bar = v.indexOf('|');
                v = tr(v.substring(1, bar), v.substring(bar + 1));
            }
            v = clip(p, v, 100);
            int vw = p.font().width(v);
            text(p, k, 10, y, ink);
            text(p, v, 166 - vw, y, ink);
            dotted(p, 13 + p.font().width(k), y + 7, 166 - vw - 16 - p.font().width(k));
            y += 10;
        }
        y += 1;
        if (sheet.source() != null) {
            text(p, tr("book.fundamentum.bestiary.comes_from", "Comes from"), 10, y + 1, dim);
            slot(p, 146, y - 5);
            p.item(stack(sheet.source()), 148, y - 3);
        } else {
            y = sheetList(p, tr("book.fundamentum.bestiary.spawns", "Spawns in"), sheet.biomes(), true, y);
            sheetList(p, tr("book.fundamentum.drops", "Drops"), sheet.drops(), false, y);
        }
        if (!e.stamps().isEmpty()) {
            text(p, tr("book.fundamentum.progress", "Progress"), 10, 14, ink);
            dotted(p, 10, 24, 156);
            for (int i = 0; i < Math.min(4, e.stamps().size()); i++) stamp(p, e, i, live);
        }
        centred(p, this.style.pageNumber(pageNo * 2 + 4), 88, 99, this.style.color("dim"));
    }

    /** A label and a short list of biomes or items, kept above the stamps. */
    private int sheetList(GuidePaint p, String label, List<FId> ids, boolean biomes, int y) {
        if (ids.isEmpty() || y > 0) return y;
        text(p, label, 10, y, this.style.color("dim"));
        if (biomes) {
            StringBuilder names = new StringBuilder();
            for (FId id : ids) {
                if (!names.isEmpty()) names.append(", ");
                names.append(tr("biome." + id.namespace() + "." + id.path().replace('/', '.'), id.path().replace('_', ' ')));
            }
            int x = 14 + p.font().width(label);
            List<String> lines = wrap(p, names.toString(), 166 - x);
            for (String line : lines) {
                if (y > 0) break;
                text(p, line, x, y, this.style.color("ink"));
                y += 10;
            }
            return y + 1;
        }
        int x = 166 - Math.min(5, ids.size()) * 18;
        for (int i = 0; i < Math.min(5, ids.size()); i++) p.item(stack(ids.get(i)), x + i * 18, y - 4);
        return y + 14;
    }

    private void stamp(GuidePaint p, BookData.Entry e, int i, boolean live) {
        BookData.Stamp s = e.stamps().get(i);
        int[] c = BookProgress.count(s);
        boolean done = c[0] >= c[1];
        int cx = 29 + i * 40;
        int cy = 44;
        float press = 1F;
        if (done && live) {
            if (this.pressStamp < 0 && BookProgress.firstPress(e.id(), i)) {
                this.pressStamp = i;
                this.pressAt = System.currentTimeMillis();
                this.pressLanded = false;
            }
            if (this.pressStamp == i) {
                float k = Math.min(1F, (System.currentTimeMillis() - this.pressAt) / (this.style.stampTicks() * 50F));
                press = Math.max(1F, 2.2F - 1.2F * k * k);
                if (k >= 1F && !this.pressLanded) {
                    this.pressLanded = true;
                    this.pressStamp = -1;
                    sound("stamp");
                }
            }
        }
        String layerName = done ? "stamp_on" : "stamp_off";
        BookStyle.Layer l = this.style.layer(layerName);
        p.push(cx, cy, press, press);
        if (l != null) {
            p.texture(this.style.texture(layerName), l.x() + i * 40 - cx, l.y() - cy, l.w(), l.h(), l.tw(), l.th());
        }
        FId icon = s.icon() != null ? s.icon() : e.icon();
        if (icon != null) {
            p.item(stack(icon), -8, -8);
            if (!done) p.fill(-8, -8, 8, 8, 0xB3000000 | this.style.color("page"));
        }
        p.pop();
        String label = done && !s.label().isEmpty() ? tr(s.label()) : c[0] + "/" + c[1];
        centred(p, clip(p, label, 38), cx, cy + this.style.stampLabelDy(), this.style.stampText());
    }

    private void paintTabs(GuidePaint p, float mx, float my) {
        BookStyle.Layer tab = this.style.layer("tab");
        BookStyle.Layer up = this.style.layer("tab_up");
        if (tab == null || this.book.categories().size() < 2) return;
        for (int i = 0; i < this.book.categories().size(); i++) {
            boolean sel = i == this.category;
            BookStyle.Layer l = sel && up != null ? up : tab;
            int x = l.x() + i * 24;
            p.texture(this.style.texture(sel && up != null ? "tab_up" : "tab"), x, l.y(), l.w(), l.h(), l.tw(), l.th());
            FId icon = this.book.categories().get(i).icon();
            if (icon != null) {
                p.push(x + 3, l.y() + 2, 1F, 1F);
                p.push(0, 0, 0.75F, 0.75F);
                p.item(stack(icon), 2, 0);
                p.pop();
                p.pop();
            }
        }
    }

    private void paintArrows(GuidePaint p, float mx, float my) {
        if (this.page != CONTENTS) arrow(p, "<", -150, 99, mx, my);
        if (this.page == CONTENTS ? !readable().isEmpty() : this.page + 1 < readable().size()) arrow(p, ">", 150, 99, mx, my);
    }

    private void arrow(GuidePaint p, String s, int x, int y, float mx, float my) {
        boolean hover = mx >= x - 6 && mx < x + 8 && my >= y - 3 && my < y + 10;
        centred(p, s, x, y, hover ? this.style.color("accent") : this.style.color("ink"));
    }

    private void tooltips(GuidePaint p, float mx, float my, int smx, int smy) {
        if (this.page == CONTENTS) {
            BookStyle.Layer tab = this.style.layer("tab");
            if (tab != null && this.book.categories().size() > 1) {
                for (int i = 0; i < this.book.categories().size(); i++) {
                    int x = tab.x() + i * 24;
                    if (mx >= x && mx < x + tab.w() && my >= tab.y() - 4 && my < tab.y() + tab.h()) {
                        p.tooltip(Component.literal(tr(this.book.categories().get(i).title())), smx, smy);
                    }
                }
            }
            return;
        }
        BookData.Entry e = readable().get(this.page);
        Bestiary.Sheet sheet = Bestiary.sheet(e.id());
        if (sheet != null && sheet.source() != null) {
            int sy = -81 + 10 * Bestiary.rows(sheet.entity()).size() + 1;
            if (mx >= 146 && mx < 166 && my >= sy - 5 && my < sy + 15) p.tooltip(stack(sheet.source()), smx, smy);
        }
        int y = 54;
        for (int i = 0; i < Math.min(2, e.drops().size()); i++) {
            if (mx >= -168 && mx < -148 && my >= y && my < y + 20) p.tooltip(stack(e.drops().get(i).item()), smx, smy);
            y += 21;
        }
        for (int i = 0; i < Math.min(4, e.stamps().size()); i++) {
            int cx = 29 + i * 40;
            if (mx >= cx - 14 && mx < cx + 14 && my >= 30 && my < 58) p.tooltip(Component.literal(stampText(e, e.stamps().get(i))), smx, smy);
        }
        for (int i = 0; i < Math.min(3, e.cards().size()); i++) {
            int cy = -70 + i * 26;
            BookData.Card c = e.cards().get(i);
            if (c.icon() != null && mx >= -84 && mx < -12 && my >= cy && my < cy + 24) {
                p.tooltip(Component.literal(tr(c.label()) + ": " + tr(c.value())), smx, smy);
            }
        }
    }

    private String stampText(BookData.Entry e, BookData.Stamp s) {
        int[] c = BookProgress.count(s);
        String what = s.target() == null ? "" : name(s.target(), s.kind());
        String verb = switch (s.kind()) {
            case "kill" -> tr("book.fundamentum.stamp.kill", "Slay %s").replace("%s", (s.count() > 1 ? s.count() + " " : "") + what);
            case "seen" -> tr("book.fundamentum.stamp.seen", "Spot %s").replace("%s", what);
            case "obtain" -> tr("book.fundamentum.stamp.obtain", "Obtain %s").replace("%s", (s.count() > 1 ? s.count() + " " : "") + what);
            default -> tr("book.fundamentum.stamp.unlock", "Unlock %s").replace("%s", s.label().isEmpty() ? what : tr(s.label()));
        };
        return verb + " (" + c[0] + "/" + c[1] + ")";
    }

    private String name(FId id, String kind) {
        if ("kill".equals(kind) || "seen".equals(kind)) {
            var type = FVanilla.entityType(id);
            return type == null ? id.toString() : type.getDescription().getString();
        }
        if ("obtain".equals(kind)) return stack(id).getHoverName().getString();
        return id.toString();
    }

    // ---- clicks

    @Override
    public boolean click(double smx, double smy) {
        if (this.mode != Mode.OPEN) return true;
        float mx = (float) ((smx - this.width / 2F) / this.scale);
        float my = (float) ((smy - this.height / 2F) / this.scale);
        if (my >= 96 && my < 110) {
            if (this.page != CONTENTS && mx >= -156 && mx < -142) {
                previous();
                return true;
            }
            if (mx >= 142 && mx < 158) {
                next();
                return true;
            }
            if (this.page != CONTENTS && mx >= -110 && mx < -70) {
                contents();
                return true;
            }
        }
        if (this.page != CONTENTS) return true;
        BookStyle.Layer tab = this.style.layer("tab");
        if (tab != null && this.book.categories().size() > 1) {
            for (int i = 0; i < this.book.categories().size(); i++) {
                int x = tab.x() + i * 24;
                if (mx >= x && mx < x + tab.w() && my >= tab.y() - 4 && my < tab.y() + tab.h()) {
                    if (i != this.category) {
                        this.category = i;
                        this.scroll = 0;
                        sound("click");
                    }
                    return true;
                }
            }
        }
        if (mx >= 8 && mx < 168 && my >= -82) {
            int row = (int) Math.floor((my + 82) / 12F);
            List<BookData.Entry> all = this.book.categories().isEmpty() ? this.book.entries()
                : this.book.in(this.book.categories().get(Math.min(this.category, this.book.categories().size() - 1)).id());
            int i = this.scroll + row;
            if (row >= 0 && row < 14 && i < all.size() && unlocked(all.get(i))) {
                int target = readable().indexOf(all.get(i));
                if (target >= 0) turnTo(target);
            }
        }
        return true;
    }

    @Override
    public boolean scroll(double smx, double smy, double amount) {
        if (this.page != CONTENTS || this.mode != Mode.OPEN) return true;
        List<BookData.Entry> all = this.book.categories().isEmpty() ? this.book.entries()
            : this.book.in(this.book.categories().get(Math.min(this.category, this.book.categories().size() - 1)).id());
        int max = Math.max(0, all.size() - 14);
        this.scroll = Math.max(0, Math.min(max, this.scroll - (int) Math.signum(amount)));
        return true;
    }

    @Override
    public boolean over(double mx, double my) {
        return true;
    }

    // ---- small drawing helpers

    private void layer(GuidePaint p, String name) {
        BookStyle.Layer l = this.style.layer(name);
        if (l != null) p.texture(this.style.texture(name), l.x(), l.y(), l.w(), l.h(), l.tw(), l.th());
    }

    private void titleBanner(GuidePaint p, String title) {
        layer(p, "title");
        BookStyle.Layer l = this.style.layer("title");
        int cx = l == null ? -90 : l.x() + l.w() / 2;
        int y = l == null ? -97 : l.y() + (l.h() - 8) / 2;
        String t = this.style.titleUpper() ? title.toUpperCase(java.util.Locale.ROOT) : title;
        t = clip(p, t, l == null ? 160 : l.w() - 16);
        if (this.style.titleShadow() != null) centred(p, t, cx + 1, y + 1, this.style.titleShadow());
        centred(p, t, cx, y, this.style.titleText());
    }

    private void rule(GuidePaint p) {
        int c = 0xFF000000 | this.style.ruleColor();
        p.fill(10, -88, 166, -87, c);
        int a = 0xFF000000 | this.style.color("accent");
        p.fill(87, -90, 89, -85, a);
        p.fill(86, -89, 90, -86, a);
    }

    private void dotted(GuidePaint p, int x, int y, int w) {
        int c = 0xFF000000 | this.style.ruleColor();
        for (int i = 0; i < w; i += 3) p.fill(x + i, y, x + i + 1, y + 1, c);
    }

    private void card(GuidePaint p, BookData.Card c, int x, int y) {
        p.fill(x, y, x + 72, y + 24, 0xFF000000 | this.style.color("card_edge"));
        p.fill(x + 1, y + 1, x + 71, y + 23, 0xFF000000 | this.style.color("card"));
        int tx = x + 4;
        if (c.icon() != null) {
            p.item(stack(c.icon()), x + 3, y + 4);
            tx = x + 21;
        }
        int w = x + 70 - tx;
        text(p, clip(p, tr(c.label()), w), tx, y + 3, this.style.color("dim"));
        text(p, clip(p, tr(c.value()), w), tx, y + 13, c.warn() ? this.style.color("accent") : this.style.cardText());
    }

    private void slot(GuidePaint p, int x, int y) {
        p.fill(x, y, x + 20, y + 20, 0xFF000000 | this.style.color("card_edge"));
        p.fill(x + 1, y + 1, x + 19, y + 19, 0xFF000000 | this.style.color("card"));
    }

    private static final String[] HEART = {".##.##.", "#######", "#######", ".#####.", "..###..", "...#..."};
    private static final String[] SHIELD = {"##...##", "#######", ".#####.", ".#####.", ".#####.", "..###.."};

    /** Up to ten pixel icons for {@code value} half-points (hearts or armour), then the exact number when it's more. */
    private void row(GuidePaint p, String[] shape, int value, int x, int y, int full, int empty) {
        int icons = Math.min(10, (value + 1) / 2);
        for (int i = 0; i < icons; i++) {
            boolean half = i * 2 + 1 == value;
            pixels(p, shape, x + i * 9, y, 0xFF000000 | full, half ? 0xFF000000 | empty : 0);
        }
        if (value > 20) text(p, "x" + value, x + icons * 9 + 2, y - 1, this.style.color("dim"));
    }

    private static void pixels(GuidePaint p, String[] shape, int x, int y, int color, int rightHalf) {
        for (int r = 0; r < shape.length; r++) {
            String row = shape[r];
            for (int c = 0; c < row.length(); c++) {
                if (row.charAt(c) != '#') continue;
                int col = rightHalf != 0 && c > row.length() / 2 ? rightHalf : color;
                p.fill(x + c, y + r, x + c + 1, y + r + 1, col);
            }
        }
    }

    private void tally(GuidePaint p, int x, int y, int n) {
        int c = 0xFF000000 | this.style.color("ink");
        int shown = Math.min(n, 20);
        for (int i = 0; i < shown; i++) {
            int group = i / 5;
            int k = i % 5;
            int gx = x + group * 14;
            if (k < 4) p.fill(gx + k * 3, y, gx + k * 3 + 1, y + 8, c);
            else for (int d = 0; d < 11; d++) p.fill(gx - 1 + d, y + 7 - d * 7 / 10, gx + d, y + 8 - d * 7 / 10, c);
        }
    }

    private int[] stampsDone(BookData.Entry e) {
        int done = 0;
        for (BookData.Stamp s : e.stamps()) if (BookProgress.done(s)) done++;
        return new int[] {done, e.stamps().size()};
    }

    private ItemStack stack(FId id) {
        return this.stacks.computeIfAbsent(id, k -> {
            Item item = FVanilla.item(k);
            return item == null || item == Items.AIR ? new ItemStack(Items.BOOK) : new ItemStack(item);
        });
    }

    private static String tr(String s) {
        return s == null || s.isEmpty() ? "" : Component.translatableWithFallback(s, s).getString();
    }

    private static String tr(String key, String fallback) {
        return Component.translatableWithFallback(key, fallback).getString();
    }

    private static void text(GuidePaint p, String s, int x, int y, int rgb) {
        p.text(Component.literal(s), x, y, 0xFF000000 | rgb, false);
    }

    private static void centred(GuidePaint p, String s, int cx, int y, int rgb) {
        text(p, s, cx - p.font().width(s) / 2, y, rgb);
    }

    private static String clip(GuidePaint p, String s, int w) {
        if (p.font().width(s) <= w) return s;
        String dots = "...";
        int end = s.length();
        while (end > 0 && p.font().width(s.substring(0, end)) + p.font().width(dots) > w) end--;
        return s.substring(0, end).stripTrailing() + dots;
    }

    /** Word wrap to {@code w} pixels; a blank line in the text starts a new paragraph. */
    static List<String> wrap(GuidePaint p, String s, int w) {
        List<String> out = new ArrayList<>();
        for (String para : s.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                if (word.isEmpty()) continue;
                String tryLine = line.isEmpty() ? word : line + " " + word;
                if (p.font().width(tryLine) <= w || line.isEmpty()) {
                    line.setLength(0);
                    line.append(tryLine);
                } else {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            out.add(line.toString());
        }
        return out;
    }
}
