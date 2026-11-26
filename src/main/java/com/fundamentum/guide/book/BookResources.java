package com.fundamentum.guide.book;

import com.fundamentum.port.FId;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads every guide book, entry and book style from the client's resource packs. The same file on every version. */
public final class BookResources {
    private BookResources() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");

    public record Library(Map<FId, BookData.Book> books, Map<FId, BookStyle> styles) {
        public static final Library EMPTY = new Library(Map.of(), Map.of());

        public BookStyle style(BookData.Book book) {
            BookStyle s = this.styles.get(book.style());
            return s != null ? s : this.styles.get(BookData.DEFAULT_STYLE);
        }
    }

    public static Library read(ResourceManager resources) {
        Map<FId, String> bookFiles = new LinkedHashMap<>();
        Map<FId, Map<FId, String>> entryFiles = new LinkedHashMap<>();
        for (var e : resources.listResources(BookData.FOLDER, loc -> loc.getPath().endsWith(".json")).entrySet()) {
            FId loc = FId.from(e.getKey());
            String rel = loc.path().substring(BookData.FOLDER.length() + 1);
            rel = rel.substring(0, rel.length() - 5);
            String text = text(loc, e.getValue());
            if (text == null) continue;
            int slash = rel.indexOf('/');
            if (slash < 0) {
                FId id = FId.tryOf(loc.namespace() + ":" + rel);
                if (id != null) bookFiles.put(id, text);
            } else {
                FId book = FId.tryOf(loc.namespace() + ":" + rel.substring(0, slash));
                FId entry = FId.tryOf(loc.namespace() + ":" + rel);
                if (book != null && entry != null) entryFiles.computeIfAbsent(book, k -> new LinkedHashMap<>()).put(entry, text);
            }
        }
        Map<FId, BookData.Book> books = new LinkedHashMap<>();
        for (var e : bookFiles.entrySet()) {
            try {
                books.put(e.getKey(), BookData.book(e.getKey(), e.getValue(), entryFiles.getOrDefault(e.getKey(), Map.of())));
            } catch (RuntimeException ex) {
                LOG.warn("Guide book {}: {}", e.getKey(), ex.toString());
            }
        }
        Map<FId, BookStyle> styles = new LinkedHashMap<>();
        for (var e : resources.listResources(BookStyle.FOLDER, loc -> loc.getPath().endsWith(".json")).entrySet()) {
            FId loc = FId.from(e.getKey());
            String rel = loc.path().substring(BookStyle.FOLDER.length() + 1);
            FId id = FId.tryOf(loc.namespace() + ":" + rel.substring(0, rel.length() - 5));
            String text = text(loc, e.getValue());
            if (id == null || text == null) continue;
            try {
                styles.put(id, BookStyle.parse(id, text));
            } catch (RuntimeException ex) {
                LOG.warn("Book style {}: {}", id, ex.toString());
            }
        }
        return new Library(java.util.Collections.unmodifiableMap(books), java.util.Collections.unmodifiableMap(styles));
    }

    private static String text(FId loc, net.minecraft.server.packs.resources.Resource res) {
        try (BufferedReader reader = res.openAsReader()) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException ex) {
            LOG.warn("Guide book: could not read {}: {}", loc, ex.toString());
            return null;
        }
    }
}
