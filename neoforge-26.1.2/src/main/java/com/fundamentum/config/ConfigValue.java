package com.fundamentum.config;

import java.util.function.Function;

/**
 * A typed handle on one config entry. Handles are cheap and stay valid across reloads; {@link #get()} always reads the
 * value in use now (the server's copy on a client connected to one).
 */
public final class ConfigValue<T> {
    private final FConfig config;
    private final String key;
    private final Function<Object, T> read;
    private final Function<T, Object> write;

    ConfigValue(FConfig config, String key, Function<Object, T> read, Function<T, Object> write) {
        this.config = config;
        this.key = key;
        this.read = read;
        this.write = write;
    }

    public FConfig config() {
        return this.config;
    }

    public String key() {
        return this.key;
    }

    /** The entry, or null while it is not declared (a data-declared config before its datapack loaded). */
    public ConfigEntry entry() {
        return this.config.entry(this.key);
    }

    public boolean present() {
        return entry() != null;
    }

    public T get() {
        ConfigEntry e = entry();
        if (e == null) throw new IllegalStateException("Config " + this.config.id() + " has no entry " + this.key + " (yet)");
        this.config.ensureLoaded();
        return this.read.apply(e.current());
    }

    /** The value, or the fallback while the entry is not declared. */
    public T getOr(T fallback) {
        return present() ? get() : fallback;
    }

    /** Writes the value into the config file (the per-world file when one exists) and applies it. */
    public void set(T value) {
        this.config.write(this.key, this.write.apply(value));
    }

    /** Removes the value from the files, so the default applies again. */
    public void reset() {
        this.config.write(this.key, null);
    }

    public boolean isDefault() {
        ConfigEntry e = entry();
        return e == null || e.isDefault();
    }

    public boolean needsRestart() {
        ConfigEntry e = entry();
        return e != null && e.needsRestart();
    }

    /** Where the value lives: {@code SERVER} (default, sent to players), {@code CLIENT} or {@code STARTUP}. */
    public ConfigValue<T> side(FConfig.Side side) {
        entryOrThrow().side = side;
        return this;
    }

    /** The screen category (default {@code general}). */
    public ConfigValue<T> category(String category) {
        entryOrThrow().category = category;
        return this;
    }

    /** The lang key of the comment (default {@code config.<ns>.<path>.<key>}). */
    public ConfigValue<T> comment(String langKey) {
        entryOrThrow().comment = langKey;
        return this;
    }

    private ConfigEntry entryOrThrow() {
        ConfigEntry e = entry();
        if (e == null) throw new IllegalStateException("Config " + this.config.id() + " has no entry " + this.key);
        return e;
    }
}
