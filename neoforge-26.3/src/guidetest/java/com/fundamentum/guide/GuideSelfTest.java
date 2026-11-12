package com.fundamentum.guide;

import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FVanilla;
import com.fundamentum.recipeview.RecipeViews;
import com.fundamentum.recipeview.ViewCategory;
import com.fundamentum.recipeview.ViewRecipe;
import com.fundamentum.unlock.Unlocks;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The guide test, only in {@code gradlew runServer -Pguidetest} (world under run-guidetest). The same file on every
 * version. A dedicated server has no resource packs, so the test reads its pages straight from its own folder
 * (src/guidetest/resources/assets/guidetest/fundamentum/guide) and checks the page parser (front matter, text styles,
 * every embed, problems), chapters and books, search, unlock gating with a real player, and recipe embeds through the
 * recipe viewer. The guide screen itself is client only and not run here. Writes guidetest-result.txt and stops the
 * server. Log lines start with GUIDETEST.
 */
public final class GuideSelfTest {
    private GuideSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final String ROOT = "/assets/guidetest/fundamentum/guide/";
    private static final String[] PAGES = {"basics.md", "furnace.md", "secret.md", "deep/advanced.md", "loose.md", "broken.md"};
    private static boolean failed;
    private static int ticks;

    private record Smelt(String name) { }

