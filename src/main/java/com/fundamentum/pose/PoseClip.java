package com.fundamentum.pose;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One keyframed player pose clip, read from {@code assets/<namespace>/player_anim/<clip>.json} in the GeckoLib / Blockbench
 * animation format: {"animations": {"&lt;name&gt;": {"animation_length", "loop", "bones": {bone: {"rotation" | "position":
 * {"&lt;seconds&gt;": [x, y, z] | {"vector": [x, y, z], "easing": "...", "lerp_mode": "catmullrom"}}}}, "fundamentum": {...}}}}
 * (Ultima Ratio's files name that last block "ultimaratio"; both are read). The files open and edit in Blockbench.
 *
 * <p>Bones: "right_arm" and "left_arm" (third person, written for a right-handed player; {@link PosePlayer} mirrors them
 * for a left-handed one) and "first_person" (the held hand in first person). Third-person rotations are degrees in the
 * model's own convention (x negative raises the arm forward), positions model pixels; each arm's value is added to its
 * base ("rest": hanging, or "aim": along the head's look, from the clip's "fundamentum" block: {"base": {"right_arm":
 * "aim"}, "blend_in": 2, "blend_out": 3, "fit": "reload"}). First-person rotations are degrees about the grip (x pitch
 * up, y yaw inward, z roll inward), positions pixels in view space (x right, y up, z back).
 *
 * <p>Moved here from Ultima Ratio so every mod shares one clip format and player.
 */
public final class PoseClip {
    public static final int RX = 0, RY = 1, RZ = 2, PX = 3, PY = 4, PZ = 5;

    /** One keyframed channel group (rotation or position) of one bone: three values per key. */
    static final class Track {
        final float[] t;
        final float[][] v;
        final String[] easing;
        final boolean[] smooth;

        Track(float[] t, float[][] v, String[] easing, boolean[] smooth) {
            this.t = t;
            this.v = v;
            this.easing = easing;
            this.smooth = smooth;
        }

        void eval(float time, float[] out, int at) {
            int n = this.t.length;
            if (n == 0) {
                return;
            }
            if (time <= this.t[0] || n == 1) {
                System.arraycopy(this.v[0], 0, out, at, 3);
                return;
            }
            if (time >= this.t[n - 1]) {
                System.arraycopy(this.v[n - 1], 0, out, at, 3);
                return;
            }
            int i = 0;
            while (i < n - 2 && time > this.t[i + 1]) {
                i++;
            }
            float k = (time - this.t[i]) / Math.max(1.0E-4F, this.t[i + 1] - this.t[i]);
            // the easing named on the key being approached shapes the way in (GeckoLib's rule)
            k = ease(this.easing[i + 1], k);
            for (int c = 0; c < 3; c++) {
                float p1 = this.v[i][c], p2 = this.v[i + 1][c];
                if (this.smooth[i + 1]) {
                    float p0 = this.v[Math.max(0, i - 1)][c], p3 = this.v[Math.min(n - 1, i + 2)][c];
                    float k2 = k * k, k3 = k2 * k;
                    out[at + c] = 0.5F * (2 * p1 + (-p0 + p2) * k + (2 * p0 - 5 * p1 + 4 * p2 - p3) * k2 + (-p0 + 3 * p1 - 3 * p2 + p3) * k3);
                } else {
                    out[at + c] = p1 + (p2 - p1) * k;
                }
            }
        }
    }

    public final String name;
    /** Seconds. */
    public final float length;
    public final boolean loop;
    /** Ticks to blend in from whatever was showing, and back out when the clip ends. */
    public final float blendIn, blendOut;
    /** "aim" or "rest" per third-person bone; arms not named stay on their own (vanilla) pose. */
    public final Map<String, String> base = new HashMap<>();
    /** "reload": the clip is stretched to the weapon's reload; "" plays it at its own speed. */
    public final String fit;
    final Map<String, Track[]> bones = new HashMap<>();

    PoseClip(String name, JsonObject o) {
        this.name = name;
        this.length = o.has("animation_length") ? o.get("animation_length").getAsFloat() : 1.0F;
        this.loop = o.has("loop") && (o.get("loop").isJsonPrimitive() && (o.get("loop").getAsJsonPrimitive().isBoolean()
            ? o.get("loop").getAsBoolean() : "loop".equals(o.get("loop").getAsString())));
        JsonObject q = o.has("fundamentum") ? o.getAsJsonObject("fundamentum")
            : o.has("ultimaratio") ? o.getAsJsonObject("ultimaratio") : new JsonObject();
        this.blendIn = q.has("blend_in") ? q.get("blend_in").getAsFloat() : 2.0F;
        this.blendOut = q.has("blend_out") ? q.get("blend_out").getAsFloat() : 3.0F;
        this.fit = q.has("fit") ? q.get("fit").getAsString() : "";
        if (q.has("base")) {
            for (Map.Entry<String, JsonElement> e : q.getAsJsonObject("base").entrySet()) {
                this.base.put(e.getKey(), e.getValue().getAsString());
            }
        }
        JsonObject bones = o.has("bones") ? o.getAsJsonObject("bones") : new JsonObject();
        for (Map.Entry<String, JsonElement> b : bones.entrySet()) {
            JsonObject bo = b.getValue().getAsJsonObject();
            this.bones.put(b.getKey(), new Track[] {track(bo.get("rotation")), track(bo.get("position"))});
        }
    }

    /** Reads every animation in one file. */
    public static List<PoseClip> read(JsonObject file) {
        List<PoseClip> out = new ArrayList<>();
        JsonObject anims = file.getAsJsonObject("animations");
        for (Map.Entry<String, JsonElement> e : anims.entrySet()) {
            out.add(new PoseClip(e.getKey(), e.getValue().getAsJsonObject()));
        }
        return out;
    }

    public boolean has(String bone) {
        return this.bones.containsKey(bone);
    }

    /**
     * The bone's six values (RX..PZ) at {@code seconds}, or null when the clip does not move it. A looping clip wraps; a
     * finished one holds its last key.
     */
    public float[] sample(String bone, float seconds) {
        Track[] tr = this.bones.get(bone);
        if (tr == null) {
            return null;
        }
        float t = this.loop && this.length > 0.0F ? seconds % this.length : Math.min(seconds, this.length);
        float[] out = new float[6];
        if (tr[0] != null) {
            tr[0].eval(t, out, RX);
        }
        if (tr[1] != null) {
            tr[1].eval(t, out, PX);
        }
        return out;
    }

    private static Track track(JsonElement el) {
        if (el == null) {
            return null;
        }
        if (el.isJsonArray()) {
            // a constant: [x, y, z]
            return new Track(new float[] {0.0F}, new float[][] {vec(el.getAsJsonArray())}, new String[] {"linear"}, new boolean[] {false});
        }
        List<Map.Entry<String, JsonElement>> keys = new ArrayList<>(el.getAsJsonObject().entrySet());
        keys.sort((a, b) -> Float.compare(Float.parseFloat(a.getKey()), Float.parseFloat(b.getKey())));
        int n = keys.size();
        float[] t = new float[n];
        float[][] v = new float[n][];
        String[] easing = new String[n];
        boolean[] smooth = new boolean[n];
        for (int i = 0; i < n; i++) {
            t[i] = Float.parseFloat(keys.get(i).getKey());
            JsonElement k = keys.get(i).getValue();
            if (k.isJsonArray()) {
                v[i] = vec(k.getAsJsonArray());
                easing[i] = "linear";
            } else {
                JsonObject ko = k.getAsJsonObject();
                v[i] = vec(ko.has("vector") ? ko.getAsJsonArray("vector") : ko.has("post") ? ko.getAsJsonArray("post") : ko.getAsJsonArray("pre"));
                easing[i] = ko.has("easing") ? ko.get("easing").getAsString() : "linear";
                smooth[i] = ko.has("lerp_mode") && "catmullrom".equals(ko.get("lerp_mode").getAsString());
            }
        }
        return new Track(t, v, easing, smooth);
    }

    private static float[] vec(JsonArray a) {
        return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    /** GeckoLib's easing names (the common subset). */
    static float ease(String name, float k) {
        switch (name) {
            case "easeInSine": return 1.0F - (float) Math.cos(k * Math.PI / 2.0);
            case "easeOutSine": return (float) Math.sin(k * Math.PI / 2.0);
            case "easeInOutSine": return -((float) Math.cos(Math.PI * k) - 1.0F) / 2.0F;
            case "easeInQuad": return k * k;
            case "easeOutQuad": return 1.0F - (1.0F - k) * (1.0F - k);
            case "easeInOutQuad": return k < 0.5F ? 2.0F * k * k : 1.0F - (-2.0F * k + 2.0F) * (-2.0F * k + 2.0F) / 2.0F;
            case "easeInCubic": return k * k * k;
            case "easeOutCubic": return 1.0F - (1.0F - k) * (1.0F - k) * (1.0F - k);
            case "easeInOutCubic": return k < 0.5F ? 4.0F * k * k * k : 1.0F - (float) Math.pow(-2.0F * k + 2.0F, 3) / 2.0F;
            case "easeOutBack": {
                float c1 = 1.70158F, c3 = c1 + 1.0F;
                return 1.0F + c3 * (float) Math.pow(k - 1.0F, 3) + c1 * (float) Math.pow(k - 1.0F, 2);
            }
            case "easeInBack": {
                float c1 = 1.70158F, c3 = c1 + 1.0F;
                return c3 * k * k * k - c1 * k * k;
            }
            case "step": return k < 1.0F ? 0.0F : 1.0F;
            default: return k;
        }
    }
}
