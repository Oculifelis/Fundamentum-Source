package com.fundamentum.guide;

import com.fundamentum.port.FId;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads every guide page, chapters.json and guide theme from the client's resource packs, plus loose themes in
 * {@code config/fundamentum/guide_themes/} (mods and packs alike; a later pack
 * replaces a page with the same path). Page {@code assets/<ns>/fundamentum/guide/a/b.md} gets the id {@code ns:a/b}.
 * The same file on every version.
 */
public final class GuideResources {
    private GuideResources() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");

    public static Guides.Library read(ResourceManager resources) {
        Map<FId, String> pages = new LinkedHashMap<>();
        Map<String, String> chapters = new LinkedHashMap<>();
        var found = resources.listResources(Guides.FOLDER, loc -> loc.getPath().endsWith(".md") || loc.getPath().endsWith("/chapters.json"));
        for (var e : found.entrySet()) {
            FId loc = FId.from(e.getKey());
            String rel = loc.path().substring(Guides.FOLDER.length() + 1);
            try (BufferedReader reader = e.getValue().openAsReader()) {
                String text = reader.lines().collect(Collectors.joining("\n"));
                if (rel.equals("chapters.json")) {
                    chapters.put(loc.namespace(), text);
                } else if (rel.endsWith(".md")) {
                    FId id = FId.tryOf(loc.namespace() + ":" + rel.substring(0, rel.length() - 3));
                    if (id != null) pages.put(id, text);
                }
            } catch (IOException ex) {
                LOG.warn("Guide: could not read {}: {}", loc, ex.toString());
            }
        }
        Map<FId, String> themes = new LinkedHashMap<>();
        for (var e : resources.listResources(GuideTheme.FOLDER, loc -> loc.getPath().endsWith(".json")).entrySet()) {
            FId loc = FId.from(e.getKey());
            String rel = loc.path().substring(GuideTheme.FOLDER.length() + 1);
            FId id = FId.tryOf(loc.namespace() + ":" + rel.substring(0, rel.length() - 5));
            if (id == null) continue;
            try (BufferedReader reader = e.getValue().openAsReader()) {
                themes.put(id, reader.lines().collect(Collectors.joining("\n")));
            } catch (IOException ex) {
                LOG.warn("Guide: could not read {}: {}", loc, ex.toString());
            }
        }
        // loose theme files a player drops in config/fundamentum/guide_themes/ (id config:<name>)
        java.nio.file.Path loose = java.nio.file.Path.of("config", "fundamentum", "guide_themes");
        if (java.nio.file.Files.isDirectory(loose)) {
            try (var files = java.nio.file.Files.list(loose)) {
                for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files.sorted()::iterator) {
                    String name = f.getFileName().toString();
                    if (!name.endsWith(".json")) continue;
                    FId id = FId.tryOf("config:" + name.substring(0, name.length() - 5).toLowerCase(java.util.Locale.ROOT).replace(' ', '_'));
                    if (id == null) continue;
                    try {
                        themes.put(id, java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
                    } catch (IOException ex) {
                        LOG.warn("Guide: could not read {}: {}", f, ex.toString());
                    }
                }
            } catch (IOException ex) {
                LOG.warn("Guide: could not list {}: {}", loose, ex.toString());
            }
        }
        return Guides.build(pages, chapters, themes);
    }
}
