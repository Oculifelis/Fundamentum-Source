package com.fundamentum.client;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * The Config button in the mod list: every Fundamentum config file, then its settings, edited in place. Booleans and
 * enums cycle, numbers, strings and string lists (comma separated) are typed and checked against the spec's range and
 * validator as they are typed; an invalid entry turns red and is not saved. Sections open as their own page. Done saves
 * the file (Forge reloads it, as when the file is edited by hand); Cancel drops the edits.
 * <p>
 * The 26 builds use NeoForge's generic {@code ConfigurationScreen}. Forge 47 (1.20.1) has only the
 * {@code ConfigScreenFactory} hook and no generic screen, so this one walks the {@link ForgeConfigSpec}s itself.
 */
public class ConfigScreen extends Screen {
    /** One config file: its name in {@code config/} and its spec. */
    public record File(String name, ForgeConfigSpec spec) {}

    private final Screen parent;
    private final List<File> files;
    private final File file;
    private final UnmodifiableConfig section;
    private final List<String> sectionPath;
    /** Edits waiting for Done, shared by every page of one file: value to new value. */
    private final Map<ForgeConfigSpec.ConfigValue<?>, Object> pending;
    private final Map<ForgeConfigSpec.ConfigValue<?>, Boolean> invalid;
    private Rows rows;
    private Button done;

