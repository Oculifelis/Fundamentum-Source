package com.fundamentum.guide.client;

import com.fundamentum.guide.book.BookData;
import com.fundamentum.guide.book.BookResources;
import com.fundamentum.port.FId;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared bestiary: one guide book, {@code fundamentum:bestiary}, filled by itself with a stat sheet page for every
 * living mob of each mod that turns it on. A mod turns it on with {@code assets/<modid>/fundamentum/bestiary/<any>.json}:
 * <pre>
 * { "title": "bestiary.ostentum", "icon": "ostentum:bottled_dragonling",
 *   "entities": [ ... ],             (optional: every living mob of the namespace when left out)
 *   "exclude": [ "ostentum:puff" ],
 *   "sources": { "ostentum:dragonling": "ostentum:bottled_dragonling" },   (the item that summons it)
 *   "biomes":  { "mod:mob": [ "minecraft:plains" ] },
 *   "drops":   { "mod:mob": [ "minecraft:bone" ] },
 *   "zoom":    { "mod:mob": 0.5 } }   (portrait size, for a model much bigger or smaller than its hitbox)
 * </pre>
 * The lore line is the lang key {@code bestiary.<modid>.<mob>}. Each page stays locked (??? in the contents) until the
 * player has seen that mob. Stats come from the mob's default attributes when the page is drawn. Client only.
 */
public final class Bestiary {
    private Bestiary() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    public static final FId BOOK = FId.of("fundamentum", "bestiary");
    public static final FId STYLE = FId.of("fundamentum", "bestiary");
    public static final String FOLDER = "fundamentum/bestiary";

    /** What a stat sheet needs beyond the book entry. */
    public record Sheet(FId entity, String mod, FId source, List<FId> drops, List<FId> biomes, float zoom) { }

    /** One stat row: a lang key (with fallback) and its value. */
    public record Row(String key, String fallback, String value) { }

    private static Map<FId, Sheet> sheets = Map.of();
    private static final Map<FId, List<Row>> ROWS = new HashMap<>();

    /** The library with the bestiary book added (or left out when no mod turns it on). */
    public static BookResources.Library add(BookResources.Library lib, ResourceManager resources) {
        Map<String, JsonObject> mods = new LinkedHashMap<>();
        for (var e : resources.listResources(FOLDER, loc -> loc.getPath().endsWith(".json")).entrySet()) {
            FId loc = FId.from(e.getKey());
            try (BufferedReader reader = e.getValue().openAsReader()) {
                JsonObject o = JsonParser.parseString(reader.lines().collect(Collectors.joining("\n"))).getAsJsonObject();
                JsonObject old = mods.get(loc.namespace());
                if (old == null) mods.put(loc.namespace(), o);
                else for (var kv : o.entrySet()) old.add(kv.getKey(), kv.getValue());
            } catch (Exception ex) {
                LOG.warn("Bestiary {}: {}", loc, ex.toString());
            }
        }
        ROWS.clear();
        if (mods.isEmpty()) {
            sheets = Map.of();
            return lib;
        }
        List<BookData.Category> categories = new ArrayList<>();
        List<BookData.Entry> entries = new ArrayList<>();
        Map<FId, Sheet> made = new HashMap<>();
        FId bookIcon = null;
        for (var m : mods.entrySet()) {
            String ns = m.getKey();
            JsonObject o = m.getValue();
            String tabTitle = str(o, "title", "bestiary." + ns);
            FId tabIcon = id(o, "icon");
            Set<FId> exclude = new HashSet<>(ids(o.get("exclude")));
            Map<FId, FId> sources = new HashMap<>();
            if (o.has("sources")) for (var kv : o.getAsJsonObject("sources").entrySet()) {
                FId k = FId.tryOf(kv.getKey());
                FId v = FId.tryOf(kv.getValue().getAsString());
                if (k != null && v != null) sources.put(k, v);
            }
            List<FId> list = new ArrayList<>();
            if (o.has("entities")) list.addAll(ids(o.get("entities")));
            else for (var key : BuiltInRegistries.ENTITY_TYPE.keySet()) {
                FId id = FId.from(key);
                if (id.namespace().equals(ns)) list.add(id);
            }
            int order = 0;
            for (FId mob : list) {
                if (exclude.contains(mob)) continue;
                EntityType<?> type = FVanilla.entityType(mob);
                if (type == null || attributes(type) == null) continue;
                FId source = sources.get(mob);
                FId icon = source != null ? source : egg(mob);
                if (icon == null) icon = tabIcon;
                FId entryId = FId.of("fundamentum", "bestiary/" + ns + "/" + mob.path());
                List<BookData.Stamp> stamps = List.of(
                    new BookData.Stamp("seen", mob, "book.fundamentum.bestiary.seen", icon, 1),
                    new BookData.Stamp("kill", mob, "book.fundamentum.bestiary.slain", FId.of("minecraft", "iron_sword"), 1));
                entries.add(new BookData.Entry(entryId, ns, type.getDescriptionId(), tabTitle, icon, mob, null, order++,
                    List.of(), "", "", List.of(), 0, 0, List.of(), stamps));
                JsonElement z = sub(o, "zoom", mob);
                made.put(entryId, new Sheet(mob, ns, source, ids(sub(o, "drops", mob)), ids(sub(o, "biomes", mob)),
                    z != null && z.isJsonPrimitive() ? Math.max(0.1F, z.getAsFloat()) : 1F));
            }
            if (order == 0) continue;
            categories.add(new BookData.Category(ns, tabTitle, tabIcon));
            if (bookIcon == null) bookIcon = tabIcon;
        }
        sheets = Map.copyOf(made);
        if (entries.isEmpty()) return lib;
        BookData.Book book = new BookData.Book(BOOK, "book.fundamentum.bestiary", "book.fundamentum.bestiary.subtitle", STYLE,
            bookIcon, "book.fundamentum.bestiary.intro", List.copyOf(categories), List.copyOf(entries));
        Map<FId, BookData.Book> books = new LinkedHashMap<>(lib.books());
        books.put(BOOK, book);
        return new BookResources.Library(java.util.Collections.unmodifiableMap(books), lib.styles());
    }

