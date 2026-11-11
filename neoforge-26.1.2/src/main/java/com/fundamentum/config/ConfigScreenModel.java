package com.fundamentum.config;

import com.fundamentum.port.FNet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * What the shared config screen shows and does, without any screen code: the rows after search and filters, which
 * rows may be edited, checking typed text, pending edits, resets and applying them. The screen of each game version
 * only draws this.
 */
public final class ConfigScreenModel {
    private final boolean canEditServer;
    private final boolean connected;
    private final Function<String, String> translate;
    private final Map<ConfigEntry, Optional<Object>> pending = new LinkedHashMap<>();
    private String search = "";
    private String mod;
    private String category;

    /**
     * @param canEditServer the player may change server and startup values (an operator, or no server connected)
     * @param connected     the game is connected to a server (its own singleplayer one included)
     * @param translate     lang key to text, or null when there is no translation
     */
    public ConfigScreenModel(boolean canEditServer, boolean connected, Function<String, String> translate) {
        this.canEditServer = canEditServer;
        this.connected = connected;
        this.translate = translate;
    }

    public void search(String text) {
        this.search = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    /** Limits the rows to one mod (namespace), or null for all. */
    public void mod(String namespace) {
        this.mod = namespace;
    }

    /** Limits the rows to one category, or null for all. */
    public void category(String category) {
        this.category = category;
    }

    public String mod() {
        return this.mod;
    }

    public String category() {
        return this.category;
    }

    /** Every mod with configs, sorted. */
    public List<String> mods() {
        Set<String> out = new LinkedHashSet<>();
        for (FConfig c : FConfig.all()) out.add(c.id().namespace());
        return new ArrayList<>(out);
    }

    /** The categories of the chosen mod (or of all mods). */
    public List<String> categories() {
        Set<String> out = new LinkedHashSet<>();
        for (FConfig c : FConfig.all()) {
            if (this.mod != null && !this.mod.equals(c.id().namespace())) continue;
            for (ConfigEntry e : c.entries()) out.add(e.category());
        }
        return new ArrayList<>(out);
    }

    /** The entries to show, in config then declaration order. */
    public List<ConfigEntry> rows() {
        List<ConfigEntry> out = new ArrayList<>();
        for (FConfig c : FConfig.all()) {
            if (this.mod != null && !this.mod.equals(c.id().namespace())) continue;
            for (ConfigEntry e : c.entries()) {
                if (this.category != null && !this.category.equals(e.category())) continue;
                if (!this.search.isEmpty() && !matches(e)) continue;
                out.add(e);
            }
        }
        return out;
    }

    private boolean matches(ConfigEntry e) {
        StringBuilder hay = new StringBuilder();
        hay.append(e.key()).append(' ').append(e.config().id()).append(' ').append(e.category()).append(' ').append(label(e)).append(' ');
        String comment = this.translate.apply(e.comment());
        if (comment != null) hay.append(comment);
        String h = hay.toString().toLowerCase(Locale.ROOT);
        for (String word : this.search.split("\\s+")) {
            if (!h.contains(word)) return false;
        }
        return true;
    }

    /** The row's title: the translated name ({@code <comment key>.name}) or the key. */
    public String label(ConfigEntry e) {
        String name = this.translate.apply(e.comment() + ".name");
        return name != null ? name : e.key();
    }

    /** The row's tooltip: comment, then type, range and side. */
    public String tooltip(ConfigEntry e) {
        String comment = this.translate.apply(e.comment());
        String d = e.describe() + ", default " + e.toText(e.defaultValue());
        return comment == null ? d : comment + "\n" + d;
    }

    /** Client entries always; server and startup entries only for an operator (greyed out otherwise). */
    public boolean editable(ConfigEntry e) {
        return e.side() == FConfig.Side.CLIENT || this.canEditServer;
    }

    /** The text the row shows: a pending edit, else the value in use. */
    public String text(ConfigEntry e) {
        Optional<Object> p = this.pending.get(e);
        if (p != null) return e.toText(p.orElse(e.defaultValue()));
        return e.toText(e.shown());
    }

    public boolean isPending(ConfigEntry e) {
        return this.pending.containsKey(e);
    }

    /** Null when the text is a valid value for the entry, else why not. */
    public String validate(ConfigEntry e, String text) {
        try {
            e.fromText(text);
            return null;
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
    }

    /** Keeps a typed value to apply on Done. Returns false (and keeps nothing) when it is not valid or not editable. */
    public boolean edit(ConfigEntry e, String text) {
        if (!editable(e)) return false;
        Object v;
        try {
            v = e.fromText(text);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (v.equals(e.shown())) {
            this.pending.remove(e);
        } else {
            this.pending.put(e, Optional.of(v));
        }
        return true;
    }

    /** Resets the entry to its default on Done (removes it from the files). */
    public boolean reset(ConfigEntry e) {
        if (!editable(e)) return false;
        this.pending.put(e, Optional.empty());
        return true;
    }

    public int pendingCount() {
        return this.pending.size();
    }

    public void cancel() {
        this.pending.clear();
    }

    /**
     * Applies the pending edits: client entries (and everything while no server is connected) go into the local
     * files; server and startup entries go to the server, which checks the player is an operator. Returns how many
     * were applied or sent.
     */
    public int apply() {
        int n = 0;
        for (Map.Entry<ConfigEntry, Optional<Object>> p : this.pending.entrySet()) {
            ConfigEntry e = p.getKey();
            Object v = p.getValue().orElse(null);
            if (e.side() == FConfig.Side.CLIENT || !this.connected) {
                e.config().write(e.key(), v);
            } else {
                FNet.toServer(new FConfig.Edit(e.config().id(), e.key(), Optional.ofNullable(v).map(x -> Json5.write(e.toJson(x)))));
            }
            n++;
        }
        this.pending.clear();
        return n;
    }

    /** The message an op edit would send, for checks. */
    public static FConfig.Edit editMessage(ConfigEntry e, Object value) {
        return new FConfig.Edit(e.config().id(), e.key(), Optional.ofNullable(value).map(x -> Json5.write(e.toJson(x))));
    }
}