    /** The first page: one button per config file. */
    public ConfigScreen(Screen parent, List<File> files) {
        this(parent, Component.literal("Fundamentum"), files, null, null, List.of(), new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private ConfigScreen(Screen parent, Component title, List<File> files, File file, UnmodifiableConfig section, List<String> sectionPath,
                         Map<ForgeConfigSpec.ConfigValue<?>, Object> pending, Map<ForgeConfigSpec.ConfigValue<?>, Boolean> invalid) {
        super(title);
        this.parent = parent;
        this.files = files;
        this.file = file;
        this.section = section;
        this.sectionPath = sectionPath;
        this.pending = pending;
        this.invalid = invalid;
    }

    @Override
    protected void init() {
        rows = new Rows(minecraft, width, height, 32, height - 32);
        if (file == null) {
            for (File f : files) {
                boolean loaded = f.spec().isLoaded();
                Button open = Button.builder(Component.literal(f.name()), b -> minecraft.setScreen(new ConfigScreen(this,
                        Component.literal(f.name()), files, f, f.spec().getValues(), List.of(), new LinkedHashMap<>(), new LinkedHashMap<>())))
                    .bounds(0, 0, 310, 20).build();
                open.active = loaded;
                if (!loaded) {
                    open.setTooltip(Tooltip.create(Component.literal("Loads with a world (server config)")));
                }
                rows.add(new Row(null, open));
            }
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 100, height - 27, 200, 20).build());
        } else {
            for (Map.Entry<String, Object> e : section.valueMap().entrySet()) {
                List<String> path = new ArrayList<>(sectionPath);
                path.add(e.getKey());
                if (e.getValue() instanceof UnmodifiableConfig sub) {
                    Component name = label(file.spec().getLevelTranslationKey(path), e.getKey());
                    Button open = Button.builder(name.copy().append("..."), b -> minecraft.setScreen(new ConfigScreen(this,
                            title.copy().append(" > ").append(name), files, file, sub, path, pending, invalid)))
                        .bounds(0, 0, 310, 20).build();
                    String comment = file.spec().getLevelComment(path);
                    if (comment != null && !comment.isBlank()) {
                        open.setTooltip(Tooltip.create(Component.literal(comment)));
                    }
                    rows.add(new Row(null, open));
                } else if (e.getValue() instanceof ForgeConfigSpec.ConfigValue<?> value) {
                    Object spec = file.spec().getSpec().get(path);
                    if (spec instanceof ForgeConfigSpec.ValueSpec vs) {
                        rows.add(entry(e.getKey(), value, vs));
                    }
                }
            }
            if (sectionPath.isEmpty()) {
                done = addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> save())
                    .bounds(width / 2 - 154, height - 27, 150, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> minecraft.setScreen(parent))
                    .bounds(width / 2 + 4, height - 27, 150, 20).build());
            } else {
                addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> minecraft.setScreen(parent))
                    .bounds(width / 2 - 100, height - 27, 200, 20).build());
            }
        }
        addWidget(rows);
        refreshDone();
    }

    private static Component label(String key, String fallback) {
        if (key != null && I18n.exists(key)) {
            return Component.translatable(key);
        }
        String[] words = fallback.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").split(" ");
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(out.length() == 0 ? Character.toUpperCase(w.charAt(0)) + w.substring(1) : w);
        }
        return Component.literal(out.toString());
    }

    private Object current(ForgeConfigSpec.ConfigValue<?> value) {
        return pending.containsKey(value) ? pending.get(value) : value.get();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Row entry(String key, ForgeConfigSpec.ConfigValue<?> value, ForgeConfigSpec.ValueSpec vs) {
        Component name = label(vs.getTranslationKey(), key);
        Object now = current(value);
        Object def = vs.getDefault();
        AbstractWidget widget;
        if (now instanceof Boolean b) {
            widget = CycleButton.onOffBuilder(b).displayOnlyValue()
                .create(0, 0, 120, 20, name, (button, v) -> change(value, vs, v));
        } else if (now instanceof Enum<?> en) {
            List<Enum> values = new ArrayList<>();
            for (Object c : en.getDeclaringClass().getEnumConstants()) {
                if (vs.test(c)) values.add((Enum) c);
            }
            widget = CycleButton.<Enum>builder(v -> Component.literal(v.name())).withValues(values).withInitialValue(en).displayOnlyValue()
                .create(0, 0, 120, 20, name, (button, v) -> change(value, vs, v));
        } else if (now instanceof Number || now instanceof String || now instanceof List) {
            EditBox box = new EditBox(font, 0, 0, 118, 18, name);
            box.setMaxLength(4096);
            box.setValue(now instanceof List<?> list ? String.join(", ", list.stream().map(String::valueOf).toList()) : String.valueOf(now));
            box.setResponder(text -> {
                Object parsed = parse(text, now);
                boolean ok = parsed != null && vs.test(parsed);
                box.setTextColor(ok ? 0xE0E0E0 : 0xFF5555);
                invalid.put(value, !ok);
                if (ok) {
                    change(value, vs, parsed);
                }
                refreshDone();
            });
            widget = box;
        } else {
            Button b = Button.builder(Component.literal(String.valueOf(now)), x -> {}).bounds(0, 0, 120, 20).build();
            b.active = false;
            widget = b;
        }
        MutableComponent tip = Component.empty();
        if (vs.getComment() != null && !vs.getComment().isBlank()) {
            tip.append(Component.literal(vs.getComment())).append("\n");
        }
        tip.append(Component.literal("Default: " + def).withStyle(ChatFormatting.GRAY));
        if (vs.needsWorldRestart()) {
            tip.append(Component.literal("\nNeeds a world restart").withStyle(ChatFormatting.YELLOW));
        }
        widget.setTooltip(Tooltip.create(tip));
        return new Row(name, widget);
    }

    /** {@code text} read as the same type as {@code like}, or null when it does not parse. */
    private static Object parse(String text, Object like) {
        String t = text.trim();
        try {
            if (like instanceof Integer) return Integer.parseInt(t);
            if (like instanceof Long) return Long.parseLong(t);
            if (like instanceof Double) return Double.parseDouble(t);
            if (like instanceof Float) return Float.parseFloat(t);
            if (like instanceof String) return text;
            if (like instanceof List<?>) {
                return t.isEmpty() ? new ArrayList<String>() : new ArrayList<>(Arrays.stream(t.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private void change(ForgeConfigSpec.ConfigValue<?> value, ForgeConfigSpec.ValueSpec vs, Object v) {
        if (vs.test(v)) {
            pending.put(value, v);
        }
    }

    private void refreshDone() {
        if (done != null) {
            done.active = !invalid.containsValue(Boolean.TRUE);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void save() {
        if (!pending.isEmpty() && file.spec().isLoaded()) {
            for (Map.Entry<ForgeConfigSpec.ConfigValue<?>, Object> e : pending.entrySet()) {
                if (!java.util.Objects.equals(e.getKey().get(), e.getValue())) {
                    ((ForgeConfigSpec.ConfigValue) e.getKey()).set(e.getValue());
                }
            }
            file.spec().save();
        }
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        rows.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private final class Rows extends ContainerObjectSelectionList<Row> {
        Rows(Minecraft mc, int width, int height, int top, int bottom) {
            super(mc, width, height, top, bottom, 24);
        }

        void add(Row row) {
            addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return 310;
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + 165;
        }
    }

    /** A label on the left and its widget on the right, or a full-width button (label null). */
    private final class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final Component name;
        private final AbstractWidget widget;

        Row(Component name, AbstractWidget widget) {
            this.name = name;
            this.widget = widget;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int rowWidth, int rowHeight, int mouseX, int mouseY,
                           boolean hovered, float partialTick) {
            if (name == null) {
                widget.setX(left);
                widget.setY(top);
            } else {
                g.drawString(font, name, left, top + 6, 0xFFFFFF);
                widget.setX(left + rowWidth - widget.getWidth() - 1);
                widget.setY(top + (20 - widget.getHeight()) / 2);
            }
            widget.render(g, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(widget);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(widget);
        }
    }
}
