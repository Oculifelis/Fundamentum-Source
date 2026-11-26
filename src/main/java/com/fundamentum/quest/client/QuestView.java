package com.fundamentum.quest.client;

import com.fundamentum.guide.book.BookData;
import com.fundamentum.guide.book.BookStyle;
import com.fundamentum.guide.book.GuideBooks;
import com.fundamentum.guide.client.GuidePaint;
import com.fundamentum.port.FId;
import com.fundamentum.port.FVanilla;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * An open quest screen. The left page lists open offers and taken quests (by group tab in the tabbed layout); the right
 * page shows the picked quest: its story, objectives with counts, time left, and Accept, Abandon and HUD buttons.
 * The book and tabbed layouts draw the screen's book style; the list layout is a plain panel in its colours.
 * Positions are relative to the screen centre and the whole thing shrinks on small screens. The same file on every version.
 */
public final class QuestView implements GuidePaint.Layer {
    private static final int W = 384;
    private static final int H = 266;
    private static final int ROW = 11;
    private static final int LIST_TOP = -74;
    private static final int LIST_BOTTOM = 94;
    private static final int BUTTON_Y = 82;

    private final QuestScreens.Def def;
    private final BookStyle style;
    private int width;
    private int height;
    private float scale = 1;
    private boolean closed;
    private int tab;
    private int scroll;
    private int detailScroll;
    private String selected;
    private String armedAbandon;
    private int seenVersion = -1;

    /** Rows of the left page as last painted: quest id or null for a header, and its y. */
    private final List<String> rowIds = new ArrayList<>();
    private final List<Integer> rowYs = new ArrayList<>();
    private final List<int[]> buttons = new ArrayList<>();
    private final List<String> buttonKinds = new ArrayList<>();

    public QuestView(QuestScreens.Def def, String focus) {
        this.def = def;
        Map<FId, BookStyle> styles = GuideBooks.library().styles();
        BookStyle s = styles.get(def.bookStyle());
        this.style = s != null ? s : styles.get(BookData.DEFAULT_STYLE);
        this.selected = focus;
        sound("open");
    }

    public void resize(int w, int h) {
        this.width = w;
        this.height = h;
        this.scale = Math.min(1F, Math.min((w - 8) / (float) W, (h - 8) / (float) H));
    }

    public boolean finished() {
        return this.closed;
    }

    public void close() {
        if (this.closed) return;
        this.closed = true;
        sound("close");
    }

    /** Points the screen at another quest, e.g. when the server re-opens it on a fresh offer. */
    public void focus(String quest) {
        if (quest != null) {
            this.selected = quest;
            this.armedAbandon = null;
            this.detailScroll = 0;
        }
    }

    // ---- what is listed

    private boolean book() {
        return this.style != null && !this.def.layout().equals("list");
    }

    private boolean tabbed() {
        return this.def.layout().equals("tabbed") && !this.def.groups().isEmpty();
    }

    private boolean inTab(QuestJournal.Quest q) {
        if (!tabbed() || this.tab == 0) return true;
        return this.def.groups().get(this.tab - 1).id().equals(q.group());
    }

    private List<QuestJournal.Quest> shown(List<QuestJournal.Quest> all) {
        List<QuestJournal.Quest> out = new ArrayList<>();
        for (QuestJournal.Quest q : all) if (inTab(q)) out.add(q);
        if (!tabbed() && !this.def.groups().isEmpty()) {
            List<QuestJournal.Quest> sorted = new ArrayList<>();
            for (QuestScreens.Group g : this.def.groups()) for (QuestJournal.Quest q : out) if (q.group().equals(g.id())) sorted.add(q);
            for (QuestJournal.Quest q : out) if (!sorted.contains(q)) sorted.add(q);
            return sorted;
        }
        return out;
    }

    private QuestJournal.Quest find(String id) {
        if (id == null) return null;
        for (QuestJournal.Quest q : QuestJournal.offered()) if (q.id().equals(id)) return q;
        for (QuestJournal.Quest q : QuestJournal.active()) if (q.id().equals(id)) return q;
        return null;
    }

    private QuestJournal.Quest current() {
        QuestJournal.Quest q = find(this.selected);
        if (q != null && inTab(q)) return q;
        List<QuestJournal.Quest> off = shown(QuestJournal.offered());
        List<QuestJournal.Quest> act = shown(QuestJournal.active());
        q = !off.isEmpty() ? off.get(0) : !act.isEmpty() ? act.get(0) : null;
        this.selected = q == null ? null : q.id();
        return q;
    }

