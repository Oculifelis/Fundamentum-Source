package com.fundamentum.guide.client;

import com.fundamentum.guide.GuidePage;
import com.fundamentum.guide.GuideTheme;
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
 * (links get a click box), items in slots, pictures, entities, recipe grids and rules, coloured by the book's
 * {@link GuideTheme.Page}. The per-version {@code GuideCanvas} draws them. The same file on every version.
 */
public final class GuideLayout {
    private GuideLayout() { }

    public static final int MISSING = 0xFFE03030;
    private static final int LINE = 10;

    public sealed interface Op permits Text, ItemOp, Image, Entity, Box, Link, Blocks, Slot, Well, Arrow, Rule { }

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

    /** An item slot, 18 (or 26 for a recipe's output) square, drawn like vanilla's in the theme's well colours. */
    public record Slot(int x, int y, int size) implements Op { }

    /** A sunken box (behind entities and multiblocks). */
    public record Well(int x, int y, int w, int h) implements Op { }

    /** The crafting progress arrow. */
    public record Arrow(int x, int y) implements Op { }

    /** A rule: a dark line over a light one (one line when both are the same colour). */
    public record Rule(int x, int y, int w, int dark, int light) implements Op { }

    public record Result(List<Op> ops, int height) { }

    /** Lays out a whole page, its title first, in the default theme's page colours. */
    public static Result page(GuidePage page, Font font, int width) {
        return page(page, font, width, GuideTheme.DEFAULT.page());
    }

    /** Lays out a whole page, its title first. */
    public static Result page(GuidePage page, Font font, int width, GuideTheme.Page pal) {
        List<Op> ops = new ArrayList<>();
        int y = 0;
        y = wrap(ops, font, List.of(new GuidePage.Span(page.title(), true, false, false, null)), 0, y, width, pal.heading(), pal);
        ops.add(new Rule(0, y, width, pal.ruleDark(), pal.ruleLight()));
        y += 6;
        for (GuidePage.Block b : page.blocks()) y = block(ops, font, b, y, width, pal) + 4;
        return new Result(List.copyOf(ops), y);
    }

    /** Lays out a page's blocks without its title (the screen shows the title in its own header). */
    public static Result body(GuidePage page, Font font, int width, GuideTheme.Page pal) {
        List<Op> ops = new ArrayList<>();
        int y = 0;
        for (GuidePage.Block b : page.blocks()) y = block(ops, font, b, y, width, pal) + 4;
        return new Result(List.copyOf(ops), y);
    }

    /** Lays out plain lines (for the front pages) in the default theme's page colours. */
    public static Result lines(List<Component> lines, Font font, int width) {
        return lines(lines, font, width, GuideTheme.DEFAULT.page());
    }

