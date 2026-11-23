package com.fundamentum.moon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * A night event: something that happens on a night, beside (or instead of) its moon, e.g. a feast or a raid. Defined in
 * data, {@code data/<namespace>/fundamentum/night_events/<name>.json}, with the id {@code <namespace>:<name>}. Every
 * field is optional:
 * <pre>{
 *   "chance": 0.1,                     // per eligible night (0 = only when raised by code or command)
 *   "priority": 0,                     // rolled from the highest priority down; the first that happens wins
 *   "dimension": "minecraft:overworld",
 *   "requires": ["mymod:dragon_slain"],   // world flags (Moons.registerFlag) that must all be set
 *   "excludes": ["mymod:peace_treaty"],   // world flags that must all be unset
 *   "min_day": 5,                      // not before this day (night 0 is the first)
 *   "cooldown": 3,                     // nights after it last happened before it can roll again
 *   "with_moon": true,                 // false: never on a night with a special moon
 *   "moons": ["mymod:harvest"],        // only on a night with one of these moons
 *   "not_moons": ["arsmoriendi:cruenta"],   // never with these moons
 *   "duration": 0,                     // ticks it lasts once it starts; 0 = from dusk until dawn
 *   "fog": {"color": "#C08040", "strength": 0.3, "fade": {...}},   // as a moon's (Moon.Tint), laid over it
 *   "sky": {"color": "#C08040", "strength": 0.15, "fade": {...}},
 *   "title": {"title": "event.mymod.feast", "subtitle": "event.mymod.feast.sub", "color": "#hex" | "fog"},   // or "<key>"
 *   "start_message": "message.mymod.feast",  // as a moon's rise_message (Moon.Message)
 *   "start_sound": "mymod:feast_bell",       // as a moon's rise_sound (Moon.Sound)
 *   "end_message": "message.mymod.feast_over",
 *   "end_sound": ""
 * }</pre>
 * Any other field is the owning mod's own: read it from {@link #json()}.
 */
public record NightEvent(String id, double chance, int priority, String dimension, List<String> requires, List<String> excludes, int minDay,
                         int cooldown, boolean withMoon, List<String> moons, List<String> notMoons, int duration, Moon.Tint fog, Moon.Tint sky,
                         Title title, Moon.Message start, Moon.Sound startSound, Moon.Message end, Moon.Sound endSound, JsonObject json) {

    /** A title on screen when it starts ({@code title} "" = none), in the event's fog colour ({@code color} -1) or its own. */
    public record Title(String title, String subtitle, int color) {
        public static final Title NONE = new Title("", "", -1);

        public boolean isEmpty() {
            return this.title.isEmpty() && this.subtitle.isEmpty();
        }

        static Title parse(@Nullable JsonElement e) {
            if (e == null) {
                return NONE;
            }
            if (e.isJsonPrimitive()) {
                return new Title(e.getAsString(), "", -1);
            }
            JsonObject o = e.getAsJsonObject();
            String c = o.has("color") ? o.get("color").getAsString() : "fog";
            return new Title(o.has("title") ? o.get("title").getAsString() : "", o.has("subtitle") ? o.get("subtitle").getAsString() : "",
                c.equals("fog") ? -1 : Moon.color(c));
        }
    }

    /** A message's or title's colour: its own, or this event's fog colour. */
    public int color(int own) {
        return own < 0 ? this.fog.color() : own;
    }

    /**
     * Whether it may happen tonight by chance: its dimension, its flags, the day, its cooldown ({@code lastNight} is the
     * night it last happened, -1 for never) and tonight's moon ({@code ""} for none).
     */
    public boolean eligible(String dimension, Predicate<String> flags, long night, long lastNight, String moon) {
        if (!this.dimension.equals(dimension) || night < this.minDay) {
            return false;
        }
        if (lastNight >= 0 && night - lastNight <= this.cooldown) {
            return false;
        }
        for (String f : this.requires) {
            if (!flags.test(f)) {
                return false;
            }
        }
        for (String f : this.excludes) {
            if (flags.test(f)) {
                return false;
            }
        }
        if (!moon.isEmpty() && (!this.withMoon || this.notMoons.contains(moon))) {
            return false;
        }
        return this.moons.isEmpty() || this.moons.contains(moon);
    }

    public static NightEvent parse(String id, JsonObject o) {
        Moon.Tint fog = Moon.Tint.parse(o.has("fog") ? o.getAsJsonObject("fog") : new JsonObject(), 0);
        return new NightEvent(id, num(o, "chance", 0.0).doubleValue(), num(o, "priority", 0).intValue(),
            o.has("dimension") ? o.get("dimension").getAsString() : Moon.OVERWORLD, strings(o, "requires"), strings(o, "excludes"),
            Math.max(0, num(o, "min_day", 0).intValue()), Math.max(0, num(o, "cooldown", 0).intValue()),
            !o.has("with_moon") || o.get("with_moon").getAsBoolean(), strings(o, "moons"), strings(o, "not_moons"),
            Math.max(0, num(o, "duration", 0).intValue()), fog, Moon.Tint.parse(o.has("sky") ? o.getAsJsonObject("sky") : null, fog.color()),
            Title.parse(o.get("title")), Moon.Message.parse(o.get("start_message")), Moon.Sound.parse(o.get("start_sound"), Moon.Sound.NONE),
            Moon.Message.parse(o.get("end_message")), Moon.Sound.parse(o.get("end_sound"), Moon.Sound.NONE), o);
    }

    private static List<String> strings(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : o.has(k) ? o.getAsJsonArray(k) : new JsonArray()) {
            out.add(e.getAsString());
        }
        return List.copyOf(out);
    }

    private static Number num(JsonObject o, String k, Number d) {
        return o.has(k) ? o.get(k).getAsNumber() : d;
    }
}
