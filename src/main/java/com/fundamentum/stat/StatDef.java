package com.fundamentum.stat;

import com.fundamentum.port.FId;
import com.google.gson.JsonObject;
import java.util.Locale;

/**
 * One declared stat, from {@code data/<ns>/fundamentum/stats/<path>.json}:
 * <pre>
 * {
 *   "name": "stat.mymod.strength",     translation key (default stat.&lt;ns&gt;.&lt;path&gt;)
 *   "icon": "minecraft:iron_sword",
 *   "type": "number",                  number, percent (0..1 shown as %), time (ticks shown as m:ss) or count
 *   "scope": "player",                 player, entity (any living entity) or item (on the stack)
 *   "default": 0, "min": 0, "max": 100,
 *   "format": "%.1f",                  optional Java format for the number
 *   "hud": true,                       shown on the stats HUD layer (player scope)
 *   "tooltip": true,                   shown in item tooltips (item scope)
 *   "source": {"type": "stored"}       where the value comes from, see {@link Stats}
 * }
 * </pre>
 */
public record StatDef(FId id, String name, FId icon, Type type, Scope scope, double defaultValue, double min, double max, String format,
                      boolean hud, boolean tooltip, JsonObject source, String json) {

    public enum Type { NUMBER, PERCENT, TIME, COUNT }

    public enum Scope { PLAYER, ENTITY, ITEM }

    /** The stand-in for an undeclared id: a stored number on players and entities, no limits. */
    public static StatDef undeclared(FId id) {
        JsonObject src = new JsonObject();
        src.addProperty("type", "stored");
        return new StatDef(id, "stat." + id.namespace() + "." + id.path().replace('/', '.'), null, Type.NUMBER, Scope.ENTITY, 0,
            -Double.MAX_VALUE, Double.MAX_VALUE, "", false, false, src, "{}");
    }

    public static StatDef parse(FId id, JsonObject o) {
        String name = o.has("name") ? o.get("name").getAsString() : "stat." + id.namespace() + "." + id.path().replace('/', '.');
        FId icon = o.has("icon") ? FId.tryOf(o.get("icon").getAsString()) : null;
        Type type = o.has("type") ? Type.valueOf(o.get("type").getAsString().toUpperCase(Locale.ROOT)) : Type.NUMBER;
        Scope scope = o.has("scope") ? Scope.valueOf(o.get("scope").getAsString().toUpperCase(Locale.ROOT)) : Scope.PLAYER;
        JsonObject src;
        if (o.has("source") && o.get("source").isJsonObject()) {
            src = o.getAsJsonObject("source");
        } else {
            src = new JsonObject();
            src.addProperty("type", o.has("source") ? o.get("source").getAsString() : "stored");
        }
        if (!src.has("type")) src.addProperty("type", "stored");
        return new StatDef(id, name, icon, type, scope,
            o.has("default") ? o.get("default").getAsDouble() : 0,
            o.has("min") ? o.get("min").getAsDouble() : -Double.MAX_VALUE,
            o.has("max") ? o.get("max").getAsDouble() : Double.MAX_VALUE,
            o.has("format") ? o.get("format").getAsString() : "",
            o.has("hud") && o.get("hud").getAsBoolean(),
            o.has("tooltip") ? o.get("tooltip").getAsBoolean() : scope == Scope.ITEM,
            src, o.toString());
    }

    public String sourceType() {
        return this.source.get("type").getAsString();
    }

    public boolean stored() {
        return "stored".equals(sourceType());
    }

    public double clamp(double v) {
        return Math.max(this.min, Math.min(this.max, v));
    }

    /** The value as shown: the format when given, else by type (50%, 1:05, 12, 2.5; numbers to two places). */
    public String format(double v) {
        if (!this.format.isEmpty()) return String.format(Locale.ROOT, this.format, v);
        return switch (this.type) {
            case PERCENT -> Math.round(v * 100) + "%";
            case COUNT -> Long.toString((long) v);
            case TIME -> {
                long secs = (long) v / 20;
                long h = secs / 3600;
                long m = secs / 60 % 60;
                long s = secs % 60;
                yield h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
            }
            case NUMBER -> Double.isFinite(v) ? java.math.BigDecimal.valueOf(Math.round(v * 100) / 100.0).stripTrailingZeros().toPlainString() : Double.toString(v);
        };
    }
}