    public static void init() {
        if (System.getProperty("fundamentum.guidetest") == null) return;
        ViewCategory<Smelt> smelt = RecipeViews.category("guidetest:smelt");
        smelt.title(Component.literal("Smelt")).size(80, 30);
        int in = smelt.input(4, 6);
        int out = smelt.output(58, 6);
        smelt.recipes(() -> List.of(new Smelt("iron"), new Smelt("gold"))).key(Smelt::name).fill((s, r) -> {
            r.item(in, new ItemStack(s.name().equals("iron") ? Items.RAW_IRON : Items.RAW_GOLD));
            r.item(out, new ItemStack(s.name().equals("iron") ? Items.IRON_INGOT : Items.GOLD_INGOT));
        });
        FLife.serverTick(GuideSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("GUIDETEST {}", line);
        else LOG.error("GUIDETEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks != 40) return;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("GUIDETEST threw", t);
            check(false, "threw " + t);
        }
        Guides.set(Guides.Library.EMPTY);
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("GUIDETEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("guidetest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("GUIDETEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static String read(String name) throws IOException {
        try (InputStream in = GuideSelfTest.class.getResourceAsStream(ROOT + name)) {
            if (in == null) throw new IOException("missing test file " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static FId id(String path) {
        return FId.of("guidetest", path);
    }

    private static List<FId> ids(List<GuidePage> pages) {
        List<FId> out = new ArrayList<>();
        for (GuidePage p : pages) out.add(p.id());
        return out;
    }

    private static List<FId> chapterIds(List<Guides.Chapter> chapters) {
        List<FId> out = new ArrayList<>();
        for (Guides.Chapter c : chapters) out.add(c.id());
        return out;
    }

    private static void run(MinecraftServer server) throws IOException {
        Map<FId, String> sources = new HashMap<>();
        for (String name : PAGES) sources.put(id(name.substring(0, name.length() - 3)), read(name));
        Guides.Library lib = Guides.build(sources, Map.of("guidetest", read("chapters.json")));
        Guides.set(lib);
        check(Guides.library() == lib, "set keeps the library");
        check(lib.pages().size() == PAGES.length, "every page loads, broken ones too (" + lib.pages().size() + ")");
        check(lib.pages().containsKey(id("deep/advanced")), "a page in a sub folder keeps its path in its id");

        // front matter and blocks
        GuidePage basics = lib.pages().get(id("basics"));
        check(basics != null && basics.problems().isEmpty(), "a good page has no problems " + (basics == null ? "" : basics.problems()));
        if (basics == null) return;
        check(basics.title().equals("Basics") && basics.order() == 1, "title and order from front matter");
        check(basics.chapter().equals(id("start")), "a chapter without a namespace takes the page's");
        check(basics.icon().equals(FId.of("minecraft", "book")), "icon");
        check(basics.items().equals(List.of(FId.of("minecraft", "stone"), FId.of("minecraft", "cobblestone"))), "items, minecraft by default");
        List<GuidePage.Block> b = basics.blocks();
        check(b.size() == 10, "block count (" + b.size() + ")");
        if (b.size() == 10) {
            check(b.get(0) instanceof GuidePage.Heading h && h.level() == 1 && GuideParser.plain(h.text()).equals("First Steps"), "heading");
            check(b.get(1) instanceof GuidePage.Paragraph, "lines join into one paragraph");
            check(b.get(2) instanceof GuidePage.ListItem && b.get(3) instanceof GuidePage.ListItem, "- and * bullets");
            check(b.get(4) instanceof GuidePage.Rule, "--- in the body is a divider");
            check(b.get(5) instanceof GuidePage.ItemEmbed e && e.items().size() == 2, "item embed with two items");
            check(b.get(6) instanceof GuidePage.RecipeEmbed r && r.category().equals("guidetest:smelt") && r.key().equals("iron"), "recipe embed");
            check(b.get(7) instanceof GuidePage.MultiblockEmbed m && m.pattern().equals(id("altar")), "multiblock embed in the page's namespace");
            check(b.get(8) instanceof GuidePage.EntityEmbed e && e.type().equals(FId.of("minecraft", "zombie")) && e.scale() == 40, "entity embed with scale");
            check(b.get(9) instanceof GuidePage.ImageEmbed i && i.texture().equals(id("textures/gui/pic.png")) && i.width() == 32 && i.height() == 16, "image embed with size");
            if (b.get(1) instanceof GuidePage.Paragraph p) {
                List<GuidePage.Span> spans = p.text();
                boolean bold = false;
                boolean italic = false;
                boolean code = false;
                FId link = null;
                for (GuidePage.Span s : spans) {
                    if (s.bold() && s.text().equals("common")) bold = true;
                    if (s.italic() && s.text().equals("useful")) italic = true;
                    if (s.code() && s.text().equals("fuel")) code = true;
                    if (s.link() != null && s.text().equals("furnaces")) link = s.link();
                }
                check(bold && italic && code, "bold, italic and code spans");
                check(id("furnace").equals(link), "link span and target");
                check(GuideParser.plain(spans).contains("fuel in one of the furnaces."), "joined text keeps a space");
            }
        }
        GuidePage furnace = lib.pages().get(id("furnace"));
        check(furnace != null && furnace.title().equals("Furnaces"), "title falls back to the first heading");
        GuidePage loose = lib.pages().get(id("loose"));
        check(loose != null && loose.title().equals("Loose Notes") && loose.chapter().equals(id("main")), "no front matter: heading title, main chapter");
        check(loose != null && loose.blocks().get(0) instanceof GuidePage.Heading h && h.level() == 3, "heading level caps at 3");
        check(loose != null && loose.blocks().stream().anyMatch(x -> x instanceof GuidePage.EntityEmbed e && e.scale() == 120), "entity scale is capped");
        check(loose != null && loose.blocks().stream().anyMatch(x -> x instanceof GuidePage.ImageEmbed i && i.width() == 64 && i.height() == 64), "image size defaults to 64");

        // problems
        GuidePage broken = lib.pages().get(id("broken"));
        check(broken != null && broken.problems().size() == 6, "a broken page lists each problem (" + (broken == null ? "" : broken.problems()) + ")");
        check(broken != null && broken.blocks().size() == 1 && broken.title().equals("Broken"), "bad embeds are left out, the rest stays");
        check(lib.problems().contains("guidetest:broken: unknown front matter key colour"), "library problems name the page");
        check(lib.problems().stream().anyMatch(p -> p.startsWith("guidetest:broken: line 6: unknown embed wobble")), "problems give the line");
        GuidePage unclosed = GuideParser.parse(id("x"), "---\ntitle: X\nno end");
        check(unclosed.problems().contains("front matter has no closing ---"), "unclosed front matter");
        List<GuidePage.Span> esc = GuideParser.spans("a \\*b\\* [x](minecraft:y) `c", "guidetest", new ArrayList<>());
        check(GuideParser.plain(esc).equals("a *b* x `c"), "escapes and an unclosed backtick stay literal");
        check(esc.stream().anyMatch(s -> FId.of("minecraft", "y").equals(s.link())), "a link with a namespace keeps it");

        // books and chapters
        Guides.Book book = lib.books().get("guidetest");
        check(book != null && book.title().equals("Guide Test") && FId.of("minecraft", "book").equals(book.icon()), "book title and icon from chapters.json");
        check(book != null && chapterIds(book.chapters()).equals(List.of(id("start"), id("advanced"), id("main"))), "chapters in order, undeclared last");
        check(book != null && book.chapters().get(0).title().equals("Getting Started") && book.chapters().get(2).title().equals("Main"), "chapter titles, made-up one from its id");
        check(book != null && id("advanced_ch").equals(book.chapters().get(1).unlock()), "chapter unlock");
        check(Guides.bookOpenedBy(lib, FId.of("minecraft", "writable_book")) == book, "opener item finds its book");
        check(Guides.bookOpenedBy(lib, FId.of("minecraft", "stone")) == null, "other items open nothing");
        Guides.Library noChapters = Guides.build(Map.of(FId.of("other", "a"), "Hello"), Map.of());
        check(noChapters.books().containsKey("other") && noChapters.books().get("other").title().equals("Other"), "a mod without chapters.json still gets a book");
        Guides.Library badChapters = Guides.build(Map.of(), Map.of("bad", "{ nope"));
        check(badChapters.problems().size() == 1 && badChapters.problems().get(0).startsWith("bad:chapters.json skipped"), "a broken chapters.json is reported");

        // gating
        Predicate<FId> none = x -> false;
        Predicate<FId> all = x -> true;
        check(ids(Guides.pages(lib, id("start"), none)).equals(List.of(id("basics"), id("furnace"))), "locked page hidden, rest in order");
        check(ids(Guides.pages(lib, id("start"), all)).equals(List.of(id("basics"), id("furnace"), id("secret"))), "unlocked page shows");
        check(Guides.pages(lib, id("advanced"), none).isEmpty() && Guides.pages(lib, id("advanced"), all).size() == 1, "a locked chapter hides its pages");
        check(chapterIds(Guides.chapters(lib, "guidetest", none)).equals(List.of(id("start"), id("main"))), "locked chapter left out of the list");
        check(Guides.books(lib, none).size() == 1, "book listed while it has a visible page");
        check(Guides.books(Guides.build(Map.of(id("s"), "---\nunlock: x\n---\nHi"), Map.of()), none).isEmpty(), "a book with only locked pages is hidden");
        check(Guides.search(lib, "secret", none).isEmpty() && Guides.search(lib, "secret", all).size() == 1, "search skips locked pages");

        ServerPlayer p = FVanilla.testPlayer(server.overworld(), "GuideReader");
        Predicate<FId> mine = x -> Unlocks.has(p, x);
        GuidePage secret = lib.pages().get(id("secret"));
        check(secret != null && !Guides.visible(lib, secret, mine), "a new player can't see the locked page");
        Unlocks.grant(p, id("secret_page"));
        check(secret != null && Guides.visible(lib, secret, mine), "granting the unlock shows it");
        Unlocks.revoke(p, id("secret_page"));
        check(secret != null && !Guides.visible(lib, secret, mine), "revoking hides it again");
        Unlocks.grant(p, id("advanced_ch"));
        check(Guides.pages(lib, id("advanced"), mine).size() == 1, "chapter unlock shows its pages");
        Unlocks.revoke(p, id("advanced_ch"));

        // search and item pages
        List<GuidePage> found = Guides.search(lib, "furnace", none);
        check(!found.isEmpty() && found.get(0).id().equals(id("furnace")), "title matches come first");
        check(ids(found).contains(id("basics")), "text matches follow");
        check(ids(Guides.search(lib, "cobblestone", none)).equals(List.of(id("basics"))), "item ids are searchable");
        check(ids(Guides.search(lib, "STONE pickaxe", none)).equals(List.of(id("basics"))), "every word must match, any case");
        check(Guides.search(lib, "  ", none).isEmpty() && Guides.search(lib, "zzzz", none).isEmpty(), "blank or unknown search finds nothing");
        GuidePage forStone = Guides.pageFor(lib, FId.of("minecraft", "stone"), none);
        check(forStone != null && forStone.id().equals(id("basics")), "item finds its page");
        check(Guides.pageFor(lib, FId.of("minecraft", "diamond"), none) == null, "item without a page finds nothing");

        // recipes
        ViewRecipe iron = Guides.resolveRecipe("guidetest:smelt", "iron");
        check(iron != null && iron.id().equals("guidetest:smelt/iron") && iron.items(1).get(0).is(Items.IRON_INGOT), "recipe embed finds its recipe");
        check(Guides.resolveRecipe("guidetest:smelt", "copper") == null, "unknown recipe key gives nothing");
        check(Guides.resolveRecipe("guidetest:nope", "iron") == null, "unknown category gives nothing");

        // opening does nothing on a server
        try {
            Guides.open(id("basics"));
            check(true, "open is harmless on a server");
        } catch (RuntimeException e) {
            check(false, "open threw " + e);
        }
    }
}
