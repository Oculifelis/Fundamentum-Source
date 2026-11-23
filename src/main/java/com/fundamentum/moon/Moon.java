package com.fundamentum.moon;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleSupplier;
import net.minecraft.network.FriendlyByteBuf;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import org.jetbrains.annotations.Nullable;

/**
 * A moon: a night event defined in data, {@code data/<namespace>/fundamentum/moons/<name>.json}, with the id
 * {@code <namespace>:<name>}. Every field is optional:
 * <pre>{
 *   "chance": 0.05,                    // per eligible night (0 = only when called by an item or by code)
 *   "priority": 0,                     // moons are rolled from the highest priority down; the first that rises wins
 *   "dimension": "minecraft:overworld",
 *   "requires": ["mymod:dragon_slain"],   // world flags that must all be set before it rises by chance
 *   "full_moon": "any",                // "any" | "never" | "only"
 *   "moon_phases": [4],                // or only on these phases (0 full, 1-3 waning, 4 new, 5-7 waxing)
 *   "fog": {"color": "#2E5BCC", "strength": 0.45,     // tints the night air of its dimension on the clients, see Tint
 *           "fade": {"in": 2.25, "out": 2.25, "curve": "linear"}},   // seconds; default 5 x strength each way
 *   "sky": {"color": "#2E5BCC", "strength": 0.2, "fade": {...}},   // tints its sky and sunset the same way (color: the fog's)
 *   "effects": [{"effect": "minecraft:luck", "amplifier": 0}],   // kept on players in its dimension while it shines
 *   "spawns": [{"entity": "mymod:moth", "chance": 0.45, "cap": 4, "interval": 160}],
 *   "bonus_loot": "mymod:moons/harvest",          // rolled when a player kills a hostile creature under it
 *   "summon": {                                   // using this item at night calls the moon, see Summon
 *     "item": "mymod:harvest_lantern",            // ("summon_item": "..." on its own is the same)
 *     "consume": 1, "cooldown": 40,
 *     "sound": {"id": "minecraft:block.beacon.activate", "volume": 1.2, "pitch": 1.4},   // "" = silent
 *     "refusals": {"elsewhere": "<lang key>", "daylight": "<lang key>", "already": "<lang key>"}
 *   },
 *   "rise_message": "message.mymod.harvest_moon",  // or {"key", "color": "#hex" | "fog", "bold": true, "italic": false, "overlay": false}
 *   "rise_sound": "mymod:moonrise",                 // or {"id", "volume": 1, "pitch": 1}; played to everyone it rises over
 *   "set_message": "message.mymod.harvest_set"      // as rise_message; when it sets, to everyone it shone on
 * }</pre>
 * Any other field is the owning mod's own: read it from {@link #json()}.
 */
