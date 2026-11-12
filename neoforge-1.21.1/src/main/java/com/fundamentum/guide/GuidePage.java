package com.fundamentum.guide;

import com.fundamentum.port.FId;
import java.util.List;

/**
 * One guide page from {@code assets/<ns>/fundamentum/guide/<path>.md}: its front matter (title, chapter, icon, the items
 * it explains, an unlock that hides it, its order in the chapter) and its body as blocks. {@code problems} lists what
 * the parser could not read; the page still loads. The same file on every version.
 */
public record GuidePage(FId id, String title, FId chapter, FId icon, List<FId> items, FId unlock, int order,
                        List<Block> blocks, List<String> problems) {

    /** A run of text with its style; {@code link} is a page id (or null). */
    public record Span(String text, boolean bold, boolean italic, boolean code, FId link) { }

    /** One piece of a page body. */
    public sealed interface Block permits Heading, Paragraph, ListItem, ItemEmbed, RecipeEmbed, MultiblockEmbed, EntityEmbed, ImageEmbed, Rule { }

    /** {@code #}, {@code ##} or {@code ###}. */
    public record Heading(int level, List<Span> text) implements Block { }

    public record Paragraph(List<Span> text) implements Block { }

    /** A {@code -} bullet. */
    public record ListItem(List<Span> text) implements Block { }

    /** {@code {{item a:b c:d}}}: one or more items shown with their names. */
    public record ItemEmbed(List<FId> items) implements Block { }

    /** {@code {{recipe <category> <key>}}}: a recipe from a recipe viewer category. */
    public record RecipeEmbed(String category, String key) implements Block { }

    /** {@code {{multiblock <id>}}}: a multiblock pattern's preview and parts list. */
    public record MultiblockEmbed(FId pattern) implements Block { }

    /** {@code {{entity <type> scale=N}}}: a living entity drawn facing the mouse. */
    public record EntityEmbed(FId type, int scale) implements Block { }

    /** {@code {{image <ns:textures/x.png> WxH}}}: a picture, drawn at W by H (the whole texture). */
    public record ImageEmbed(FId texture, int width, int height) implements Block { }

    /** {@code ---} in the body: a divider. */
    public record Rule() implements Block { }

    /** The text of a heading, paragraph or bullet; empty for other blocks. */
    public static List<Span> spans(Block block) {
        if (block instanceof Heading h) return h.text();
        if (block instanceof Paragraph p) return p.text();
        if (block instanceof ListItem l) return l.text();
        return List.of();
    }

    /** All the body text, lower case, for search. */
    public String searchText() {
        StringBuilder b = new StringBuilder(this.title.toLowerCase(java.util.Locale.ROOT)).append('\n');
        for (Block block : this.blocks) {
            for (Span s : spans(block)) b.append(s.text().toLowerCase(java.util.Locale.ROOT));
            b.append('\n');
        }
        for (FId item : this.items) b.append(item).append('\n');
        return b.toString();
    }
}
