package com.fundamentum.boss;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A boss arena: a box (half sizes x, y, z) or a cylinder (radius, half height) around the boss's spawn point or a fixed
 * centre. {@code lockIn} keeps engaged players inside (pushed back to the edge), {@code leash} brings the boss back to
 * the centre when it leaves, {@code resetWhenEmpty} heals and resets the boss after that many ticks with no player
 * inside (0 = never), {@code healthPerExtraPlayer} adds that share of max health for each player inside past the first.
 */
public record ArenaSpec(boolean box, double rx, double ry, double rz, @Nullable Vec3 center, boolean locksIn, boolean leashes,
        int resetWhenEmpty, float healthPerExtraPlayer) {

    public static ArenaSpec radius(double radius, double halfHeight) {
        return new ArenaSpec(false, radius, halfHeight, radius, null, false, false, 0, 0f);
    }

    public static ArenaSpec box(double halfX, double halfY, double halfZ) {
        return new ArenaSpec(true, halfX, halfY, halfZ, null, false, false, 0, 0f);
    }

    public ArenaSpec at(Vec3 center) {
        return new ArenaSpec(box, rx, ry, rz, center, locksIn, leashes, resetWhenEmpty, healthPerExtraPlayer);
    }

    public ArenaSpec lockIn() {
        return new ArenaSpec(box, rx, ry, rz, center, true, leashes, resetWhenEmpty, healthPerExtraPlayer);
    }

    public ArenaSpec leash() {
        return new ArenaSpec(box, rx, ry, rz, center, locksIn, true, resetWhenEmpty, healthPerExtraPlayer);
    }

    public ArenaSpec resetWhenEmpty(int ticks) {
        return new ArenaSpec(box, rx, ry, rz, center, locksIn, leashes, ticks, healthPerExtraPlayer);
    }

    public ArenaSpec healthPerExtraPlayer(float share) {
        return new ArenaSpec(box, rx, ry, rz, center, locksIn, leashes, resetWhenEmpty, share);
    }

    /** Whether {@code p} is inside the arena centred on {@code c}. */
    public boolean contains(Vec3 c, Vec3 p) {
        double dy = Math.abs(p.y - c.y);
        if (dy > ry) return false;
        double dx = p.x - c.x, dz = p.z - c.z;
        if (box) return Math.abs(dx) <= rx && Math.abs(dz) <= rz;
        return dx * dx + dz * dz <= rx * rx;
    }

    /** The point inside the arena nearest {@code p}, pulled one block in from the edge. */
    public Vec3 clamp(Vec3 c, Vec3 p) {
        double y = Math.max(c.y - ry + 1, Math.min(c.y + ry - 1, p.y));
        double dx = p.x - c.x, dz = p.z - c.z;
        if (box) {
            return new Vec3(c.x + Math.max(-rx + 1, Math.min(rx - 1, dx)), y, c.z + Math.max(-rz + 1, Math.min(rz - 1, dz)));
        }
        double d = Math.sqrt(dx * dx + dz * dz);
        double max = Math.max(0, rx - 1);
        if (d <= max || d < 1e-6) return new Vec3(p.x, y, p.z);
        return new Vec3(c.x + dx / d * max, y, c.z + dz / d * max);
    }

    static ArenaSpec fromJson(JsonObject json) {
        String shape = json.has("shape") ? json.get("shape").getAsString() : (json.has("size") ? "box" : "radius");
        ArenaSpec a;
        if ("box".equals(shape)) {
            JsonArray size = json.getAsJsonArray("size");
            a = box(size.get(0).getAsDouble() / 2, size.get(1).getAsDouble() / 2, size.get(2).getAsDouble() / 2);
        } else {
            a = radius(json.get("radius").getAsDouble(), json.has("height") ? json.get("height").getAsDouble() / 2 : 16);
        }
        if (json.has("center") && json.get("center").isJsonArray()) {
            JsonArray c = json.getAsJsonArray("center");
            a = a.at(new Vec3(c.get(0).getAsDouble(), c.get(1).getAsDouble(), c.get(2).getAsDouble()));
        }
        if (json.has("lock_in") && json.get("lock_in").getAsBoolean()) a = a.lockIn();
        if (json.has("leash") && json.get("leash").getAsBoolean()) a = a.leash();
        if (json.has("reset_when_empty")) a = a.resetWhenEmpty(json.get("reset_when_empty").getAsInt());
        if (json.has("health_per_extra_player")) a = a.healthPerExtraPlayer(json.get("health_per_extra_player").getAsFloat());
        return a;
    }
}
