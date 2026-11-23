package com.fundamentum.event;

import com.fundamentum.port.FId;
import com.fundamentum.port.FVanilla;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ecosystem events: moments that matter across mods (a spell cast, a boss killed, a quest completed), each a plain
 * record with a stable id. Any mod posts one with {@link #post}; listeners hear it here, on the loader's own event bus
 * (as {@code FundamentumEvent}), through the {@code fundamentum:event} advancement trigger, and in scripts.
 * <p>
 * Listeners run in the order they were added. Once a cancellable event is cancelled, later listeners, the loader bus
 * and the advancement trigger are skipped, and {@link #post} returns true so the poster can stop what it was doing.
 */
public final class FEvents {
    private FEvents() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");

    /** A registered event type. */
    public record Type(FId id, Class<? extends Record> type, boolean cancellable) { }

    /** What a listener can do with the event it hears. */
    public interface Call {
        FId id();

        boolean cancellable();

        /** Stops the event (ignored, with a warning, when the event is not cancellable). */
        void cancel();

        boolean cancelled();
    }

    @FunctionalInterface
    public interface Listener<E> {
        void on(E event, Call call);
    }

    /** The loader side of {@link #post}: posts on the loader bus and returns true when a listener there cancelled. */
    @FunctionalInterface
    public interface Bridge {
        boolean post(Type type, Record event, boolean cancelled);
    }

    private static final Map<FId, Type> BY_ID = new ConcurrentSkipListMap<>();
    private static final Map<Class<?>, Type> BY_CLASS = new ConcurrentHashMap<>();
    private static final Map<FId, List<Listener<Record>>> LISTENERS = new ConcurrentHashMap<>();
    private static final List<Bridge> BRIDGES = new CopyOnWriteArrayList<>();

    // ---- types ----

    /** Registers an event record under a stable id. Registering the same record and id again does nothing. */
    public static synchronized <E extends Record> Type register(FId id, Class<E> type, boolean cancellable) {
        Type known = BY_ID.get(id);
        if (known != null) {
            if (known.type() == type && known.cancellable() == cancellable) return known;
            throw new IllegalStateException("Event " + id + " is already registered for " + known.type().getName());
        }
        if (BY_CLASS.containsKey(type)) throw new IllegalStateException(type.getName() + " is already registered as " + BY_CLASS.get(type).id());
        Type t = new Type(id, type, cancellable);
        BY_ID.put(id, t);
        BY_CLASS.put(type, t);
        return t;
    }

    /** Every registered event type, sorted by id. */
    public static Collection<Type> types() {
        Events.bootstrap();
        return Collections.unmodifiableCollection(BY_ID.values());
    }

    public static Type type(FId id) {
        Events.bootstrap();
        return BY_ID.get(id);
    }

    public static Type type(Class<?> type) {
        Events.bootstrap();
        return BY_CLASS.get(type);
    }

    /** The id of an event record class, or null when it is not registered. */
    public static FId idOf(Class<?> type) {
        Type t = type(type);
        return t == null ? null : t.id();
    }

    // ---- listening ----

    /** Hears every event of this record class. */
    public static <E extends Record> void listen(Class<E> type, Consumer<? super E> listener) {
        listen(type, (e, call) -> listener.accept(e));
    }

    /** Hears every event of this record class, with the chance to cancel it. */
    @SuppressWarnings("unchecked")
    public static <E extends Record> void listen(Class<E> type, Listener<? super E> listener) {
        Type t = type(type);
        if (t == null) throw new IllegalArgumentException(type.getName() + " is not a registered event");
        LISTENERS.computeIfAbsent(t.id(), k -> new CopyOnWriteArrayList<>()).add((Listener<Record>) (Listener<?>) listener);
    }

    /** Hears every event with this id; for scripts and data, which know the id rather than the class. */
    public static void listen(FId id, Listener<Record> listener) {
        LISTENERS.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** Called by the loader layer and the advancement trigger, not by mods. */
    public static void bridge(Bridge bridge) {
        BRIDGES.add(bridge);
    }

    // ---- posting ----

    /** Posts an event. Returns true when it was cancelled (always false for an event that cannot be cancelled). */
    public static boolean post(Record event) {
        Type t = type(event.getClass());
        if (t == null) throw new IllegalArgumentException(event.getClass().getName() + " is not a registered event");
        CallImpl call = new CallImpl(t);
        for (Listener<Record> l : LISTENERS.getOrDefault(t.id(), List.of())) {
            try {
                l.on(event, call);
            } catch (RuntimeException ex) {
                LOG.error("Listener for event {} failed", t.id(), ex);
            }
            if (call.cancelled) return true;
        }
        for (Bridge b : BRIDGES) {
            try {
                if (b.post(t, event, call.cancelled) && t.cancellable()) call.cancelled = true;
            } catch (RuntimeException ex) {
                LOG.error("Posting event {} on the loader side failed", t.id(), ex);
            }
            if (call.cancelled) return true;
        }
        return false;
    }

    private static final class CallImpl implements Call {
        private final Type type;
        boolean cancelled;

        CallImpl(Type type) {
            this.type = type;
        }

        @Override
        public FId id() {
            return this.type.id();
        }

        @Override
        public boolean cancellable() {
            return this.type.cancellable();
        }

        @Override
        public void cancel() {
            if (!this.type.cancellable()) {
                LOG.warn("Event {} cannot be cancelled; cancel() ignored", this.type.id());
                return;
            }
            this.cancelled = true;
        }

        @Override
        public boolean cancelled() {
            return this.cancelled;
        }
    }

    // ---- reading events (triggers, data conditions, scripts) ----

    /** The record's fields in declaration order. */
    public static Map<String, Object> fields(Record event) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RecordComponent c : event.getClass().getRecordComponents()) {
            try {
                c.getAccessor().setAccessible(true);
                out.put(c.getName(), c.getAccessor().invoke(event));
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Cannot read " + c.getName() + " of " + event.getClass().getName(), ex);
            }
        }
        return out;
    }

    /** The player the event is about: the first field holding a server player, or null. */
    public static ServerPlayer player(Record event) {
        for (Object v : fields(event).values()) {
            if (v instanceof ServerPlayer p) return p;
        }
        return null;
    }

    /**
     * A field as plain JSON: numbers, true/false and text as they are; ids, enums and text as strings; an entity as its
     * type id, an item stack as its item id, a block position as {@code "x y z"}; a list of ids as the ids joined with
     * commas. Null for a field that is empty.
     */
    public static JsonElement plain(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof Number n) return new JsonPrimitive(n);
        if (v instanceof String s) return new JsonPrimitive(s);
        if (v instanceof FId id) return new JsonPrimitive(id.toString());
        if (v instanceof Enum<?> e) return new JsonPrimitive(e.name().toLowerCase(java.util.Locale.ROOT));
        if (v instanceof Entity e) return new JsonPrimitive(FVanilla.idOf(e.getType()).toString());
        if (v instanceof ItemStack s) return s.isEmpty() ? null : new JsonPrimitive(FVanilla.idOf(s.getItem()).toString());
        if (v instanceof BlockPos p) return new JsonPrimitive(p.getX() + " " + p.getY() + " " + p.getZ());
        if (v instanceof Collection<?> c) {
            List<String> parts = new ArrayList<>();
            for (Object o : c) parts.add(String.valueOf(o));
            return new JsonPrimitive(String.join(",", parts));
        }
        return new JsonPrimitive(v.toString());
    }

    /** The event as JSON: its id and every field in {@link #plain} form. For logs, scripts and debugging. */
    public static JsonObject describe(Record event) {
        JsonObject o = new JsonObject();
        FId id = idOf(event.getClass());
        o.addProperty("event", id == null ? event.getClass().getName() : id.toString());
        for (Map.Entry<String, Object> f : fields(event).entrySet()) {
            JsonElement j = plain(f.getValue());
            if (j != null) o.add(f.getKey(), j);
        }
        return o;
    }

    /**
     * True when every condition in {@code where} holds: {@code "field": value} for an exact match (text compared without
     * case, numbers by value), {@code "field": {"min": a, "max": b}} for a range (either end optional), and
     * {@code "field": [a, b]} for any one of several values. A missing or null {@code where} matches everything; a
     * field the event does not have never matches.
     */
    public static boolean matches(Record event, JsonObject where) {
        if (where == null || where.size() == 0) return true;
        Map<String, Object> fields = fields(event);
        for (Map.Entry<String, JsonElement> w : where.entrySet()) {
            if (!fields.containsKey(w.getKey())) return false;
            if (!matchesOne(plain(fields.get(w.getKey())), w.getValue())) return false;
        }
        return true;
    }

    private static boolean matchesOne(JsonElement value, JsonElement want) {
        if (want == null || want.isJsonNull()) return value == null;
        if (value == null) return false;
        if (want.isJsonArray()) {
            for (JsonElement w : want.getAsJsonArray()) {
                if (matchesOne(value, w)) return true;
            }
            return false;
        }
        if (want.isJsonObject()) {
            JsonObject range = want.getAsJsonObject();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
            double d = value.getAsDouble();
            if (range.has("min") && d < range.get("min").getAsDouble()) return false;
            if (range.has("max") && d > range.get("max").getAsDouble()) return false;
            return true;
        }
        JsonPrimitive w = want.getAsJsonPrimitive();
        JsonPrimitive v = value.getAsJsonPrimitive();
        if (w.isNumber() && v.isNumber()) return w.getAsDouble() == v.getAsDouble();
        if (w.isBoolean() && v.isBoolean()) return w.getAsBoolean() == v.getAsBoolean();
        String ws = w.getAsString();
        String vs = v.getAsString();
        if (vs.equalsIgnoreCase(ws)) return true;
        // "stone" matches "minecraft:stone"
        return !ws.contains(":") && vs.startsWith("minecraft:") && vs.substring(10).equalsIgnoreCase(ws) || Objects.equals(vs, ws);
    }
}