    // ---- painting

    @Override
    public void paint(GuidePaint p, int mx, int my) {
        if (this.width == 0 || this.closed) return;
        if (this.seenVersion != QuestJournal.version()) {
            this.seenVersion = QuestJournal.version();
            this.armedAbandon = null;
        }
        float lmx = (mx - this.width / 2F) / this.scale;
        float lmy = (my - this.height / 2F) / this.scale;
        p.push(this.width / 2F, this.height / 2F, this.scale, this.scale);
        if (book()) {
            if (tabbed()) paintTabs(p, lmx, lmy);
            layer(p, "spread");
        } else {
            p.fill(-188, -118, 188, 118, argb("card_edge"));
            p.fill(-187, -117, 187, 117, argb("page"));
            p.fill(-1, -100, 1, 108, argb("rule"));
        }
        titleBanner(p, tr(this.def.title()));
        paintList(p, lmx, lmy);
        paintDetail(p, lmx, lmy);
        p.pop();
    }

    private void paintTabs(GuidePaint p, float mx, float my) {
        BookStyle.Layer tab = this.style.layer("tab");
        BookStyle.Layer up = this.style.layer("tab_up");
        if (tab == null) return;
        for (int i = 0; i <= this.def.groups().size(); i++) {
            boolean sel = i == this.tab;
            BookStyle.Layer l = sel && up != null ? up : tab;
            int x = l.x() + i * 24;
            p.texture(this.style.texture(sel && up != null ? "tab_up" : "tab"), x, l.y(), l.w(), l.h(), l.tw(), l.th());
            FId icon = i == 0 ? null : this.def.groups().get(i - 1).icon();
            if (icon != null) {
                p.push(x + 5, l.y() + 2, 0.75F, 0.75F);
                p.item(stack(icon), 0, 0);
                p.pop();
            } else {
                String label = i == 0 ? "*" : tr(this.def.groups().get(i - 1).title()).substring(0, 1);
                centred(p, label, x + l.w() / 2, l.y() + 4, color("ink"));
            }
        }
    }

