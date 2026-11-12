package com.fundamentum.guide.client;

import com.fundamentum.guide.GuidePage;
import com.fundamentum.guide.Guides;
import com.fundamentum.multiblock.MultiblockPattern;
import com.fundamentum.multiblock.MultiblockViews;
import com.fundamentum.multiblock.client.MultiblockScene;
import com.fundamentum.multiblock.Multiblocks;
import com.fundamentum.port.FId;
import com.fundamentum.port.FVanilla;
import com.fundamentum.recipeview.ViewCategory;
import com.fundamentum.recipeview.ViewRecipe;
import com.fundamentum.recipeview.ViewSlot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * Turns a guide page into draw operations at fixed positions inside a column {@code width} wide: wrapped text runs
 * (links get a click box), items, pictures, entities and boxes. The per-version {@code GuideCanvas} draws them. The
 * same file on every version.
 */
public final class GuideLayout {
    private GuideLayout() { }

    public static final int TEXT = 0xFFE0E0E0;
    public static final int HEADING = 0xFFFFE080;
    public static final int LINK = 0xFF7FD4FF;
    public static final int CODE = 0xFFFFB060;
    public static final int MISSING = 0xFFFF6060;
    public static final int FRAME = 0x40000000;
    public static final int SLOT = 0x50FFFFFF;
    private static final int LINE = 10;

    public sealed interface Op permits Text, ItemOp, Image, Entity, Box, Link, Blocks { }

    public record Text(int x, int y, Component text, int colour) implements Op { }

    /** Items cycle once a second when there are several. */
    public record ItemOp(int x, int y, List<ItemStack> stacks) implements Op { }

    public record Image(int x, int y, int w, int h, FId texture) implements Op { }

    public record Entity(int x, int y, int w, int h, FId type, int scale) implements Op { }

    public record Box(int x, int y, int w, int h, int colour) implements Op { }

    /** A click area that opens {@code target}. */
    public record Link(int x, int y, int w, int h, FId target) implements Op { }

    /** A multiblock drawn as real block models in a box; drag to turn it. */
    public record Blocks(int x, int y, int w, int h, FId pattern) implements Op { }

    public record Result(List<Op> ops, int height) { }

    /** Lays out a whole page, its title first. */
    public static Result page(GuidePage page, Font font, int width) {
        List<Op> ops = new ArrayList<>();
        int y = 0;
        y = wrap(ops, font, List.of(new GuidePage.Span(page.title(), true, false, false, null)), 0, y, width, HEADING) + 4;
        ops.add(new Box(0, y - 2, width, 1, 0x60FFFFFF));
        y += 4;
        for (GuidePage.Block b : page.blocks()) y = block(ops, font, b, y, width) + 4;
        return new Result(List.copyOf(ops), y);
    }

    /** Lays out plain lines (for the front pages). */
    public static Result lines(List<Component> lines, Font font, int width) {
        List<Op> ops = new ArrayList<>();
        int y = 0;
        for (Component c : lines) {
            for (var seq : font.split(c, width)) {
                ops.add(new Text(0, y, toComponent(seq), TEXT));
                y += LINE;
            }
        }
        return new Result(List.copyOf(ops), y);
    }

    private static Component toComponent(net.minecraft.util.FormattedCharSequence seq) {
        StringBuilder b = new StringBuilder();
        seq.accept((index, style, cp) -> {
            b.appendCodePoint(cp);
            return true;
        });
        return Component.literal(b.toString());
    }

    private static int block(List<Op> ops, Font font, GuidePage.Block b, int y, int width) {
        if (b instanceof GuidePage.Heading h) {
            return wrap(ops, font, bold(h.text()), 0, y + (h.level() == 1 ? 4 : 2), width, HEADING);
        }
        if (b instanceof GuidePage.Paragraph p) return wrap(ops, font, p.text(), 0, y, width, TEXT);
        if (b instanceof GuidePage.ListItem l) {
            ops.add(new Text(2, y, Component.literal("•"), TEXT));
            return wrap(ops, font, l.text(), 10, y, width, TEXT);
        }
        if (b instanceof GuidePage.Rule) {
            ops.add(new Box(0, y + 3, width, 1, 0x40FFFFFF));
            return y + 7;
        }
        if (b instanceof GuidePage.ItemEmbed e) {
            for (FId id : e.items()) {
                Item item = FVanilla.item(id);
                if (item == null || item == Items.AIR) {
                    ops.add(new Text(0, y, Component.literal("Missing item " + id), MISSING));
                    y += LINE;
                    continue;
                }
                ItemStack stack = new ItemStack(item);
                ops.add(new Box(0, y, 18, 18, SLOT));
                ops.add(new ItemOp(1, y + 1, List.of(stack)));
                ops.add(new Text(22, y + 5, stack.getHoverName(), TEXT));
                y += 20;
            }
            return y;
        }
        if (b instanceof GuidePage.RecipeEmbed r) return recipe(ops, font, r, y, width);
        if (b instanceof GuidePage.MultiblockEmbed m) return multiblock(ops, font, m, y, width);
        if (b instanceof GuidePage.EntityEmbed e) {
            int h = e.scale() * 2 + 10;
            int w = Math.min(width, Math.max(40, e.scale() * 2));
            ops.add(new Box((width - w) / 2, y, w, h, FRAME));
            ops.add(new Entity((width - w) / 2, y, w, h, e.type(), e.scale()));
            return y + h;
        }
        if (b instanceof GuidePage.ImageEmbed i) {
            int w = i.width();
            int h = i.height();
            if (w > width) {
                h = h * width / w;
                w = width;
            }
            ops.add(new Image((width - w) / 2, y, w, h, i.texture()));
            return y + h;
        }
        return y;
    }

