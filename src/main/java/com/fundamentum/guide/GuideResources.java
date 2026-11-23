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
 * Reads every guide page and chapters.json from the client's resource packs (mods and packs alike; a later pack
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
        return Guides.build(pages, chapters);
    }
}
