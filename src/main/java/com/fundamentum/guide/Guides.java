package com.fundamentum.guide;

import com.fundamentum.port.FId;
import com.fundamentum.recipeview.RecipeViews;
import com.fundamentum.recipeview.ViewCategory;
import com.fundamentum.recipeview.ViewRecipe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The in-game guide: one book for every mod. Each mod puts pages in {@code assets/<ns>/fundamentum/guide/} and may add
 * a {@code chapters.json} there that names the mod's guide, its chapters (title, icon, order, unlock) and the items that
 * open it. Pages and chapters can hide behind an unlock (com.fundamentum.unlock). The client reads them on resource
 * reload; this class holds the result and answers searches. Reference pages only, no quests. The same file on every
 * version.
 */
public final class Guides {
    private Guides() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    public static final String FOLDER = "fundamentum/guide";

    /** A chapter: pages with the same {@code chapter:} id, shown in {@code order} then title order. */
    public record Chapter(FId id, String title, FId icon, int order, FId unlock) { }

    /** One mod's guide: its title, icon, the items that open it, and its chapters. */
    public record Book(String namespace, String title, FId icon, List<FId> openers, List<Chapter> chapters) { }

    /** Everything loaded: pages by id, books by namespace, and what could not be read. */
    public record Library(Map<FId, GuidePage> pages, Map<String, Book> books, List<String> problems) {
        public static final Library EMPTY = new Library(Map.of(), Map.of(), List.of());

        public Chapter chapter(FId id) {
            Book b = this.books.get(id.namespace());
            if (b == null) return null;
            for (Chapter c : b.chapters()) if (c.id().equals(id)) return c;
            return null;
        }
    }

    private static volatile Library library = Library.EMPTY;
    private static Consumer<FId> opener = id -> { };
    private static boolean initialised;

    /** Nothing to register on the server; kept so every system starts the same way. */
    public static synchronized void init() {
        if (initialised) return;
        initialised = true;
    }

    public static Library library() {
        return library;
    }

    public static void set(Library lib) {
        library = lib;
        if (!lib.problems().isEmpty()) lib.problems().forEach(p -> LOG.warn("Guide: {}", p));
        LOG.info("Guide loaded {} pages in {} books", lib.pages().size(), lib.books().size());
    }

    /** The client sets this to open the guide screen at a page (or at a book's front with {@code ns:}). */
    public static void setOpener(Consumer<FId> open) {
        opener = open;
    }

    /** Opens the guide at {@code page} on the client; does nothing on a server. */
    public static void open(FId page) {
        opener.accept(page);
    }

    // ---- building

    /**
     * Builds the library from page sources (page id to file text) and chapter files (namespace to chapters.json text).
     * A chapter that pages name but no file declares is made with the chapter's path as its title.
     */
    public static Library build(Map<FId, String> pageSources, Map<String, String> chapterFiles) {
        List<String> problems = new ArrayList<>();
        Map<FId, GuidePage> pages = new TreeMap<>();
        for (Map.Entry<FId, String> e : new TreeMap<>(pageSources).entrySet()) {
            GuidePage page = GuideParser.parse(e.getKey(), e.getValue());
            for (String p : page.problems()) problems.add(e.getKey() + ": " + p);
            pages.put(page.id(), page);
        }
        Map<String, Book> books = new TreeMap<>();
        Map<String, Map<FId, Chapter>> chapters = new TreeMap<>();
        Map<String, String[]> heads = new TreeMap<>();
        Map<String, List<FId>> openers = new TreeMap<>();
        for (Map.Entry<String, String> e : new TreeMap<>(chapterFiles).entrySet()) {
            String ns = e.getKey();
            try {
                JsonObject json = JsonParser.parseString(e.getValue()).getAsJsonObject();
                heads.put(ns, new String[] {str(json, "title", ns), str(json, "icon", null)});
                List<FId> open = new ArrayList<>();
                if (json.has("openers")) {
                    for (JsonElement x : json.getAsJsonArray("openers")) {
                        FId id = GuideParser.id(x.getAsString(), "minecraft", "opener", problems);
                        if (id != null) open.add(id);
                    }
                }
                openers.put(ns, open);
                Map<FId, Chapter> map = chapters.computeIfAbsent(ns, k -> new LinkedHashMap<>());
                if (json.has("chapters")) {
                    JsonArray arr = json.getAsJsonArray("chapters");
                    int n = 0;
                    for (JsonElement x : arr) {
                        JsonObject c = x.getAsJsonObject();
                        FId id = GuideParser.id(str(c, "id", "main"), ns, "chapter", problems);
                        if (id == null) continue;
                        String icon = str(c, "icon", null);
                        String unlock = str(c, "unlock", null);
                        map.put(id, new Chapter(id, str(c, "title", id.path()),
                            icon == null ? null : GuideParser.id(icon, "minecraft", "icon", problems),
                            c.has("order") ? c.get("order").getAsInt() : n,
                            unlock == null ? null : GuideParser.id(unlock, ns, "unlock", problems)));
                        n++;
                    }
                }
            } catch (Exception ex) {
                problems.add(ns + ":chapters.json skipped: " + ex.getMessage());
            }
        }
        for (GuidePage p : pages.values()) {
            Map<FId, Chapter> map = chapters.computeIfAbsent(p.chapter().namespace(), k -> new LinkedHashMap<>());
            if (!map.containsKey(p.chapter())) {
                map.put(p.chapter(), new Chapter(p.chapter(), titleCase(p.chapter().path()), null, 1000, null));
            }
        }
        for (Map.Entry<String, Map<FId, Chapter>> e : chapters.entrySet()) {
            String ns = e.getKey();
            String[] head = heads.getOrDefault(ns, new String[] {titleCase(ns), null});
            List<Chapter> list = new ArrayList<>(e.getValue().values());
            list.sort(Comparator.comparingInt(Chapter::order).thenComparing(Chapter::title));
            books.put(ns, new Book(ns, head[0], head[1] == null ? null : GuideParser.id(head[1], "minecraft", "icon", problems),
                List.copyOf(openers.getOrDefault(ns, List.of())), List.copyOf(list)));
        }
        return new Library(java.util.Collections.unmodifiableMap(pages), java.util.Collections.unmodifiableMap(books), List.copyOf(problems));
    }