public record Moon(String id, double chance, int priority, String dimension, List<String> requires, FullMoon fullMoon, List<Integer> moonPhases,
                   Tint fog, Tint sky, List<Effect> effects, List<Spawn> spawns, String bonusLoot, Summon summon,
                   Message rise, Message set, Sound riseSound, JsonObject json) {

    public enum FullMoon { ANY, NEVER, ONLY }

    public record Effect(String effect, int amplifier) {}

    /** Every {@code interval} ticks, for each player with fewer than {@code cap} of the creature near, a {@code chance} roll. */
    public record Spawn(String entity, float chance, int cap, int interval) {}

    /** A sound by id ({@code ""} = none). Data: {@code "ns:id"} or {@code {"id", "volume", "pitch"}}. */
    public record Sound(String id, float volume, float pitch) {
        public static final Sound NONE = new Sound("", 1.0F, 1.0F);

        public boolean isEmpty() {
            return this.id.isEmpty();
        }

        static Sound parse(@Nullable JsonElement e, Sound d) {
            if (e == null) {
                return d;
            }
            if (e.isJsonPrimitive()) {
                return new Sound(e.getAsString(), 1.0F, 1.0F);
            }
            JsonObject o = e.getAsJsonObject();
            return new Sound(str(o, "id", ""), num(o, "volume", 1.0).floatValue(), num(o, "pitch", 1.0).floatValue());
        }
    }

    /**
     * A chat line by lang key ({@code ""} = none), in the moon's fog colour ({@code color} -1) or its own, bold by
     * default; {@code overlay} shows it above the hotbar instead. Data: {@code "<key>"} or
     * {@code {"key", "color": "#hex" | "fog", "bold", "italic", "overlay"}}.
     */
    public record Message(String key, int color, boolean bold, boolean italic, boolean overlay) {
        public static final Message NONE = new Message("", -1, true, false, false);

        public boolean isEmpty() {
            return this.key.isEmpty();
        }

        static Message parse(@Nullable JsonElement e) {
            if (e == null) {
                return NONE;
            }
            if (e.isJsonPrimitive()) {
                return new Message(e.getAsString(), -1, true, false, false);
            }
            JsonObject o = e.getAsJsonObject();
            String c = str(o, "color", "fog");
            return new Message(str(o, "key", ""), c.equals("fog") ? -1 : Moon.color(c), bool(o, "bold", true), bool(o, "italic", false),
                bool(o, "overlay", false));
        }
    }

    /**
     * The item that calls the moon ({@code ""} = none): {@code consume} of the stack is used up (0 = none; never in
     * creative), the item cools down {@code cooldown} ticks and {@code sound} plays. When it can't be used now the
     * player sees {@code refusals[elsewhere | daylight | already]} above the hotbar (default
     * {@code fundamentum.moon.<reason>}).
     */
    public record Summon(String item, int consume, int cooldown, Sound sound, Map<String, String> refusals) {
        public static final Sound DEFAULT_SOUND = new Sound("minecraft:block.beacon.activate", 1.2F, 1.4F);

        /** The lang key for a refusal: "elsewhere" (wrong dimension), "daylight" or "already" (a moon is up). */
        public String refusal(String reason) {
            return this.refusals.getOrDefault(reason, "fundamentum.moon." + reason);
        }

        static Summon parse(JsonObject moon) {
            JsonObject o = moon.has("summon") ? moon.getAsJsonObject("summon") : new JsonObject();
            Map<String, String> refusals = new HashMap<>();
            if (o.has("refusals")) {
                o.getAsJsonObject("refusals").entrySet().forEach(e -> refusals.put(e.getKey(), e.getValue().getAsString()));
            }
            return new Summon(str(o, "item", str(moon, "summon_item", "")), Math.max(0, num(o, "consume", 1).intValue()),
                Math.max(0, num(o, "cooldown", 40).intValue()), Sound.parse(o.get("sound"), DEFAULT_SOUND), Map.copyOf(refusals));
        }
    }

    /**
     * How a tint comes in as the moon rises ({@code in} seconds) and goes as it sets ({@code out} seconds; 0 = at once).
     * {@code linear} is steady; {@code ease_out} is fast at first and slows, a fixed share of what is left each tick, 99%
     * of the way at the time given (AM's old 0.02 a tick is ease_out over 11.5 s). Fading out uses the moon that set.
     */
    public record Fade(float in, float out, Curve curve) {
        public enum Curve { LINEAR, EASE_OUT }

        /** One client tick from {@code mix} toward {@code target}, for a tint of this {@code strength}. */
        public float step(float mix, float target, float strength) {
            float seconds = target > mix ? this.in : this.out;
            if (seconds <= 0.0F || strength <= 0.0F || Math.abs(target - mix) <= 1.0E-4F) {
                return target;
            }
            float ticks = seconds * 20.0F;
            if (this.curve == Curve.EASE_OUT) {
                float next = mix + (target - mix) * (1.0F - (float) Math.pow(0.01, 1.0 / ticks));
                return Math.abs(target - next) <= strength * 0.01F ? target : next;
            }
            float most = strength / ticks;
            return mix + Math.max(-most, Math.min(most, target - mix));
        }

        static Fade parse(@Nullable JsonObject o, float strength) {
            float d = 5.0F * strength;
            if (o == null) {
                return new Fade(d, d, Curve.LINEAR);
            }
            return new Fade(Math.max(0.0F, num(o, "in", d).floatValue()), Math.max(0.0F, num(o, "out", d).floatValue()),
                Curve.valueOf(str(o, "curve", "linear").toUpperCase(Locale.ROOT)));
        }
    }

    /**
     * A tint of the fog or the sky while the moon shines: its colour, how far toward it (0..1) and how it fades. Data:
     * {@code {"color": "#hex", "strength": 0.3, "fade": {"in": 5, "out": 5, "curve": "linear" | "ease_out"},
     * "shift": {"color": "#hex", "seconds": 8}}}. With a shift (1.15.0) the colour drifts smoothly to the shift colour
     * and back, once every {@code seconds}.
     */
    public record Tint(int color, float strength, Fade fade, int shiftColor, float shiftSeconds) {
        public static final Tint NONE = new Tint(0, 0.0F, new Fade(0.0F, 0.0F, Fade.Curve.LINEAR));

        public Tint(int color, float strength, Fade fade) {
            this(color, strength, fade, color, 0.0F);
        }

        /** The colour at a moment, in seconds of any steady clock: {@link #color()} unless the tint shifts. */
        public int colorAt(double seconds) {
            if (this.shiftSeconds <= 0.0F) {
                return this.color;
            }
            float k = (float) (0.5 - 0.5 * Math.cos(seconds / this.shiftSeconds * Math.PI * 2.0));
            int a = this.color, b = this.shiftColor;
            int r = Math.round((a >> 16 & 255) * (1.0F - k) + (b >> 16 & 255) * k);
            int g = Math.round((a >> 8 & 255) * (1.0F - k) + (b >> 8 & 255) * k);
            int bl = Math.round((a & 255) * (1.0F - k) + (b & 255) * k);
            return r << 16 | g << 8 | bl;
        }

        static Tint parse(@Nullable JsonObject o, int defaultColor) {
            if (o == null) {
                return new Tint(defaultColor, 0.0F, NONE.fade());
            }
            float strength = Math.max(0.0F, Math.min(1.0F, num(o, "strength", 0.0).floatValue()));
            int color = o.has("color") ? Moon.color(o.get("color").getAsString()) : defaultColor;
            JsonObject shift = o.has("shift") ? o.getAsJsonObject("shift") : null;
            return new Tint(color, strength, Fade.parse(o.has("fade") ? o.getAsJsonObject("fade") : null, strength),
                shift != null && shift.has("color") ? Moon.color(shift.get("color").getAsString()) : color,
                shift == null ? 0.0F : Math.max(0.0F, num(shift, "seconds", 8.0).floatValue()));
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeInt(this.color);
            buf.writeFloat(this.strength);
            buf.writeFloat(this.fade.in());
            buf.writeFloat(this.fade.out());
            buf.writeVarInt(this.fade.curve().ordinal());
            buf.writeInt(this.shiftColor);
            buf.writeFloat(this.shiftSeconds);
        }

        public static Tint read(FriendlyByteBuf buf) {
            int color = buf.readInt();
            float strength = buf.readFloat(), in = buf.readFloat(), out = buf.readFloat();
            Fade.Curve[] curves = Fade.Curve.values();
            Fade fade = new Fade(in, out, curves[Math.floorMod(buf.readVarInt(), curves.length)]);
            return new Tint(color, strength, fade, buf.readInt(), buf.readFloat());
        }
    }

    public static final String OVERWORLD = "minecraft:overworld";

    public int fogColor() {
        return this.fog.color();
    }

    public float fogStrength() {
        return this.fog.strength();
    }

    /** The item that calls this moon, or "". */
    public String summonItem() {
        return this.summon.item();
    }

    /** The rise message's lang key, or "". */
    public String riseMessage() {
        return this.rise.key();
    }

    /** A message's colour: its own, or this moon's fog colour. */
    public int color(Message m) {
        return m.color() < 0 ? this.fog.color() : m.color();
    }

    /** Whether this moon may rise tonight by chance: its dimension, its world flags ({@code flags} says which are set), the phase. */
    public boolean eligible(String dimension, Predicate<String> flags, int moonPhase) {
        if (!this.dimension.equals(dimension)) {
            return false;
        }
        for (String f : this.requires) {
            if (!flags.test(f)) {
                return false;
            }
        }
        int phase = Math.floorMod(moonPhase, 8);
        if (!this.moonPhases.isEmpty() && !this.moonPhases.contains(phase)) {
            return false;
        }
        return switch (this.fullMoon) {
            case ANY -> true;
            case NEVER -> phase != 0;
            case ONLY -> phase == 0;
        };
    }

    public static Moon parse(String id, JsonObject o) {
        Tint fogTint = Tint.parse(o.has("fog") ? o.getAsJsonObject("fog") : new JsonObject(), 0);
        List<Effect> effects = new ArrayList<>();
        for (JsonElement e : array(o, "effects")) {
            JsonObject eo = e.getAsJsonObject();
            effects.add(new Effect(eo.get("effect").getAsString(), num(eo, "amplifier", 0).intValue()));
        }
        List<Spawn> spawns = new ArrayList<>();
        for (JsonElement e : array(o, "spawns")) {
            JsonObject so = e.getAsJsonObject();
            spawns.add(new Spawn(so.get("entity").getAsString(), num(so, "chance", 0.3).floatValue(), num(so, "cap", 4).intValue(),
                Math.max(20, num(so, "interval", 160).intValue())));
        }
        List<String> requires = new ArrayList<>();
        for (JsonElement e : array(o, "requires")) {
            requires.add(e.getAsString());
        }
        List<Integer> phases = new ArrayList<>();
        for (JsonElement e : array(o, "moon_phases")) {
            phases.add(Math.floorMod(e.getAsInt(), 8));
        }
        return new Moon(id, num(o, "chance", 0.0).doubleValue(), num(o, "priority", 0).intValue(), str(o, "dimension", OVERWORLD),
            List.copyOf(requires), FullMoon.valueOf(str(o, "full_moon", "any").toUpperCase(Locale.ROOT)), List.copyOf(phases),
            fogTint, Tint.parse(o.has("sky") ? o.getAsJsonObject("sky") : null, fogTint.color()), List.copyOf(effects), List.copyOf(spawns), str(o, "bonus_loot", ""),
            Summon.parse(o), Message.parse(o.get("rise_message")), Message.parse(o.get("set_message")), Sound.parse(o.get("rise_sound"), Sound.NONE), o);
    }

    /** "#RRGGBB" or "RRGGBB". */
    public static int color(String s) {
        return Integer.parseInt(s.startsWith("#") ? s.substring(1) : s, 16) & 0xFFFFFF;
    }

    private static JsonArray array(JsonObject o, String k) {
        return o.has(k) ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static Number num(JsonObject o, String k, Number d) {
        return o.has(k) ? o.get(k).getAsNumber() : d;
    }

    private static String str(JsonObject o, String k, String d) {
        return o.has(k) ? o.get(k).getAsString() : d;
    }

    private static boolean bool(JsonObject o, String k, boolean d) {
        return o.has(k) ? o.get(k).getAsBoolean() : d;
    }

    /**
     * Tonight's moon, if any: the eligible moons rolled from the highest priority down (ties by id), each by its chance
     * tonight ({@code chanceOf}); the first that rises wins.
     */
    public static @Nullable Moon roll(List<Moon> moons, String dimension, Predicate<String> flags, int moonPhase, ToDoubleFunction<Moon> chanceOf,
                                      DoubleSupplier random) {
        List<Moon> order = new ArrayList<>(moons);
        order.sort((a, b) -> a.priority != b.priority ? Integer.compare(b.priority, a.priority) : a.id.compareTo(b.id));
        for (Moon m : order) {
            if (!m.eligible(dimension, flags, moonPhase)) {
                continue;
            }
            double c = chanceOf.applyAsDouble(m);
            if (c > 0 && random.getAsDouble() < c) {
                return m;
            }
        }
        return null;
    }
}
