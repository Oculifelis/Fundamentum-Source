package com.fundamentum.pose;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.io.FileNotFoundException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How players hold items and the pose clips they play. Each item may have a binding, a client resource at
 * {@code assets/<item namespace>/player_pose/<item path>.json}; every field is optional:
 * <pre>{
 *   "arms": "two_handed",        // the third-person hold: two_handed (crossbow-style aim), bow, pistol (one arm along
 *                                // the look), staff, support (main arm set, off hand on a point of the item), spear,
 *                                // block or item; see {@link ArmPoses}
 *   "when": "always",            // or "using": only while the item is being used
 *   "main": [-45, -25, 0],       // staff and support: the main arm's angles in degrees (model convention, x negative
 *                                // raises it forward), written for a right-handed player
 *   "support": [0, 0, 3],        // support: where the off hand grips, in pixels from the main hand in the main arm's frame
 *   "hold": "clip",              // a clip looped while the item is held
 *   "fire": "clip", "reload": "clip", "charge": "clip",   // see {@link Clock} and the "charge" cue
 *   "cues": {"cast": "clip", "swing": ["slash_a", "slash_b"]},   // cue names (see {@link Poses#cue}) to clips; a list
 *                                // is a combo, walked by successive cues and started again after a pause
 *   "rest": "rest",              // or "aim": the main arm's base under a clip
 *   "first_person": {"arm": "grip", "grip": [0.4, -0.6, -1.2], "angles": [18, 30, 0],
 *                    "support": {"arm": "grip", "grip": [0, 0, -6], "angles": [14, -42, 0]}, "swing": true}
 *                                // the player's own arms in first person, see {@link FirstPersonHands}
 * }</pre>
 * Clip names are {@code namespace:name} or a bare name in the item's namespace, read from
 * {@code assets/<namespace>/player_anim/<name>.json} ({@link PoseClip}).
 *
 * <p>Blending: a new clip fades in over its blend_in ticks from a frozen copy of what was showing; a finished clip fades
 * out over its blend_out ticks to the hold clip or the item's own pose. Purely visual: nothing here touches gameplay.
 * Third person is applied for every player by {@link PoseClient}; first person is the item's own renderer's to apply,
 * through {@link #firstPerson} (e.g. from {@code IClientItemExtensions.applyForgeHandTransform}).
 *
 * <p>Moved here from Ultima Ratio (its client.pose.PosePlayer), so every mod shares one pose player.
 */
public final class PosePlayer {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");

    /**
     * When an item last fired and when its reload ends, for the "fire" and "reload" clips. A mod with guns registers one
     * ({@link #registerClock}); the first answer that is not "none" counts.
     */
    public interface Clock {
        /** Game time (ticks, with the partial tick) the stack last fired, or NaN for none. */
        default float fired(LivingEntity entity, ItemStack stack, float pt) {
            return Float.NaN;
        }

        /** The game tick the stack's running reload ends, or 0 for none. */
        default long reloadEnd(ItemStack stack) {
            return 0L;
        }

        /** Ticks the stack's full reload takes (stretches a "fit": "reload" clip), or 0. */
        default int reloadTicks(ItemStack stack) {
            return 0;
        }
    }

    private static final List<Clock> CLOCKS = new CopyOnWriteArrayList<>();

    public static void registerClock(Clock clock) {
        CLOCKS.add(clock);
    }

    /** One weighted contribution to a bone: values (PoseClip.RX..PZ) on a base ("aim", "rest" or "" for first person). */
    public record Layer(String base, float[] v, float w) {}

    /** What one entity shows this frame: layers per bone, in the clip's own (right-handed) bone names. */
    public static final class Sample {
        public final Map<String, List<Layer>> bones = new HashMap<>();
        public String rest = "rest";
        public boolean leftHanded;

        void add(String bone, String base, float[] v, float w) {
            if (v != null && w > 1.0E-3F) {
                this.bones.computeIfAbsent(bone, k -> new ArrayList<>()).add(new Layer(base, v, w));
            }
        }

        Sample scaled(float k) {
            Sample s = new Sample();
            s.rest = this.rest;
            s.leftHanded = this.leftHanded;
            this.bones.forEach((b, ls) -> ls.forEach(l -> s.add(b, l.base(), l.v(), l.w() * k)));
            return s;
        }

        void addAll(Sample o) {
            o.bones.forEach((b, ls) -> ls.forEach(l -> this.add(b, l.base(), l.v(), l.w())));
        }

        public boolean thirdPerson() {
            return this.bones.containsKey("right_arm") || this.bones.containsKey("left_arm");
        }
    }

    /** One item's binding (see the class comment). Angles in degrees, the support point in pixels. */
    public record Binding(String namespace, @Nullable String arms, String when, float @Nullable [] main, float @Nullable [] support,
                          @Nullable String hold, @Nullable String fire, @Nullable String reload, @Nullable String charge,
                          Map<String, List<String>> cues, String rest, @Nullable FirstPerson firstPerson) {}

    /**
     * The binding's "first_person" block: which arm {@link FirstPersonHands} draws holding the item ("grip", "palm",
     * "gun", "wand" or "none"), where (pixels in the held item's frame) and at what angles (degrees), how far past the
     * point the fist closes ("fist_in", pixels), and an optional second hand.
     */
    public record FirstPerson(String arm, float @Nullable [] grip, float @Nullable [] angles, @Nullable FirstPerson support, boolean swing,
                              float fistIn) {
        static @Nullable FirstPerson read(@Nullable JsonElement e) {
            if (e == null || !e.isJsonObject()) {
                return null;
            }
            JsonObject o = e.getAsJsonObject();
            return new FirstPerson(o.has("arm") ? o.get("arm").getAsString() : "grip", vec(o, "grip"), vec(o, "angles"),
                read(o.get("support")), !o.has("swing") || o.get("swing").getAsBoolean(), o.has("fist_in") ? o.get("fist_in").getAsFloat() : 0.0F);
        }
    }

    /** What an entity's pose is doing: resting (on the item's hold clip, if any) or playing one kind of clip. */
    enum Mode { REST, CHARGE, CUE, FIRE, RELOAD }

    /** "No shot seen yet" for {@link State#lastFire}: older than any shot. */
    static final float NO_FIRE = Float.NEGATIVE_INFINITY;

    /**
     * Per-entity playback. The mode only changes through {@link #play}, {@link #release} and {@link #hold}, so a charge
     * never survives a shot or reload, and nothing (clip, blend, combo) carries over to the next item.
     */
    static final class State {
        String item = "";
        Mode mode = Mode.REST;
        PoseClip clip;
        float start;
        float speed = 1.0F;
        /** What was showing when the current clip began (or ended), faded out from blendFrom over blendTicks. */
        Sample from;
        float blendFrom;
        float blendTicks;
        /** The fire time last seen (game ticks, {@link #NO_FIRE} for none), and when the last cue of a combo came. */
        float lastFire = NO_FIRE;
        long lastReload;
        int combo;
        float lastCombo = -1000.0F;
        long frame = -1L;
        float framePt = -1.0F;
        Sample cached;

        /** Starts {@code clip} as {@code mode} at {@code now}, fading in over {@code from} (what was showing). */
        void play(Mode mode, PoseClip clip, Sample from, float now, float speed) {
            this.mode = mode;
            this.from = from;
            this.blendFrom = now;
            this.blendTicks = clip.blendIn;
            this.clip = clip;
            this.start = now;
            this.speed = speed;
        }

        /** Back to rest from {@code at}, fading out {@code from} over {@code blendTicks}. */
        void release(Sample from, float at, float blendTicks) {
            this.mode = Mode.REST;
            this.from = from;
            this.blendFrom = at;
            this.blendTicks = blendTicks;
            this.clip = null;
        }

        /** A new item in hand: everything starts over, and the item's last shot and reload are not replayed. */
        void hold(String item, float lastFire, long lastReload) {
            this.item = item;
            this.mode = Mode.REST;
            this.clip = null;
            this.from = null;
            this.blendTicks = 0.0F;
            this.speed = 1.0F;
            this.combo = 0;
            this.lastCombo = -1000.0F;
            this.lastFire = Float.isNaN(lastFire) ? NO_FIRE : lastFire;
            this.lastReload = lastReload;
        }
    }

    private static final Map<String, Optional<Binding>> BINDINGS = new ConcurrentHashMap<>();
    private static final Map<String, Optional<PoseClip>> CLIPS = new ConcurrentHashMap<>();
    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();
    /** The level STATES belong to: entity ids mean nothing in another one (a dimension change, a new world). */
    private static @Nullable Object statesLevel;

    /** Drops everything loaded (resource reload) and every entity's playback. */
    public static void reset() {
        BINDINGS.clear();
        CLIPS.clear();
        STATES.clear();
    }

    // ------------------------------------------------------------------------------------------------ loading
    /** The binding of the item in a stack, or null when it has none. */
    public static @Nullable Binding binding(ItemStack stack) {
        return stack.isEmpty() ? null : binding(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    static @Nullable Binding binding(Identifier item) {
        return BINDINGS.computeIfAbsent(item.toString(), key -> {
            JsonObject o = json(Identifier.fromNamespaceAndPath(item.getNamespace(), "player_pose/" + item.getPath() + ".json"));
            if (o == null) {
                return Optional.empty();
            }
            Map<String, List<String>> cues = new HashMap<>();
            if (o.has("cues")) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("cues").entrySet()) {
                    List<String> list = new ArrayList<>();
                    if (e.getValue().isJsonArray()) {
                        e.getValue().getAsJsonArray().forEach(x -> list.add(x.getAsString()));
                    } else {
                        list.add(e.getValue().getAsString());
                    }
                    cues.put(e.getKey(), list);
                }
            }
            return Optional.of(new Binding(item.getNamespace(), str(o, "arms"), o.has("when") ? o.get("when").getAsString() : "always",
                vec(o, "main"), vec(o, "support"), str(o, "hold"), str(o, "fire"), str(o, "reload"), str(o, "charge"), cues,
                o.has("rest") ? o.get("rest").getAsString() : "rest", FirstPerson.read(o.get("first_person"))));
        }).orElse(null);
    }

    /** A clip by name ({@code namespace:name}, or a bare name in {@code namespace}), or null. */
    public static @Nullable PoseClip clip(String namespace, @Nullable String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Identifier id = name.contains(":") ? Identifier.parse(name) : Identifier.fromNamespaceAndPath(namespace, name);
        return CLIPS.computeIfAbsent(id.toString(), n -> {
            JsonObject o = json(Identifier.fromNamespaceAndPath(id.getNamespace(), "player_anim/" + id.getPath() + ".json"));
            if (o == null) {
                LOG.warn("Fundamentum poses: no clip {}", id);
                return Optional.empty();
            }
            List<PoseClip> all = PoseClip.read(o);
            return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
        }).orElse(null);
    }

    private static @Nullable PoseClip clip(Binding b, @Nullable String name) {
        return b == null ? null : clip(b.namespace(), name);
    }

    /** The file as JSON, or null: quietly when there is none (most items have no pose), with a warning when it is broken. */
    private static @Nullable JsonObject json(Identifier path) {
        try (Reader reader = Minecraft.getInstance().getResourceManager().openAsReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (FileNotFoundException e) {
            return null;
        } catch (Exception e) {
            LOG.warn("Fundamentum poses: could not read {}: {}", path, e.toString());
            return null;
        }
    }

    private static @Nullable String str(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsString() : null;
    }

    private static float @Nullable [] vec(JsonObject o, String k) {
        if (!o.has(k)) {
            return null;
        }
        JsonArray a = o.getAsJsonArray(k);
        return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    private static String itemKey(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    // ------------------------------------------------------------------------------------------------ cues
    /**
     * A moment happened for an entity (from {@link Poses#cue}, or called on the client directly): starts the clip the
     * held item binds to it. "charge" starts the charge clip, which loops until the next cue.
     */
    public static void cue(int entityId, String cue) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Entity e = level.getEntity(entityId);
        if (!(e instanceof LivingEntity living)) {
            return;
        }
        Binding b = binding(living.getMainHandItem());
        if (b == null) {
            return;
        }
        float now = level.getGameTime() + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        forLevel(level);
        State st = STATES.computeIfAbsent(entityId, k -> new State());
        if ("charge".equals(cue)) {
            play(st, Mode.CHARGE, clip(b, b.charge()), now, 1.0F);
            return;
        }
        if (st.mode == Mode.CHARGE) {
            // any other cue ends a charge, even one with no clip of its own
            st.release(composite(st, now), now, 3.0F);
        }
        List<String> list = b.cues().get(cue);
        if (list == null || list.isEmpty()) {
            return;
        }
        String name;
        if (list.size() > 1) {
            // a combo: successive cues walk the list, a pause starts it again
            st.combo = now - st.lastCombo > 24.0F ? 0 : (st.combo + 1) % list.size();
            st.lastCombo = now;
            name = list.get(st.combo);
        } else {
            name = list.get(0);
        }
        play(st, Mode.CUE, clip(b, name), now, 1.0F);
    }

    private static void play(State st, Mode mode, PoseClip clip, float now, float speed) {
        if (clip != null) {
            st.play(mode, clip, composite(st, now), now, speed);
        }
    }

    private static void forLevel(Object level) {
        if (level != statesLevel) {
            STATES.clear();
            statesLevel = level;
        }
    }

    // ------------------------------------------------------------------------------------------------ sampling
    /** What {@code entity} shows this frame for the item in its main hand, or null when it plays no pose. */
    public static @Nullable Sample sample(LivingEntity entity, float pt) {
        var level = Minecraft.getInstance().level;
        if (level == null || entity == null) {
            return null;
        }
        ItemStack stack = entity.getMainHandItem();
        Binding b = binding(stack);
        forLevel(level);
        State st = STATES.get(entity.getId());
        if (b == null || b.hold() == null && b.fire() == null && b.reload() == null && b.charge() == null && b.cues().isEmpty()) {
            if (st != null) {
                STATES.remove(entity.getId());
            }
            return null;
        }
        if (st == null) {
            if (STATES.size() > 256) {
                // entities long gone (another level, out of range): start over
                STATES.clear();
            }
            st = new State();
            STATES.put(entity.getId(), st);
        }
        long tick = level.getGameTime();
        if (st.frame == tick && st.framePt == pt && st.cached != null) {
            return st.cached;
        }
        float now = tick + pt;
        String item = itemKey(stack);
        if (!item.equals(st.item)) {
            st.hold(item, fired(entity, stack, pt), reloadEnd(stack));
        }
        // a shot
        float fired = fired(entity, stack, pt);
        if (b.fire() != null && !Float.isNaN(fired) && fired - st.lastFire > 0.5F && now - fired < 4.0F) {
            play(st, Mode.FIRE, clip(b, b.fire()), Math.max(fired, now - 1.0F), 1.0F);
            st.start = fired;
        }
        if (!Float.isNaN(fired)) {
            st.lastFire = fired;
        }
        // a reload, stretched over its length
        long end = reloadEnd(stack);
        int reload = reloadTicks(stack);
        if (b.reload() != null && reload > 0 && end > 0L && end != st.lastReload && end > tick) {
            PoseClip rc = clip(b, b.reload());
            if (rc != null) {
                play(st, Mode.RELOAD, rc, now, "reload".equals(rc.fit) ? rc.length * 20.0F / reload : 1.0F);
                st.start = end - reload;
            }
        }
        st.lastReload = end;
        // a charge with no word from the server for five seconds has been lost (the cue's player left range)
        if (st.mode == Mode.CHARGE && now - st.start > 100.0F) {
            st.release(composite(st, now), now, 3.0F);
        }
        // a finished clip lets go
        if (st.clip != null && !st.clip.loop && (now - st.start) * st.speed / 20.0F >= st.clip.length) {
            float endAt = st.start + st.clip.length * 20.0F / st.speed;
            st.release(layers(st.clip, st.clip.length, null), endAt, st.clip.blendOut);
        }
        Sample s = composite(st, now);
        s.rest = b.rest();
        s.leftHanded = entity.getMainArm() == HumanoidArm.LEFT;
        st.frame = tick;
        st.framePt = pt;
        st.cached = s.bones.isEmpty() ? null : s;
        return st.cached;
    }

    /** The current composite: the playing clip (or the hold clip), faded in over the frozen copy it replaced. */
    private static Sample composite(State st, float now) {
        Binding b = st.item.isEmpty() ? null : binding(Identifier.parse(st.item));
        Sample cur;
        if (st.clip != null) {
            cur = layers(st.clip, (now - st.start) * st.speed / 20.0F, null);
        } else {
            PoseClip hold = b == null ? null : clip(b, b.hold());
            cur = hold == null ? new Sample() : layers(hold, now / 20.0F, null);
        }
        if (st.from == null || st.blendTicks <= 0.0F) {
            return cur;
        }
        float k = Mth.clamp((now - st.blendFrom) / st.blendTicks, 0.0F, 1.0F);
        if (k >= 1.0F) {
            st.from = null;
            return cur;
        }
        k = k * k * (3.0F - 2.0F * k);
        Sample out = st.from.scaled(1.0F - k);
        out.addAll(cur.scaled(k));
        return out;
    }

    private static Sample layers(PoseClip clip, float seconds, Sample into) {
        Sample s = into == null ? new Sample() : into;
        for (String bone : new String[] {"right_arm", "left_arm"}) {
            if (clip.has(bone)) {
                s.add(bone, clip.base.getOrDefault(bone, "rest"), clip.sample(bone, seconds), 1.0F);
            }
        }
        if (clip.has("first_person")) {
            s.add("first_person", "", clip.sample("first_person", seconds), 1.0F);
        }
        return s;
    }

    private static float fired(LivingEntity entity, ItemStack stack, float pt) {
        for (Clock clock : CLOCKS) {
            float f = clock.fired(entity, stack, pt);
            if (!Float.isNaN(f)) {
                return f;
            }
        }
        return Float.NaN;
    }

    private static long reloadEnd(ItemStack stack) {
        for (Clock clock : CLOCKS) {
            long end = clock.reloadEnd(stack);
            if (end > 0L) {
                return end;
            }
        }
        return 0L;
    }

    private static int reloadTicks(ItemStack stack) {
        for (Clock clock : CLOCKS) {
            int ticks = clock.reloadTicks(stack);
            if (ticks > 0) {
                return ticks;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------------------------------------ applying it
    /**
     * Third person ({@link ArmPoses#CLIP}'s transformer): puts the arms the sample moves on their clip pose, blended with
     * what the model already has for the weight the clips leave over (the main arm starts from the item's rest pose).
     */
    public static void applyArms(HumanoidModel<?> model, @Nullable Sample s, HumanoidArm mainArm) {
        if (s == null) {
            return;
        }
        boolean left = mainArm == HumanoidArm.LEFT;
        for (String bone : new String[] {"right_arm", "left_arm"}) {
            List<Layer> ls = s.bones.get(bone);
            boolean main = "right_arm".equals(bone);
            ModelPart part = (main != left) ? model.rightArm : model.leftArm;
            float side = (main != left) ? 1.0F : -1.0F;
            if (main) {
                base(model, part, s.rest, side);
            }
            if (ls == null) {
                continue;
            }
            float total = 0.0F;
            for (Layer l : ls) {
                total += l.w();
            }
            float keep = Math.max(0.0F, 1.0F - total);
            float x = part.xRot * keep, y = part.yRot * keep, z = part.zRot * keep;
            float px = 0.0F, py = 0.0F, pz = 0.0F;
            for (Layer l : ls) {
                float[] b = baseAngles(model, l.base(), side);
                float w = l.w() / Math.max(1.0F, total);
                float[] v = l.v();
                // a left-handed player's arms mirror the right-handed clip: yaw and roll turn the other way
                float m = left ? -1.0F : 1.0F;
                x += w * (b[0] + v[PoseClip.RX] * Mth.DEG_TO_RAD);
                y += w * (b[1] + m * v[PoseClip.RY] * Mth.DEG_TO_RAD);
                z += w * (b[2] + m * v[PoseClip.RZ] * Mth.DEG_TO_RAD);
                px += w * m * v[PoseClip.PX];
                py += w * v[PoseClip.PY];
                pz += w * v[PoseClip.PZ];
            }
            part.xRot = x;
            part.yRot = y;
            part.zRot = z;
            part.x += px;
            part.y += py;
            part.z += pz;
        }
    }

    private static void base(HumanoidModel<?> model, ModelPart part, String base, float side) {
        float[] b = baseAngles(model, base, side);
        part.xRot = b[0];
        part.yRot = b[1];
        part.zRot = b[2];
    }

    /** "aim": the arm along the head's look, a touch inward, as the pistol and crossbow aims are; "rest": hanging. */
    private static float[] baseAngles(HumanoidModel<?> model, String base, float side) {
        if ("aim".equals(base)) {
            return new float[] {model.head.xRot - Mth.HALF_PI, model.head.yRot - side * 0.08F, 0.0F};
        }
        return new float[] {0.0F, 0.0F, 0.0F};
    }

    /**
     * First person: moves the held hand (and the item and the arm drawn from the same pose) by the sample's
     * "first_person" bone, turning about {@code pivot} (the grip, in blocks). Returns true when anything moved.
     */
    public static boolean firstPerson(PoseStack pose, LivingEntity player, HumanoidArm arm, float pt, float @Nullable [] pivot) {
        Sample s = sample(player, pt);
        if (s == null) {
            return false;
        }
        List<Layer> ls = s.bones.get("first_person");
        if (ls == null) {
            return false;
        }
        float[] v = new float[6];
        for (Layer l : ls) {
            for (int i = 0; i < 6; i++) {
                v[i] += l.v()[i] * l.w();
            }
        }
        float m = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        pose.translate(m * v[PoseClip.PX] / 16.0F, v[PoseClip.PY] / 16.0F, v[PoseClip.PZ] / 16.0F);
        float gx = pivot == null ? 0.0F : pivot[0], gy = pivot == null ? 0.0F : pivot[1], gz = pivot == null ? 0.0F : pivot[2];
        pose.translate(gx, gy, gz);
        pose.mulPose(Axis.YP.rotationDegrees(m * v[PoseClip.RY]));
        pose.mulPose(Axis.XP.rotationDegrees(v[PoseClip.RX]));
        pose.mulPose(Axis.ZP.rotationDegrees(m * v[PoseClip.RZ]));
        pose.translate(-gx, -gy, -gz);
        return true;
    }

    /** True while {@code player} has a first-person pose playing. */
    public static boolean firstPersonActive(LivingEntity player, float pt) {
        Sample s = sample(player, pt);
        return s != null && s.bones.containsKey("first_person");
    }

    private PosePlayer() {}
}