    /** The stat sheet for this book entry, or null for an ordinary entry. */
    public static Sheet sheet(FId entry) {
        return entry == null ? null : sheets.get(entry);
    }

    /** How much to shrink or grow the portrait model, for mobs whose model is much bigger or smaller than their hitbox. */
    public static float zoom(FId entry) {
        Sheet s = sheet(entry);
        return s == null ? 1F : s.zoom();
    }

    /** Bestiary pages open once the player has seen the mob; every other entry is unaffected. */
    public static boolean unlocked(BookData.Entry e) {
        Sheet s = sheet(e.id());
        return s == null || BookProgress.seen(s.entity());
    }

    /** Max health in half hearts (0 if unknown). */
    public static int health(FId mob) {
        AttributeSupplier a = attributes(FVanilla.entityType(mob));
        return a == null || !a.hasAttribute(Attributes.MAX_HEALTH) ? 0 : (int) Math.round(a.getBaseValue(Attributes.MAX_HEALTH));
    }

    /** The stat table: health, armor, attack, speed, follow range, size and group, skipping what the mob lacks. */
    public static List<Row> rows(FId mob) {
        return ROWS.computeIfAbsent(mob, k -> {
            List<Row> out = new ArrayList<>();
            EntityType<?> type = FVanilla.entityType(k);
            AttributeSupplier a = attributes(type);
            if (a == null) return out;
            out.add(new Row("book.fundamentum.bestiary.health", "Health", num(base(a, Attributes.MAX_HEALTH))));
            out.add(new Row("book.fundamentum.bestiary.armor", "Armor", num(base(a, Attributes.ARMOR))));
            double attack = base(a, Attributes.ATTACK_DAMAGE);
            if (attack > 0) out.add(new Row("book.fundamentum.bestiary.attack", "Attack", num(attack)));
            double fly = base(a, Attributes.FLYING_SPEED);
            if (fly > 0) out.add(new Row("book.fundamentum.bestiary.flying_speed", "Flying speed", num(fly)));
            else {
                double walk = base(a, Attributes.MOVEMENT_SPEED);
                if (walk > 0) out.add(new Row("book.fundamentum.bestiary.speed", "Speed", num(walk)));
            }
            double follow = base(a, Attributes.FOLLOW_RANGE);
            if (follow > 0) out.add(new Row("book.fundamentum.bestiary.follows", "Follows", num(follow) + " blocks"));
            out.add(new Row("book.fundamentum.bestiary.size", "Size",
                num(type.getDimensions().width()) + " x " + num(type.getDimensions().height())));
            String group = type.getCategory().getName();
            out.add(new Row("book.fundamentum.bestiary.group", "Group", "@book.fundamentum.bestiary.group." + group + "|"
                + group.substring(0, 1).toUpperCase(Locale.ROOT) + group.substring(1).replace('_', ' ')));
            return out;
        });
    }

    @SuppressWarnings("unchecked")
    private static AttributeSupplier attributes(EntityType<?> type) {
        if (type == null) return null;
        try {
            return DefaultAttributes.hasSupplier(type) ? DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type) : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static double base(AttributeSupplier a, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr) {
        return a.hasAttribute(attr) ? a.getBaseValue(attr) : 0;
    }

    private static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-6) return Long.toString(Math.round(v));
        String s = String.format(Locale.ROOT, "%.2f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static FId egg(FId mob) {
        FId egg = mob.withSuffix("_spawn_egg");
        Item item = FVanilla.item(egg);
        return item == null || item == Items.AIR ? null : egg;
    }

    private static JsonElement sub(JsonObject o, String key, FId mob) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key).get(mob.toString()) : null;
    }

    private static List<FId> ids(JsonElement el) {
        List<FId> out = new ArrayList<>();
        if (el == null || !el.isJsonArray()) return out;
        for (JsonElement e : (JsonArray) el) {
            FId id = FId.tryOf(e.getAsString());
            if (id != null) out.add(id);
        }
        return out;
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) ? o.get(key).getAsString() : fallback;
    }

    private static FId id(JsonObject o, String key) {
        return o.has(key) ? FId.tryOf(o.get(key).getAsString()) : null;
    }
}