    private void paintList(GuidePaint p, float mx, float my) {
        this.rowIds.clear();
        this.rowYs.clear();
        List<QuestJournal.Quest> off = shown(QuestJournal.offered());
        List<QuestJournal.Quest> act = shown(QuestJournal.active());
        QuestJournal.Quest cur = current();
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (!off.isEmpty()) {
            ids.add(null);
            labels.add(tr("fundamentum.quests.offered", "On offer"));
            for (QuestJournal.Quest q : off) {
                ids.add(q.id());
                labels.add(tr(q.name()));
            }
        }
        if (!act.isEmpty()) {
            ids.add(null);
            labels.add(tr("fundamentum.quests.active", "Taken"));
            for (QuestJournal.Quest q : act) {
                ids.add(q.id());
                labels.add(tr(q.name()) + (q.ready() ? " *" : ""));
            }
        }
        if (ids.isEmpty()) {
            centred(p, tr("fundamentum.quests.none", "No quests yet"), -90, -40, color("dim"));
            return;
        }
        int maxScroll = Math.max(0, ids.size() - (LIST_BOTTOM - LIST_TOP) / ROW);
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll));
        p.scissor(-170, LIST_TOP - 1, -8, LIST_BOTTOM);
        int y = LIST_TOP;
        for (int i = this.scroll; i < ids.size() && y + ROW <= LIST_BOTTOM + ROW; i++) {
            String id = ids.get(i);
            if (id == null) {
                text(p, clip(p, labels.get(i), 150), -166, y + 1, color("accent"));
                p.fill(-166, y + 10, -14, y + 11, argb("rule"));
            } else {
                boolean sel = cur != null && cur.id().equals(id);
                boolean hover = mx >= -168 && mx < -12 && my >= y && my < y + ROW;
                if (sel) p.fill(-168, y, -12, y + ROW, argb("card"));
                text(p, clip(p, labels.get(i), 146), -160, y + 2, sel || hover ? color("accent") : color("ink"));
                this.rowIds.add(id);
                this.rowYs.add(y);
            }
            y += ROW;
        }
        p.endScissor();
        if (QuestJournal.done() > 0) {
            text(p, tr("fundamentum.quests.done", "Finished") + ": " + QuestJournal.done(), -166, LIST_BOTTOM + 2, color("dim"));
        }
    }

    private void paintDetail(GuidePaint p, float mx, float my) {
        this.buttons.clear();
        this.buttonKinds.clear();
        QuestJournal.Quest q = current();
        if (q == null) return;
        centred(p, clip(p, tr(q.name()), 150), 88, -100, color("accent"));
        p.fill(10, -88, 166, -87, argb("rule"));
        List<String[]> lines = new ArrayList<>();
        for (String l : wrap(p, tr(q.description()), 152)) lines.add(new String[] {l, "ink"});
        lines.add(new String[] {"", "ink"});
        lines.add(new String[] {tr("fundamentum.quests.objectives", "Objectives"), "accent"});
        for (int i = 0; i < q.objectives().size(); i++) {
            String count = q.active() ? " " + Math.min(i < q.have().size() ? q.have().get(i) : 0, q.need().get(i)) + "/" + q.need().get(i) : q.need().get(i) > 1 ? " x" + q.need().get(i) : "";
            List<String> w = wrap(p, tr(q.objectives().get(i)) + count, 144);
            for (int k = 0; k < w.size(); k++) {
                lines.add(new String[] {(k == 0 ? (q.active() && q.objectiveDone(i) ? "+ " : "- ") : "  ") + w.get(k), q.active() && q.objectiveDone(i) ? "dim" : "ink"});
            }
        }
        long left = QuestJournal.ticksLeft(q);
        if (left >= 0) {
            lines.add(new String[] {"", "ink"});
            lines.add(new String[] {tr("fundamentum.quests.time_left", "Time left") + ": " + QuestJournal.clock(left), left < 1200 ? "accent" : "ink"});
        }
        if (q.active() && q.ready()) {
            lines.add(new String[] {"", "ink"});
            lines.add(new String[] {q.turnIn() ? tr("fundamentum.quests.hand_in", "Ready to hand in") : tr("fundamentum.quests.finishing", "All done"), "accent"});
        }
        int top = -82;
        int rows = (BUTTON_Y - 4 - top) / 10;
        this.detailScroll = Math.max(0, Math.min(this.detailScroll, Math.max(0, lines.size() - rows)));
        int y = top;
        for (int i = this.detailScroll; i < lines.size() && i < this.detailScroll + rows; i++) {
            text(p, lines.get(i)[0], 12, y, color(lines.get(i)[1]));
            y += 10;
        }
        if (q.active()) {
            boolean armed = q.id().equals(this.armedAbandon);
            button(p, armed ? tr("fundamentum.quests.abandon_sure", "Really abandon?") : tr("fundamentum.quests.abandon", "Abandon"),
                12, armed ? 92 : 60, "abandon", mx, my);
            button(p, QuestJournal.onHud(q.id()) ? tr("fundamentum.quests.hud_on", "On HUD") : tr("fundamentum.quests.hud_off", "Off HUD"),
                armed ? 108 : 104, armed ? 56 : 60, "hud", mx, my);
        } else {
            button(p, tr("fundamentum.quests.accept", "Accept"), 58, 60, "accept", mx, my);
        }
    }

    private void button(GuidePaint p, String label, int x, int w, String kind, float mx, float my) {
        boolean hover = mx >= x && mx < x + w && my >= BUTTON_Y && my < BUTTON_Y + 14;
        p.fill(x, BUTTON_Y, x + w, BUTTON_Y + 14, argb("card_edge"));
        p.fill(x + 1, BUTTON_Y + 1, x + w - 1, BUTTON_Y + 13, argb("card"));
        centred(p, clip(p, label, w - 4), x + w / 2, BUTTON_Y + 3, hover ? color("accent") : color("ink"));
        this.buttons.add(new int[] {x, w});
        this.buttonKinds.add(kind);
    }

    // ---- input

    @Override
    public boolean click(double smx, double smy) {
        if (this.closed) return true;
        float mx = (float) ((smx - this.width / 2F) / this.scale);
        float my = (float) ((smy - this.height / 2F) / this.scale);
        if (tabbed() && this.style != null && this.style.layer("tab") != null) {
            BookStyle.Layer t = this.style.layer("tab");
            for (int i = 0; i <= this.def.groups().size(); i++) {
                int x = t.x() + i * 24;
                if (mx >= x && mx < x + t.w() && my >= t.y() - 4 && my < t.y() + t.h()) {
                    if (this.tab != i) {
                        this.tab = i;
                        this.scroll = 0;
                        this.selected = null;
                        sound("turn");
                    }
                    return true;
                }
            }
        }
        for (int i = 0; i < this.rowIds.size(); i++) {
            int y = this.rowYs.get(i);
            if (mx >= -168 && mx < -12 && my >= y && my < y + ROW && y < LIST_BOTTOM) {
                if (!this.rowIds.get(i).equals(this.selected)) {
                    focus(this.rowIds.get(i));
                    sound("turn");
                }
                return true;
            }
        }
        QuestJournal.Quest q = current();
        for (int i = 0; i < this.buttons.size(); i++) {
            int[] b = this.buttons.get(i);
            if (q == null || mx < b[0] || mx >= b[0] + b[1] || my < BUTTON_Y || my >= BUTTON_Y + 14) continue;
            sound("click");
            switch (this.buttonKinds.get(i)) {
                case "accept" -> QuestJournal.send("accept", q.id());
                case "abandon" -> {
                    if (q.id().equals(this.armedAbandon)) {
                        QuestJournal.send("abandon", q.id());
                        this.armedAbandon = null;
                    } else {
                        this.armedAbandon = q.id();
                    }
                }
                case "hud" -> QuestJournal.toggleHud(q.id());
                default -> { }
            }
            return true;
        }
        this.armedAbandon = null;
        return true;
    }

    @Override
    public boolean scroll(double smx, double smy, double amount) {
        float mx = (float) ((smx - this.width / 2F) / this.scale);
        int step = amount > 0 ? -1 : 1;
        if (mx < 0) this.scroll = Math.max(0, this.scroll + step);
        else this.detailScroll = Math.max(0, this.detailScroll + step);
        return true;
    }

    @Override
    public boolean over(double mx, double my) {
        return true;
    }

    // ---- small helpers

    private int color(String key) {
        Integer c = this.def.colors().get(key);
        if (c != null) return c;
        if (this.style != null) return this.style.color(key);
        return switch (key) {
            case "page" -> 0xe8d5a8;
            case "card" -> 0xefe0b8;
            case "card_edge" -> 0xb89858;
            case "rule" -> 0xd4bf90;
            case "accent" -> 0x8e2a2a;
            case "dim" -> 0x7a5a3a;
            default -> 0x3a2414;
        };
    }

    private int argb(String key) {
        return 0xFF000000 | color(key);
    }

    private void layer(GuidePaint p, String name) {
        BookStyle.Layer l = this.style.layer(name);
        if (l != null) p.texture(this.style.texture(name), l.x(), l.y(), l.w(), l.h(), l.tw(), l.th());
    }

    private void titleBanner(GuidePaint p, String title) {
        BookStyle.Layer l = book() ? this.style.layer("title") : null;
        if (l != null) layer(p, "title");
        int cx = l == null ? -90 : l.x() + l.w() / 2;
        int y = l == null ? -100 : l.y() + (l.h() - 8) / 2;
        boolean upper = l == null || this.style.titleUpper();
        String t = upper ? title.toUpperCase(java.util.Locale.ROOT) : title;
        t = clip(p, t, l == null ? 160 : l.w() - 16);
        if (l == null) {
            centred(p, t, cx, y, color("accent"));
            return;
        }
        if (this.style.titleShadow() != null) centred(p, t, cx + 1, y + 1, this.style.titleShadow());
        centred(p, t, cx, y, this.style.titleText());
    }

    private void sound(String key) {
        if (this.style == null) return;
        FId id = this.style.sound(key);
        if (id == null) return;
        try {
            var event = FVanilla.sound(id);
            if (event != null) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, 1F));
        } catch (RuntimeException ex) {
            // a missing sound only stays quiet
        }
    }

    private static ItemStack stack(FId id) {
        Item item = FVanilla.item(id);
        return item == null || item == Items.AIR ? new ItemStack(Items.BOOK) : new ItemStack(item);
    }

    static String tr(String s) {
        return s == null || s.isEmpty() ? "" : Component.translatableWithFallback(s, s).getString();
    }

    static String tr(String key, String fallback) {
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

    private static List<String> wrap(GuidePaint p, String s, int w) {
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
