package com.fundamentum.config;

import com.fundamentum.port.FId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One entry of a config: its declaration (type, default, range, side, category, comment key) and the layers its value
 * comes from. Values are held as Boolean, Integer, Double, String (also enum names), FId, List of FId, or Integer for
 * colours (0xAARRGGBB).
 */
public final class ConfigEntry {
    public enum Type { BOOL, INT, DOUBLE, STRING, ENUM, ID, ID_LIST, COLOUR }

    final FConfig config;
    final String key;
    final Type type;
    Object codeDefault;
    Double min;
    Double max;
    List<String> values = List.of();
    FConfig.Side side = FConfig.Side.SERVER;
    String category = "general";
    String comment;

    // layers, lowest first
    Object dataDefault;
    Object fileValue;
    Object worldValue;
    Object synced;
    Object applied;
    boolean appliedSet;

    ConfigEntry(FConfig config, String key, Type type, Object codeDefault) {
        this.config = config;
        this.key = key;
        this.type = type;
        this.codeDefault = codeDefault;
    }

    public FConfig config() {
        return this.config;
    }

    public String key() {
        return this.key;
    }

    public Type type() {
        return this.type;
    }

    public FConfig.Side side() {
        return this.side;
    }

    public String category() {
        return this.category;
    }

    /** The lang key of this entry's comment. */
    public String comment() {
        return this.comment != null ? this.comment
            : "config." + this.config.id().namespace() + "." + this.config.id().path().replace('/', '.') + "." + this.key;
    }

    public Double min() {
        return this.min;
    }

    public Double max() {
        return this.max;
    }

    /** The allowed names of an enum entry. */
    public List<String> values() {
        return this.values;
    }

    /** The default after datapacks: what a reset falls back to when no file sets it. */
    public Object defaultValue() {
        return this.dataDefault != null ? this.dataDefault : this.codeDefault;
    }

    /** The value the files give: per-world file, then server/game file, then datapack default, then code default. */
    public Object effective() {
        if (this.side != FConfig.Side.CLIENT && this.worldValue != null) return this.worldValue;
        if (this.fileValue != null) return this.fileValue;
        return defaultValue();
    }

    /** The value code reads now. */
    public Object current() {
        if (this.side == FConfig.Side.SERVER && !FConfig.serverRunning() && this.synced != null) return this.synced;
        if (this.side == FConfig.Side.STARTUP) {
            if (!this.appliedSet) {
                this.applied = effective();
                this.appliedSet = true;
            }
            return this.applied;
        }
        return effective();
    }

    /** True for a {@code startup} entry whose files now say something other than the value in use. */
    public boolean needsRestart() {
        return this.side == FConfig.Side.STARTUP && this.appliedSet && !Objects.equals(this.applied, effective());
    }

    /** True when neither file sets this entry. */
    public boolean isDefault() {
        return (this.side == FConfig.Side.CLIENT || this.worldValue == null) && this.fileValue == null;
    }

    /** The value to show on the screen: the server's copy for a synced entry while connected to one. */
    public Object shown() {
        if (this.side == FConfig.Side.SERVER && this.synced != null && !FConfig.serverRunning()) return this.synced;
        return effective();
    }

    // ---- parsing and writing ----

    /** Reads a value from JSON, or throws {@link IllegalArgumentException} saying why it is not valid. */
    public Object fromJson(JsonElement json) {
        if (json == null || json.isJsonNull()) throw new IllegalArgumentException("no value");
        Object v;
        switch (this.type) {
            case BOOL -> {
                if (!json.isJsonPrimitive()) throw new IllegalArgumentException("expected true or false");
                JsonPrimitive p = json.getAsJsonPrimitive();
                v = p.isBoolean() ? p.getAsBoolean() : parseBool(p.getAsString());
            }
            case INT -> {
                double d = number(json);
                if (d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) throw new IllegalArgumentException("expected a whole number");
                v = (int) d;
            }
            case DOUBLE -> v = number(json);
            case STRING -> {
                if (!json.isJsonPrimitive()) throw new IllegalArgumentException("expected text");
                v = json.getAsString();
            }
            case ENUM, ID, COLOUR -> {
                if (!json.isJsonPrimitive()) throw new IllegalArgumentException("expected text");
                if (this.type == Type.COLOUR && json.getAsJsonPrimitive().isNumber()) v = json.getAsInt();
                else v = fromText(json.getAsString());
            }
            case ID_LIST -> {
                if (!json.isJsonArray()) throw new IllegalArgumentException("expected a list of ids");
                List<FId> ids = new ArrayList<>();
                for (JsonElement e : json.getAsJsonArray()) ids.add(id(e.isJsonPrimitive() ? e.getAsString() : String.valueOf(e)));
                v = List.copyOf(ids);
            }
            default -> throw new IllegalStateException();
        }
        check(v);
        return v;
    }

    /** Reads a value typed into the screen (lists comma-separated, colours as #RRGGBB). */
    public Object fromText(String text) {
        String t = text.trim();
        Object v;
        switch (this.type) {
            case BOOL -> v = parseBool(t);
            case INT -> {
                try {
                    v = Integer.parseInt(t);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("expected a whole number");
                }
            }
            case DOUBLE -> {
                double d;
                try {
                    d = Double.parseDouble(t);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("expected a number");
                }
                if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("expected a number");
                v = d;
            }
            case STRING -> v = text;
            case ENUM -> {
                String found = null;
                for (String s : this.values) {
                    if (s.equalsIgnoreCase(t)) found = s;
                }
                if (found == null) throw new IllegalArgumentException("expected one of " + String.join(", ", this.values));
                v = found;
            }
            case ID -> v = id(t);
            case ID_LIST -> {
                List<FId> ids = new ArrayList<>();
                for (String part : t.split(",")) {
                    if (!part.isBlank()) ids.add(id(part.trim()));
                }
                v = List.copyOf(ids);
            }
            case COLOUR -> {
                String h = t.startsWith("#") ? t.substring(1) : t;
                if (h.length() != 6 && h.length() != 8) throw new IllegalArgumentException("expected #RRGGBB or #AARRGGBB");
                try {
                    v = (int) Long.parseLong(h, 16);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("expected #RRGGBB or #AARRGGBB");
                }
            }
            default -> throw new IllegalStateException();
        }
        check(v);
        return v;
    }