    private static List<GuidePage.Span> bold(List<GuidePage.Span> spans) {
        List<GuidePage.Span> out = new ArrayList<>();
        for (GuidePage.Span s : spans) out.add(new GuidePage.Span(s.text(), true, s.italic(), s.code(), s.link()));
        return out;
    }

    private static int recipe(List<Op> ops, Font font, GuidePage.RecipeEmbed r, int y, int width) {
        ViewRecipe recipe = Guides.resolveRecipe(r.category(), r.key());
        if (recipe == null) {
            ops.add(new Text(0, y, Component.literal("Missing recipe " + r.category() + " " + r.key()), MISSING));
            return y + LINE;
        }
        ViewCategory<?> cat = recipe.category();
        ops.add(new Text(0, y, cat.title(), HEADING));
        y += LINE + 2;
        int w = cat.width();
        int x0 = Math.max(0, (width - w) / 2);
        ops.add(new Box(x0 - 3, y - 3, w + 6, cat.height() + 6, FRAME));
        for (ViewSlot slot : cat.slots()) {
            if (slot.kind() != ViewSlot.Kind.ITEM) continue;
            ops.add(new Box(x0 + slot.x() - 1, y + slot.y() - 1, 18, 18, SLOT));
            List<ItemStack> stacks = recipe.items(slot.index());
            if (!stacks.isEmpty()) ops.add(new ItemOp(x0 + slot.x(), y + slot.y(), stacks));
        }
        if (cat.arrow() != null) ops.add(new Text(x0 + cat.arrow().x(), y + cat.arrow().y() + 4, Component.literal("→"), TEXT));
        for (ViewRecipe.Text t : recipe.text()) {
            int tx = t.x() < 0 ? (w - font.width(t.text())) / 2 : t.x();
            ops.add(new Text(x0 + tx, y + t.y(), t.text(), 0xFF000000 | t.colour()));
        }
        for (ViewRecipe.Blocks b : recipe.blocks()) ops.add(new Blocks(x0 + b.x(), y + b.y(), b.w(), b.h(), b.pattern()));
        return y + cat.height() + 4;
    }

    private static int multiblock(List<Op> ops, Font font, GuidePage.MultiblockEmbed m, int y, int width) {
        MultiblockPattern p = Multiblocks.pattern(m.pattern());
        if (p == null) {
            ops.add(new Text(0, y, Component.literal("Missing multiblock " + m.pattern()), MISSING));
            return y + LINE;
        }
        ops.add(new Text(0, y, MultiblockViews.title(m.pattern(), p).copy().append(" (" + p.width() + " x " + p.height() + " x " + p.depth() + ")"), HEADING));
        y += LINE + 2;
        MultiblockScene.Scene scene = MultiblockScene.of(p);
        if (scene != null) {
            int bw = Math.min(width - 8, 160);
            int bh = Math.min(120, Math.max(64, scene.size() * 22));
            int bx = (width - bw) / 2;
            ops.add(new Box(bx - 4, y, bw + 8, bh + 8, FRAME));
            ops.add(new Blocks(bx, y + 4, bw, bh, m.pattern()));
            y += bh + 12;
        }
        int col = 0;
        for (Map.Entry<Character, Integer> e : p.counts().entrySet()) {
            MultiblockPattern.Part part = p.parts().get(e.getKey());
            ItemStack s = part == null ? ItemStack.EMPTY : stack(part);
            if (s.isEmpty()) continue;
            int x = col * 52;
            if (x + 50 > width) {
                col = 0;
                x = 0;
                y += 20;
            }
            ops.add(new Box(x, y, 18, 18, SLOT));
            ops.add(new ItemOp(x + 1, y + 1, List.of(s)));
            ops.add(new Text(x + 21, y + 5, Component.literal("x" + e.getValue()), TEXT));
            col++;
        }
        return y + 20;
    }

    private static ItemStack stack(MultiblockPattern.Part part) {
        try {
            Item item = part.display(Rotation.NONE, Mirror.NONE).getBlock().asItem();
            return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }

    /** Word-wraps spans from {@code indent}; returns the y below the last line. */
    static int wrap(List<Op> ops, Font font, List<GuidePage.Span> spans, int indent, int y, int width, int colour) {
        int x = indent;
        for (GuidePage.Span s : spans) {
            String[] words = s.text().split("(?<= )");
            StringBuilder run = new StringBuilder();
            int runX = x;
            for (String word : words) {
                String w = (x == indent && run.length() == 0) ? word.stripLeading() : word;
                if (w.isEmpty()) continue;
                int ww = font.width(style(s, w));
                if (x + ww > width && x > indent) {
                    flush(ops, font, s, run, runX, y, colour);
                    y += LINE;
                    x = indent;
                    runX = x;
                    w = word.stripLeading();
                    ww = font.width(style(s, w));
                }
                run.append(w);
                x += ww;
            }
            flush(ops, font, s, run, runX, y, colour);
        }
        return y + LINE;
    }

    private static void flush(List<Op> ops, Font font, GuidePage.Span s, StringBuilder run, int x, int y, int colour) {
        if (run.length() == 0) return;
        Component c = style(s, run.toString());
        int c0 = s.link() != null ? LINK : s.code() ? CODE : colour;
        ops.add(new Text(x, y, c, c0));
        if (s.link() != null) ops.add(new Link(x, y - 1, font.width(c), LINE, s.link()));
        run.setLength(0);
    }

    private static Component style(GuidePage.Span s, String text) {
        MutableComponent c = Component.literal(text);
        if (s.bold()) c = c.withStyle(ChatFormatting.BOLD);
        if (s.italic()) c = c.withStyle(ChatFormatting.ITALIC);
        if (s.link() != null) c = c.withStyle(ChatFormatting.UNDERLINE);
        return c;
    }
}
