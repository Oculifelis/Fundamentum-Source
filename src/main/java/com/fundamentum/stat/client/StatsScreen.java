package com.fundamentum.stat.client;

import com.fundamentum.port.FId;
import com.fundamentum.stat.StatDef;
import com.fundamentum.stat.Stats;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Client only: the local player's stats as the server last sent them, one per line, a page at a time. */
public final class StatsScreen extends Screen {
    private static final int ROW = 12;
    private int page;

    public StatsScreen() {
        super(Component.translatableWithFallback("screen.fundamentum.stats", "Stats"));
    }

    /** The lines shown: declared player and entity stats first (in id order), then undeclared stored ones. */
    public static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        Map<FId, StatDef> defs = Stats.clientDefs();
        Map<FId, Double> values = Stats.clientValues();
        values.forEach((id, v) -> {
            StatDef d = defs.get(id);
            if (d != null && d.scope() != StatDef.Scope.ITEM) out.add(Stats.line(d, v));
        });
        values.forEach((id, v) -> {
            if (!defs.containsKey(id)) out.add(Stats.line(StatDef.undeclared(id), v));
        });
        return out;
    }

    @Override
    protected void init() {
        int w = Math.min(300, this.width - 20);
        int left = (this.width - w) / 2;
        addRenderableWidget(new StringWidget(left, 8, w, 12, this.title, this.font));
        List<Component> lines = lines();
        int perPage = Math.max(1, (this.height - 60) / ROW);
        int pages = Math.max(1, (lines.size() + perPage - 1) / perPage);
        this.page = Math.min(this.page, pages - 1);
        int y = 26;
        for (int i = this.page * perPage; i < Math.min(lines.size(), (this.page + 1) * perPage); i++) {
            addRenderableWidget(new StringWidget(left, y, w, 12, lines.get(i), this.font));
            y += ROW;
        }
        if (lines.isEmpty()) addRenderableWidget(new StringWidget(left, y, w, 12, Component.literal("No stats yet."), this.font));
        int by = this.height - 26;
        Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            this.page--;
            this.rebuild();
        }).bounds(left, by, 20, 20).build());
        prev.active = this.page > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            this.page++;
            this.rebuild();
        }).bounds(left + 24, by, 20, 20).build());
        next.active = this.page < pages - 1;
        addRenderableWidget(Button.builder(Component.translatableWithFallback("gui.done", "Done"), b -> onClose())
            .bounds(left + w - 80, by, 80, 20).build());
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
