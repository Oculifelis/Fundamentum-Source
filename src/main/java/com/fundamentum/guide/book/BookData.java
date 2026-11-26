package com.fundamentum.guide.book;

import com.fundamentum.port.FId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Guide books: a bestiary or field guide made of entries, read from client resources. A book is
 * {@code assets/<ns>/fundamentum/books/<id>.json} and its entries are {@code assets/<ns>/fundamentum/books/<id>/<entry>.json}.
 * Every text field is a lang key or plain text (a missing key shows as written). The same file on every version.
 */
public final class BookData {
    private BookData() { }

    public static final String FOLDER = "fundamentum/books";

    public record Category(String id, String title, FId icon) { }

    public record Card(FId icon, String label, String value, boolean warn) { }

    public record Drop(FId item, String label, String chance) { }

    /** A goal on an entry's progress row: {@code kill}, {@code seen}, {@code obtain} or {@code unlock} its target. */
    public record Stamp(String kind, FId target, String label, FId icon, int count) { }

    public record Entry(FId id, String category, String title, String subtitle, FId icon, FId entity, FId unlock, int order,
                        List<String> facts, String flavour, String caption, List<Card> cards, int health, int armor,
                        List<Drop> drops, List<Stamp> stamps) { }

    public record Book(FId id, String title, String subtitle, FId style, FId icon, String intro, List<Category> categories,
                       List<Entry> entries) {
        /** The entries of one category in reading order (all of them for a null category). */
        public List<Entry> in(String category) {
            List<Entry> out = new ArrayList<>();
            for (Entry e : this.entries) if (category == null || category.equals(e.category())) out.add(e);
            return out;
        }
    }

    public static final FId DEFAULT_STYLE = FId.of("fundamentum", "parchment_tome");

    /** One book from its file and its entry files (keyed by entry id); bad entries are skipped. */
    public static Book book(FId id, String json, Map<FId, String> entryFiles) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        List<Category> cats = new ArrayList<>();
        for (JsonElement el : arr(o, "categories")) {
            JsonObject c = el.getAsJsonObject();
            String cid = str(c, "id", null);
            if (cid == null) continue;
            cats.add(new Category(cid, str(c, "title", cid), id(c, "icon")));
        }
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<FId, String> e : entryFiles.entrySet()) {
            entries.add(entry(e.getKey(), JsonParser.parseString(e.getValue()).getAsJsonObject()));
        }
        String firstCat = cats.isEmpty() ? null : cats.get(0).id();
        List<Entry> placed = new ArrayList<>();
        for (Entry e : entries) {
            boolean known = e.category() != null && cats.stream().anyMatch(c -> c.id().equals(e.category()));
            placed.add(known || firstCat == null ? e : withCategory(e, firstCat));
        }
        List<String> order = cats.stream().map(Category::id).toList();
        placed.sort(Comparator.<Entry>comparingInt(e -> order.indexOf(e.category())).thenComparingInt(Entry::order)
            .thenComparing(e -> e.id().toString()));
        FId style = id(o, "style");
        return new Book(id, str(o, "title", id.toString()), str(o, "subtitle", ""), style == null ? DEFAULT_STYLE : style,
            id(o, "icon"), str(o, "intro", ""), List.copyOf(cats), List.copyOf(placed));
    }

    private static Entry withCategory(Entry e, String cat) {
        return new Entry(e.id(), cat, e.title(), e.subtitle(), e.icon(), e.entity(), e.unlock(), e.order(), e.facts(), e.flavour(),
            e.caption(), e.cards(), e.health(), e.armor(), e.drops(), e.stamps());
    }

    static Entry entry(FId id, JsonObject o) {
        List<String> facts = new ArrayList<>();
        for (JsonElement f : arr(o, "facts")) facts.add(f.getAsString());
        List<Card> cards = new ArrayList<>();
        for (JsonElement el : arr(o, "cards")) {
            JsonObject c = el.getAsJsonObject();
            cards.add(new Card(id(c, "icon"), str(c, "label", ""), str(c, "value", ""), c.has("warn") && c.get("warn").getAsBoolean()));
        }
        List<Drop> drops = new ArrayList<>();
        for (JsonElement el : arr(o, "drops")) {
            JsonObject d = el.getAsJsonObject();
            FId item = id(d, "item");
            if (item != null) drops.add(new Drop(item, str(d, "label", null), str(d, "chance", "")));
        }
        FId entity = id(o, "entity");
        List<Stamp> stamps = new ArrayList<>();
        for (JsonElement el : arr(o, "stamps")) {
            JsonObject s = el.getAsJsonObject();
            String kind = str(s, "kind", "kill");
            FId target = id(s, "target");
            if (target == null && (kind.equals("kill") || kind.equals("seen"))) target = entity;
            if (target == null) continue;
            stamps.add(new Stamp(kind, target, str(s, "label", null), id(s, "icon"), s.has("count") ? Math.max(1, s.get("count").getAsInt()) : 1));
        }
        return new Entry(id, str(o, "category", null), str(o, "title", id.path()), str(o, "subtitle", ""), id(o, "icon"), entity,
            id(o, "unlock"), o.has("order") ? o.get("order").getAsInt() : 0, List.copyOf(facts), str(o, "flavour", ""),
            str(o, "caption", null), List.copyOf(cards), o.has("health") ? o.get("health").getAsInt() : 0,
            o.has("armor") ? o.get("armor").getAsInt() : 0, List.copyOf(drops), List.copyOf(stamps));
    }

    static JsonArray arr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    static FId id(JsonObject o, String key) {
        String s = str(o, key, null);
        return s == null ? null : FId.tryOf(s);
    }
}
