package com.fundamentum.guide.client;

import com.fundamentum.port.FId;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;

/**
 * One player's guide settings: pinned pages, recently opened pages, the chapters left open in the list and the list's width. Kept in
 * {@code config/fundamentum-guide.json}; a missing or broken file just starts empty. Client only, the same file on every version.
 */
final class GuidePrefs {
    static final int RECENT_MAX = 6;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<FId> PINNED = new ArrayList<>();
    private static final List<FId> RECENT = new ArrayList<>();
    private static final Set<String> OPEN = new LinkedHashSet<>();
    private static boolean loaded;
    private static FId theme;
    private static boolean ownThemes = true;
    /** The list's width in GUI pixels; 0 keeps the default. */
    private static int navWidth;
    private static boolean navFolded;

    private GuidePrefs() { }

    private static File file() {
        return new File(Minecraft.getInstance().gameDirectory, "config/fundamentum-guide.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        File f = file();
        if (!f.isFile()) return;
        try {
            JsonObject j = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            ids(j, "pinned", PINNED);
            ids(j, "recent", RECENT);
            if (j.has("open") && j.get("open").isJsonArray()) for (JsonElement e : j.getAsJsonArray("open")) OPEN.add(e.getAsString());
            if (j.has("theme") && j.get("theme").isJsonPrimitive()) theme = FId.tryOf(j.get("theme").getAsString());
            if (j.has("book_themes") && j.get("book_themes").isJsonPrimitive()) ownThemes = j.get("book_themes").getAsBoolean();
            if (j.has("nav_width") && j.get("nav_width").isJsonPrimitive()) navWidth = Math.max(0, j.get("nav_width").getAsInt());
            if (j.has("nav_folded") && j.get("nav_folded").isJsonPrimitive()) navFolded = j.get("nav_folded").getAsBoolean();
        } catch (IOException | RuntimeException e) {
            PINNED.clear();
            RECENT.clear();
            OPEN.clear();
        }
    }

    private static void ids(JsonObject j, String key, List<FId> out) {
        if (!j.has(key) || !j.get(key).isJsonArray()) return;
        for (JsonElement e : j.getAsJsonArray(key)) {
            FId id = e.isJsonPrimitive() ? FId.tryOf(e.getAsString()) : null;
            if (id != null && !out.contains(id)) out.add(id);
        }
    }

    private static void save() {
        JsonObject j = new JsonObject();
        JsonArray p = new JsonArray();
        PINNED.forEach(id -> p.add(id.toString()));
        JsonArray r = new JsonArray();
        RECENT.forEach(id -> r.add(id.toString()));
        JsonArray o = new JsonArray();
        OPEN.forEach(o::add);
        j.add("pinned", p);
        j.add("recent", r);
        j.add("open", o);
        if (theme != null) j.addProperty("theme", theme.toString());
        j.addProperty("book_themes", ownThemes);
        if (navWidth > 0) j.addProperty("nav_width", navWidth);
        j.addProperty("nav_folded", navFolded);
        try {
            File f = file();
            f.getParentFile().mkdirs();
            Files.writeString(f.toPath(), GSON.toJson(j), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // settings are a convenience; failing to save them never breaks the guide
        }
    }

    static List<FId> pinned() {
        load();
        return List.copyOf(PINNED);
    }

    static boolean isPinned(FId page) {
        load();
        return PINNED.contains(page);
    }

    static void togglePin(FId page) {
        load();
        if (!PINNED.remove(page)) PINNED.add(page);
        save();
    }

    static List<FId> recent() {
        load();
        return List.copyOf(RECENT);
    }

    /** Puts a page at the top of the recent list. */
    static void visit(FId page) {
        load();
        if (!RECENT.isEmpty() && RECENT.get(0).equals(page)) return;
        RECENT.remove(page);
        RECENT.add(0, page);
        while (RECENT.size() > RECENT_MAX) RECENT.remove(RECENT.size() - 1);
        save();
    }

    /** Whether a list section (a chapter id, or a fixed key like {@code #recent}) is open. */
    static boolean isOpen(String key, boolean byDefault) {
        load();
        return OPEN.contains(byDefault ? "-" + key : key) != byDefault;
    }

    static void toggle(String key, boolean byDefault) {
        load();
        String k = byDefault ? "-" + key : key;
        if (!OPEN.remove(k)) OPEN.add(k);
        save();
    }

    /** The theme the player picked, or null for the default. */
    static FId theme() {
        load();
        return theme;
    }

    static void setTheme(FId id) {
        load();
        theme = id;
        save();
    }

    /** Whether a book that names its own theme shows in it (else every book uses the player's theme). */
    static boolean ownThemes() {
        load();
        return ownThemes;
    }

    static void setOwnThemes(boolean on) {
        load();
        ownThemes = on;
        save();
    }

    /** The width the player dragged the list to, or 0 for the default. */
    static int navWidth() {
        load();
        return navWidth;
    }

    /** Whether the list is folded away so only the page shows. */
    static boolean navFolded() {
        load();
        return navFolded;
    }

    static void setNav(int width, boolean folded) {
        load();
        if (width == navWidth && folded == navFolded) return;
        navWidth = Math.max(0, width);
        navFolded = folded;
        save();
    }
}