    public JsonElement toJson(Object value) {
        return switch (this.type) {
            case BOOL -> new JsonPrimitive((Boolean) value);
            case INT -> new JsonPrimitive((Integer) value);
            case DOUBLE -> new JsonPrimitive((Double) value);
            case STRING, ENUM, COLOUR -> new JsonPrimitive(toText(value));
            case ID -> new JsonPrimitive(value.toString());
            case ID_LIST -> {
                JsonArray a = new JsonArray();
                for (Object o : (List<?>) value) a.add(o.toString());
                yield a;
            }
        };
    }

    public String toText(Object value) {
        if (value == null) return "";
        return switch (this.type) {
            case ID_LIST -> {
                List<String> parts = new ArrayList<>();
                for (Object o : (List<?>) value) parts.add(o.toString());
                yield String.join(", ", parts);
            }
            case COLOUR -> {
                int c = (Integer) value;
                yield (c >>> 24) == 0 ? String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF) : String.format(Locale.ROOT, "#%08X", c);
            }
            default -> String.valueOf(value);
        };
    }

    /** The type, range and side as one line, for file comments and the screen. */
    public String describe() {
        StringBuilder b = new StringBuilder(this.type.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        if (this.min != null || this.max != null) {
            b.append(", ").append(this.min == null ? "any" : trim(this.min)).append(" to ").append(this.max == null ? "any" : trim(this.max));
        }
        if (this.type == Type.ENUM) b.append(", one of ").append(String.join(", ", this.values));
        b.append(", ").append(switch (this.side) {
            case SERVER -> "server, sent to players";
            case CLIENT -> "client only";
            case STARTUP -> "needs a restart";
        });
        return b.toString();
    }

    private void check(Object v) {
        double d;
        if (v instanceof Integer i && this.type == Type.INT) d = i;
        else if (v instanceof Double x) d = x;
        else return;
        if (this.min != null && d < this.min) throw new IllegalArgumentException("below the minimum " + trim(this.min));
        if (this.max != null && d > this.max) throw new IllegalArgumentException("above the maximum " + trim(this.max));
    }

    private static String trim(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static boolean parseBool(String s) {
        if (s.equalsIgnoreCase("true")) return true;
        if (s.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("expected true or false");
    }

    private static double number(JsonElement json) {
        if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("expected a number");
        double d = json.getAsDouble();
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("expected a number");
        return d;
    }

    private static FId id(String s) {
        FId id = FId.tryOf(s.trim());
        if (id == null) throw new IllegalArgumentException("not a valid id: " + s);
        return id;
    }

    // ---- declaration as JSON (datapack files and the sync message) ----

    JsonObject declaration() {
        JsonObject o = new JsonObject();
        o.addProperty("type", this.type.name().toLowerCase(Locale.ROOT));
        o.add("default", toJson(defaultValue()));
        if (this.min != null) o.addProperty("min", this.min);
        if (this.max != null) o.addProperty("max", this.max);
        if (this.type == Type.ENUM) {
            JsonArray a = new JsonArray();
            this.values.forEach(a::add);
            o.add("values", a);
        }
        o.addProperty("side", this.side.name().toLowerCase(Locale.ROOT));
        o.addProperty("category", this.category);
        o.addProperty("comment", comment());
        return o;
    }

    /** Makes an entry from its JSON declaration. */
    static ConfigEntry declared(FConfig config, String key, JsonObject o) {
        String typeName = o.has("type") ? o.get("type").getAsString().toUpperCase(Locale.ROOT).replace(' ', '_') : "";
        if (typeName.equals("INTEGER")) typeName = "INT";
        if (typeName.equals("BOOLEAN")) typeName = "BOOL";
        if (typeName.equals("COLOR")) typeName = "COLOUR";
        Type type;
        try {
            type = Type.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("entry " + key + ": unknown type " + typeName.toLowerCase(Locale.ROOT));
        }
        ConfigEntry e = new ConfigEntry(config, key, type, null);
        if (o.has("min")) e.min = o.get("min").getAsDouble();
        if (o.has("max")) e.max = o.get("max").getAsDouble();
        if (o.has("values")) {
            List<String> vs = new ArrayList<>();
            for (JsonElement v : o.getAsJsonArray("values")) vs.add(v.getAsString());
            e.values = List.copyOf(vs);
        }
        if (type == Type.ENUM && e.values.isEmpty()) throw new IllegalArgumentException("entry " + key + ": an enum needs \"values\"");
        if (o.has("side")) e.side = FConfig.Side.valueOf(o.get("side").getAsString().toUpperCase(Locale.ROOT));
        if (o.has("category")) e.category = o.get("category").getAsString();
        if (o.has("comment")) e.comment = o.get("comment").getAsString();
        if (!o.has("default")) throw new IllegalArgumentException("entry " + key + ": no default");
        e.codeDefault = e.fromJson(o.get("default"));
        return e;
    }
}