    /** Lays out plain lines (for the front pages); bold lines take the heading colour. */
    public static Result lines(List<Component> lines, Font font, int width, GuideTheme.Page pal) {
        List<Op> ops = new ArrayList<>();
        int y = 0;
        for (Component c : lines) {
            int colour = c.getStyle().isBold() ? pal.heading() : pal.text();
            for (var seq : font.split(c, width)) {
                ops.add(new Text(0, y, c.getStyle().isBold() ? toComponent(seq).copy().withStyle(ChatFormatting.BOLD) : toComponent(seq), colour));
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

    private static int block(List<Op> ops, Font font, GuidePage.Block b, int y, int width, GuideTheme.Page pal) {
        if (b instanceof GuidePage.Heading h) {
            return wrap(ops, font, bold(h.text()), 0, y + (h.level() == 1 ? 4 : 2), width, pal.heading(), pal);
        }
        if (b instanceof GuidePage.Paragraph p) return wrap(ops, font, p.text(), 0, y, width, pal.text(), pal);
        if (b instanceof GuidePage.ListItem l) {
            ops.add(new Box(1, y + 3, 2, 2, pal.text()));
            return wrap(ops, font, l.text(), 7, y, width, pal.text(), pal);
        }
        if (b instanceof GuidePage.Rule) {
            ops.add(new Rule(0, y + 3, width, pal.ruleDark(), pal.ruleLight()));
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
                ops.add(new Slot(0, y, 18));
                ops.add(new ItemOp(1, y + 1, List.of(stack)));
                ops.add(new Text(22, y + 5, stack.getHoverName(), pal.text()));
                y += 20;
            }
            return y;
        }
        if (b instanceof GuidePage.RecipeEmbed r) return recipe(ops, font, r, y, width, pal);
        if (b instanceof GuidePage.MultiblockEmbed m) return multiblock(ops, font, m, y, width, pal);
        if (b instanceof GuidePage.EntityEmbed e) {
            int h = e.scale() * 2 + 10;
            int w = Math.min(width, Math.max(40, e.scale() * 2));
            ops.add(new Well((width - w) / 2, y, w, h));
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

    private static int recipe(List<Op> ops, Font font, GuidePage.RecipeEmbed r, int y, int width, GuideTheme.Page pal) {
        ViewRecipe recipe = Guides.resolveRecipe(r.category(), r.key());
        if (recipe == null) {
            ops.add(new Text(0, y, Component.literal("Missing recipe " + r.category() + " " + r.key()), MISSING));
            return y + LINE;
        }
        ViewCategory<?> cat = recipe.category();
        ops.add(new Text(0, y, cat.title(), pal.muted()));
        y += LINE + 4;
        int w = cat.width();
        int x0 = 1;
        ViewCategory.Arrow arrow = cat.arrow();
        for (ViewSlot slot : cat.slots()) {
            if (slot.kind() != ViewSlot.Kind.ITEM) continue;
            // a slot right of the arrow is an output: vanilla draws those bigger
            boolean out = arrow != null && slot.x() > arrow.x();
            if (out) ops.add(new Slot(x0 + slot.x() - 5, y + slot.y() - 5, 26));
            else ops.add(new Slot(x0 + slot.x() - 1, y + slot.y() - 1, 18));
            List<ItemStack> stacks = recipe.items(slot.index());
            if (!stacks.isEmpty()) ops.add(new ItemOp(x0 + slot.x(), y + slot.y(), stacks));
        }
        if (arrow != null) ops.add(new Arrow(x0 + arrow.x(), y + arrow.y()));
        for (ViewRecipe.Text t : recipe.text()) {
            int tx = t.x() < 0 ? (w - font.width(t.text())) / 2 : t.x();
            ops.add(new Text(x0 + tx, y + t.y(), t.text(), 0xFF000000 | t.colour()));
        }
        for (ViewRecipe.Blocks b : recipe.blocks()) ops.add(new Blocks(x0 + b.x(), y + b.y(), b.w(), b.h(), b.pattern()));
        return y + cat.height() + 6;
    }

    private static int multiblock(List<Op> ops, Font font, GuidePage.MultiblockEmbed m, int y, int width, GuideTheme.Page pal) {
        MultiblockPattern p = Multiblocks.pattern(m.pattern());
        if (p == null) {
            ops.add(new Text(0, y, Component.literal("Missing multiblock " + m.pattern()), MISSING));
            return y + LINE;
        }
        ops.add(new Text(0, y, MultiblockViews.title(m.pattern(), p).copy().append(" (" + p.width() + " x " + p.height() + " x " + p.depth() + ")"), pal.muted()));
        y += LINE + 2;
        MultiblockScene.Scene scene = MultiblockScene.of(p);
        if (scene != null) {
            int bw = Math.min(width - 8, 160);
            int bh = Math.min(120, Math.max(64, scene.size() * 22));
            int bx = (width - bw) / 2;
            ops.add(new Well(bx - 4, y, bw + 8, bh + 8));
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
            ops.add(new Slot(x, y, 18));
            ops.add(new ItemOp(x + 1, y + 1, List.of(s)));
            ops.add(new Text(x + 21, y + 5, Component.literal("x" + e.getValue()), pal.text()));
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
    static int wrap(List<Op> ops, Font font, List<GuidePage.Span> spans, int indent, int y, int width, int colour, GuideTheme.Page pal) {
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
                    flush(ops, font, s, run, runX, y, colour, pal);
                    y += LINE;
                    x = indent;
                    runX = x;
                    w = word.stripLeading();
                    ww = font.width(style(s, w));
                }
                run.append(w);
                x += ww;
            }
            flush(ops, font, s, run, runX, y, colour, pal);
        }
        return y + LINE;
    }

    private static void flush(List<Op> ops, Font font, GuidePage.Span s, StringBuilder run, int x, int y, int colour, GuideTheme.Page pal) {
        if (run.length() == 0) return;
        Component c = style(s, run.toString());
        int c0 = s.link() != null ? pal.link() : s.code() ? pal.code() : colour;
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
