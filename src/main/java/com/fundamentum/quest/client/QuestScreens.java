package com.fundamentum.quest.client;

import com.fundamentum.port.FId;
import com.fundamentum.quest.QuestSync;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Quest screens from resource packs, {@code assets/<ns>/fundamentum/quest_screens/<name>.json}:
 * <pre>{
 *   "layout": "book",                      // book, tabbed (book with group tabs) or list (plain panel)
 *   "title": "Quest Journal",              // text or a lang key
 *   "book_style": "fundamentum:parchment_tome",
 *   "groups": [{"id": "main", "title": "Main", "icon": "minecraft:compass"}],
 *   "colors": {"ink": "3a2414"}            // overrides the book style's colours
 * }</pre>
 * Quests and quest-giver types pick a screen with {@code "screen"}; anything else opens {@code fundamentum:journal}.
 * The same file on every version.
 */
public final class QuestScreens {
    private QuestScreens() { }

    public static final String FOLDER = "fundamentum/quest_screens";
    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");

    public record Group(String id, String title, FId icon) { }

    public record Def(FId id, String layout, String title, FId bookStyle, List<Group> groups, Map<String, Integer> colors) { }

    private static final Def FALLBACK = new Def(QuestSync.JOURNAL, "book", "fundamentum.quests.title",
        FId.of("fundamentum", "parchment_tome"), List.of(), Map.of());

    private static volatile Map<FId, Def> screens = Map.of();

    /** The screen {@code id}, else the journal, else a plain book. */
    public static Def get(FId id) {
        Def d = id == null ? null : screens.get(id);
        if (d == null) d = screens.get(QuestSync.JOURNAL);
        return d != null ? d : FALLBACK;
    }

    public static void load(ResourceManager resources) {
        Map<FId, Def> out = new LinkedHashMap<>();
        for (var e : resources.listResources(FOLDER, loc -> loc.getPath().endsWith(".json")).entrySet()) {
            FId loc = FId.from(e.getKey());
            String rel = loc.path().substring(FOLDER.length() + 1);
            FId id = FId.tryOf(loc.namespace() + ":" + rel.substring(0, rel.length() - 5));
            if (id == null) continue;
            try (BufferedReader reader = e.getValue().openAsReader()) {
                out.put(id, parse(id, reader.lines().collect(Collectors.joining("\n"))));
            } catch (IOException | RuntimeException ex) {
                LOG.warn("Quest screen {}: {}", id, ex.toString());
            }
        }
        screens = Map.copyOf(out);
    }

    static Def parse(FId id, String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String layout = o.has("layout") ? o.get("layout").getAsString() : "book";
        if (!layout.equals("book") && !layout.equals("tabbed") && !layout.equals("list")) layout = "book";
        String title = o.has("title") ? o.get("title").getAsString() : FALLBACK.title();
        FId style = o.has("book_style") ? FId.tryOf(o.get("book_style").getAsString()) : null;
        List<Group> groups = new ArrayList<>();
        if (o.has("groups")) {
            for (JsonElement g : o.getAsJsonArray("groups")) {
                JsonObject go = g.getAsJsonObject();
                String gid = go.get("id").getAsString();
                groups.add(new Group(gid, go.has("title") ? go.get("title").getAsString() : gid,
                    go.has("icon") ? FId.tryOf(go.get("icon").getAsString()) : null));
            }
        }
        Map<String, Integer> colors = new LinkedHashMap<>();
        if (o.has("colors")) {
            for (var c : o.getAsJsonObject("colors").entrySet()) colors.put(c.getKey(), Integer.parseInt(c.getValue().getAsString(), 16));
        }
        return new Def(id, layout, title, style == null ? FALLBACK.bookStyle() : style, List.copyOf(groups), Map.copyOf(colors));
    }
}
