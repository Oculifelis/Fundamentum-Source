package com.fundamentum.whip;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * How one whip looks beyond its {@link WhipDefinition} texture and colours, registered on the client with
 * {@link WhipsClient#style}: geometry of its own along the cord (leaves, thorns, beads, a model per segment), and
 * client effects at the crack and when the tip strikes a block. Drawing only; the server's hits are unchanged. Every
 * method is optional.
 *
 * <pre>{@code
 * // in a client-only class, while the mod is constructed
 * WhipsClient.style(id("vine_whip"), new WhipStyle() {
 *     public void draw(Cord cord, PoseStack pose, SubmitNodeCollector out) {
 *         for (int i = 1; i < cord.points().length; i += 3) {   // a leaf on every third link
 *             pose.pushPose();
 *             pose.translate(cord.points()[i].x, cord.points()[i].y, cord.points()[i].z);
 *             // turn along cord.direction(i), submit a model or quads with cord.light()[i] ...
 *             pose.popPose();
 *         }
 *     }
 *     public void crack(ClientLevel level, Player player, Vec3 tip, RandomSource random) { petals(level, tip, 12); }
 * });
 * }</pre>
 */
public interface WhipStyle {
    /**
     * One strand of a cord as it is drawn this frame. {@code points} run from the handle (0) to the tip, relative to
     * the camera (the pose stack is at the camera, so draw at them directly); {@code light} is the packed light at each.
     * The points are the first {@code span} of the cord's length (1 mid-swing, the idle fraction between swings).
     */
    record Cord(Player player, WhipDefinition whip, int strand, Vec3[] points, int[] light, double span, boolean swinging, Vec3 camera) {
        /** How far along the whole cord point {@code i} is, 0 at the handle to 1 at the tip of a full cord. */
        public double along(int i) {
            return span * i / (points.length - 1);
        }

        /** The unit direction of the link from point {@code i} toward the tip (the last link's at the tip). */
        public Vec3 direction(int i) {
            int a = Math.min(i, points.length - 2);
            Vec3 d = points[a + 1].subtract(points[a]);
            return d.lengthSqr() < 1.0E-12 ? new Vec3(0, -1, 0) : d.normalize();
        }

        /** Point {@code i} in the world. */
        public Vec3 world(int i) {
            return points[i].add(camera);
        }
    }

    /** False to leave out Fundamentum's textured ribbon and draw the whole cord in {@link #draw}. */
    default boolean ribbon() {
        return true;
    }

    /** Draws extra geometry along one strand, after the ribbon. Called every frame for every drawn strand. */
    default void draw(Cord cord, PoseStack pose, SubmitNodeCollector collector) {}

    /** The tip passed the crack speed (once a swing), on every client that sees the swing; after the crack particle. */
    default void crack(ClientLevel level, Player player, Vec3 tip, RandomSource random) {}

    /** The tip snapped against {@code block} (see {@link WhipHooks#struckBlock}); after the block chips. */
    default void struck(ClientLevel level, Player player, Vec3 tip, BlockState block, RandomSource random) {}
}