    private static String str(JsonObject json, String key, String def) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : def;
    }

    private static String titleCase(String path) {
        String s = path.replace('_', ' ').replace('/', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- gating and lookups

    /** Whether the chapter shows: no unlock, or the unlock is held. */
    public static boolean visible(Library lib, Chapter chapter, Predicate<FId> unlocked) {
        return chapter == null || chapter.unlock() == null || unlocked.test(chapter.unlock());
    }

    /** Whether the page shows: its own unlock and its chapter's are both held (or absent). */
    public static boolean visible(Library lib, GuidePage page, Predicate<FId> unlocked) {
        if (page.unlock() != null && !unlocked.test(page.unlock())) return false;
        return visible(lib, lib.chapter(page.chapter()), unlocked);
    }

    /** The books that have at least one visible page, by namespace. */
    public static List<Book> books(Library lib, Predicate<FId> unlocked) {
        List<Book> out = new ArrayList<>();
        for (Book b : lib.books().values()) {
            boolean any = false;
            for (Chapter c : b.chapters()) {
                if (!pages(lib, c.id(), unlocked).isEmpty()) {
                    any = true;
                    break;
                }
            }
            if (any) out.add(b);
        }
        return out;
    }

    /** A book's visible chapters with at least one visible page, in order. */
    public static List<Chapter> chapters(Library lib, String namespace, Predicate<FId> unlocked) {
        Book b = lib.books().get(namespace);
        if (b == null) return List.of();
        List<Chapter> out = new ArrayList<>();
        for (Chapter c : b.chapters()) if (visible(lib, c, unlocked) && !pages(lib, c.id(), unlocked).isEmpty()) out.add(c);
        return out;
    }

    /** A chapter's visible pages, by order then title. */
    public static List<GuidePage> pages(Library lib, FId chapter, Predicate<FId> unlocked) {
        List<GuidePage> out = new ArrayList<>();
        for (GuidePage p : lib.pages().values()) if (p.chapter().equals(chapter) && visible(lib, p, unlocked)) out.add(p);
        out.sort(Comparator.comparingInt(GuidePage::order).thenComparing(GuidePage::title).thenComparing(GuidePage::id));
        return out;
    }

    /** The visible page that explains this item, or null. */
    public static GuidePage pageFor(Library lib, FId item, Predicate<FId> unlocked) {
        for (GuidePage p : lib.pages().values()) if (p.items().contains(item) && visible(lib, p, unlocked)) return p;
        return null;
    }

    /** The book this item opens (named in a chapters.json {@code openers} list), or null. */
    public static Book bookOpenedBy(Library lib, FId item) {
        for (Book b : lib.books().values()) if (b.openers().contains(item)) return b;
        return null;
    }

    /**
     * Visible pages matching every word of {@code query} in their title, text or item ids. Title matches come first,
     * then the rest, each by title.
     */
    public static List<GuidePage> search(Library lib, String query, Predicate<FId> unlocked) {
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<GuidePage> title = new ArrayList<>();
        List<GuidePage> body = new ArrayList<>();
        if (query.isBlank()) return List.of();
        for (GuidePage p : lib.pages().values()) {
            if (!visible(lib, p, unlocked)) continue;
            String t = p.title().toLowerCase(Locale.ROOT);
            String all = p.searchText();
            boolean inTitle = true;
            boolean inAll = true;
            for (String w : words) {
                if (!t.contains(w)) inTitle = false;
                if (!all.contains(w)) inAll = false;
            }
            if (inTitle) title.add(p);
            else if (inAll) body.add(p);
        }
        Comparator<GuidePage> byTitle = Comparator.comparing(GuidePage::title).thenComparing(GuidePage::id);
        title.sort(byTitle);
        body.sort(byTitle);
        List<GuidePage> out = new ArrayList<>(title);
        out.addAll(body);
        return out;
    }

    /** The recipe a page embeds: recipe {@code <category>/<key>} from the recipe viewer, or null when it doesn't exist. */
    public static ViewRecipe resolveRecipe(String category, String key) {
        ViewCategory<?> cat = RecipeViews.find(category);
        if (cat == null) return null;
        String id = category + "/" + key;
        try {
            for (ViewRecipe r : cat.build()) if (r.id().equals(id)) return r;
        } catch (RuntimeException e) {
            LOG.warn("Guide: recipe category {} failed to build: {}", category, e.toString());
        }
        return null;
    }
}
