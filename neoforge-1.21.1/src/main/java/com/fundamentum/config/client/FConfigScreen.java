package com.fundamentum.config.client;

import com.fundamentum.config.ConfigEntry;
import com.fundamentum.config.ConfigScreenModel;
import com.fundamentum.config.FConfig;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/**
 * The shared config screen: every mod's {@link FConfig} entries with search, mod and category filters, paging, reset
 * per entry, and server values greyed out unless the player is an operator. Built from plain widgets only.
 */
public final class FConfigScreen extends Screen {
    private static final int ROW = 22;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int BAD = 0xFFFF5555;

    private final Screen parent;
    private final Function<Screen, Screen> classic;
    private final ConfigScreenModel model;
    private String searchText = "";
    private int page;
    private EditBox searchBox;

    /** @param classic opens the older settings screen (the loader's config files), or null when there is none */
    public FConfigScreen(Screen parent, Function<Screen, Screen> classic) {
        super(Component.literal("Settings"));
        this.parent = parent;
        this.classic = classic;
        Minecraft mc = Minecraft.getInstance();
        boolean connected = mc.getConnection() != null;
        boolean op = mc.player != null && mc.player.hasPermissions(2);
        this.model = new ConfigScreenModel(!connected || op, connected, key -> I18n.exists(key) ? I18n.get(key) : null);
    }

    public ConfigScreenModel model() {
        return this.model;
    }

    private int rowsPerPage() {
        return Math.max(1, (this.height - 50 - 34) / ROW);
    }

    @Override
    protected void init() {
        int w = Math.min(this.width - 20, 420);
        int left = (this.width - w) / 2;
        addRenderableWidget(new StringWidget(left, 6, w, 12, this.title, this.font));

        this.searchBox = new EditBox(this.font, left, 24, w / 2 - 4, 18, Component.literal("Search"));
        this.searchBox.setHint(Component.literal("Search"));
        this.searchBox.setMaxLength(100);
        this.searchBox.setValue(this.searchText);
        this.searchBox.setResponder(text -> {
            if (text.equals(this.searchText)) return;
            this.searchText = text;
            this.model.search(text);
            this.page = 0;
            rebuildWidgets();
        });
        addRenderableWidget(this.searchBox);

        int bw = (w / 2 - 4) / 2;
        addRenderableWidget(Button.builder(Component.literal("Mod: " + (this.model.mod() == null ? "all" : this.model.mod())), b -> {
            this.model.mod(next(this.model.mods(), this.model.mod()));
            this.model.category(null);
            this.page = 0;
            rebuildWidgets();
        }).bounds(left + w / 2, 24, bw - 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Category: " + (this.model.category() == null ? "all" : this.model.category())), b -> {
            this.model.category(next(this.model.categories(), this.model.category()));
            this.page = 0;
            rebuildWidgets();
        }).bounds(left + w / 2 + bw, 24, w - w / 2 - bw, 18).build());

        List<ConfigEntry> rows = this.model.rows();
        int per = rowsPerPage();
        int pages = Math.max(1, (rows.size() + per - 1) / per);
        this.page = Math.min(this.page, pages - 1);
        int y = 50;
        int labelW = w * 9 / 20;
        int valueW = w - labelW - 26;
        for (int i = this.page * per; i < Math.min(rows.size(), (this.page + 1) * per); i++) {
            addRow(rows.get(i), left, y, labelW, valueW);
            y += ROW;
        }
        if (rows.isEmpty()) addRenderableWidget(new StringWidget(left, 54, w, 12, Component.literal("No settings match."), this.font));

        int by = this.height - 28;
        int third = (w - 8) / 6;
        Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            this.page--;
            rebuildWidgets();
        }).bounds(left, by, 20, 20).build());
        prev.active = this.page > 0;
        addRenderableWidget(new StringWidget(left + 22, by + 4, third - 4, 12, Component.literal((this.page + 1) + "/" + pages), this.font));
        Button nextPage = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            this.page++;
            rebuildWidgets();
        }).bounds(left + 20 + third, by, 20, 20).build());
        nextPage.active = this.page < pages - 1;
        Button classicButton = addRenderableWidget(Button.builder(Component.literal("Classic settings"), b -> {
            if (this.classic != null) this.minecraft.setScreen(this.classic.apply(this));
        }).bounds(left + 44 + third, by, third * 2, 20).build());
        classicButton.active = this.classic != null;
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
            this.model.cancel();
            onClose();
        }).bounds(left + 48 + third * 3, by, third, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
            this.model.apply();
            onClose();
        }).bounds(left + 52 + third * 4, by, w - (52 + third * 4), 20).build());

        if (this.searchBox.getValue().length() > 0) setFocused(this.searchBox);
    }

    private void addRow(ConfigEntry e, int left, int y, int labelW, int valueW) {
        boolean editable = this.model.editable(e);
        String label = (this.model.isPending(e) ? "* " : "") + this.model.label(e) + (e.needsRestart() ? " (needs restart)" : "");
        StringWidget name = new StringWidget(left, y + 4, labelW, 12, Component.literal(label), this.font);
        name.setTooltip(Tooltip.create(Component.literal(this.model.tooltip(e) + (editable ? "" : "\nOnly an operator can change this."))));
        addRenderableWidget(name);
        int vx = left + labelW + 2;
        if (e.type() == ConfigEntry.Type.BOOL || e.type() == ConfigEntry.Type.ENUM) {
            Button b = Button.builder(Component.literal(this.model.text(e)), btn -> {
                List<String> options = e.type() == ConfigEntry.Type.BOOL ? List.of("true", "false") : e.values();
                this.model.edit(e, cycle(options, this.model.text(e)));
                rebuildWidgets();
            }).bounds(vx, y, valueW, 20).build();
            b.active = editable;
            addRenderableWidget(b);
        } else {
            EditBox box = new EditBox(this.font, vx + 1, y + 1, valueW - 2, 18, Component.literal(e.key()));
            box.setMaxLength(4096);
            box.setValue(this.model.text(e));
            box.setEditable(editable);
            box.setResponder(text -> {
                boolean ok = this.model.validate(e, text) == null;
                box.setTextColor(ok ? TEXT : BAD);
                box.setTooltip(ok ? null : Tooltip.create(Component.literal(this.model.validate(e, text))));
                if (ok) this.model.edit(e, text);
            });
            addRenderableWidget(box);
        }
        Button reset = addRenderableWidget(Button.builder(Component.literal("R"), b -> {
            this.model.reset(e);
            rebuildWidgets();
        }).bounds(vx + valueW + 2, y, 20, 20).build());
        reset.setTooltip(Tooltip.create(Component.literal("Reset to default (" + e.toText(e.defaultValue()) + ")")));
        reset.active = editable;
    }

    private static String next(List<String> options, String current) {
        if (options.isEmpty()) return null;
        int i = current == null ? -1 : options.indexOf(current);
        if (i == options.size() - 1) return null;
        return options.get(i + 1);
    }

    private static String cycle(List<String> options, String current) {
        return options.get((options.indexOf(current) + 1) % options.size());
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
