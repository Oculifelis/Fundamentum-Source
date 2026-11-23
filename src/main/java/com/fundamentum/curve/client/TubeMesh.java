package com.fundamentum.curve.client;

import com.fundamentum.curve.Curve;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.util.List;

/**
 * Draws a round tube swept along {@link Curve} frames, for block-entity (or any) renderers: hand it the frames (moved
 * so they are relative to the pose's origin), and a vertex consumer of an entity render type, e.g.
 * <pre>
 * collector.submitCustomGeometry(pose, RenderTypes.entityTranslucent(texture),
 *         (p, vc) -> TubeMesh.tube(p, vc, frames, 0.375f, 12, 0.5f, 0xFFFFFFFF, lights, true));
 * </pre>
 * The texture wraps once around the tube (u) and repeats along it every {@code 1 / vPerBlock} blocks (v), so a 16x16
 * texture with a band near its top draws a ring at that spacing.
 */
public final class TubeMesh {
    private TubeMesh() {
    }

    /**
     * The tube's wall from the first frame to the last.
     *
     * @param radius    in blocks
     * @param sides     faces around (8 to 16 looks round)
     * @param vPerBlock texture repeats per block along the tube
     * @param argb      vertex colour (white for the texture as it is)
     * @param lights    packed light per frame (same length as frames), or null for full bright
     * @param inside    also draw the inside, so it shows from within and through translucent walls
     */
    public static void tube(PoseStack.Pose pose, VertexConsumer vc, List<Curve.Frame> frames, float radius, int sides, float vPerBlock,
                            int argb, int[] lights, boolean inside) {
        for (int i = 0; i + 1 < frames.size(); i++) {
            Curve.Frame a = frames.get(i);
            Curve.Frame b = frames.get(i + 1);
            int la = lights == null ? 0xF000F0 : lights[i];
            int lb = lights == null ? 0xF000F0 : lights[i + 1];
            float va = (float) (a.distance() * vPerBlock);
            float vb = (float) (b.distance() * vPerBlock);
            for (int j = 0; j < sides; j++) {
                double a0 = Math.PI * 2 * j / sides;
                double a1 = Math.PI * 2 * (j + 1) / sides;
                float u0 = (float) j / sides;
                float u1 = (float) (j + 1) / sides;
                Curve.Vec n00 = radial(a, a0);
                Curve.Vec n01 = radial(a, a1);
                Curve.Vec n10 = radial(b, a0);
                Curve.Vec n11 = radial(b, a1);
                // Outside, counter-clockwise seen from outside.
                vertex(pose, vc, a.pos(), n00, radius, u0, va, argb, la, 1);
                vertex(pose, vc, b.pos(), n10, radius, u0, vb, argb, lb, 1);
                vertex(pose, vc, b.pos(), n11, radius, u1, vb, argb, lb, 1);
                vertex(pose, vc, a.pos(), n01, radius, u1, va, argb, la, 1);
                if (inside) {
                    vertex(pose, vc, a.pos(), n01, radius, u1, va, argb, la, -1);
                    vertex(pose, vc, b.pos(), n11, radius, u1, vb, argb, lb, -1);
                    vertex(pose, vc, b.pos(), n10, radius, u0, vb, argb, lb, -1);
                    vertex(pose, vc, a.pos(), n00, radius, u0, va, argb, la, -1);
                }
            }
        }
    }

    /**
     * A short ring band around the tube at one frame ({@code width} blocks long, centred there), e.g. a coupling or a
     * flange; its texture spans {@code v0..v1}.
     */
    public static void band(PoseStack.Pose pose, VertexConsumer vc, Curve.Frame at, float radius, float width, int sides, float v0, float v1,
                            int argb, int light) {
        Curve.Vec half = at.tangent().scale(width / 2.0);
        Curve.Frame a = new Curve.Frame(at.pos().sub(half), at.tangent(), at.normal(), at.binormal(), 0);
        Curve.Frame b = new Curve.Frame(at.pos().add(half), at.tangent(), at.normal(), at.binormal(), width);
        float vPerBlock = (v1 - v0) / Math.max(1e-4f, width);
        for (int j = 0; j < sides; j++) {
            double a0 = Math.PI * 2 * j / sides;
            double a1 = Math.PI * 2 * (j + 1) / sides;
            float u0 = (float) j / sides;
            float u1 = (float) (j + 1) / sides;
            vertex(pose, vc, a.pos(), radial(a, a0), radius, u0, v0, argb, light, 1);
            vertex(pose, vc, b.pos(), radial(b, a0), radius, u0, v0 + width * vPerBlock, argb, light, 1);
            vertex(pose, vc, b.pos(), radial(b, a1), radius, u1, v0 + width * vPerBlock, argb, light, 1);
            vertex(pose, vc, a.pos(), radial(a, a1), radius, u1, v0, argb, light, 1);
        }
    }

    private static Curve.Vec radial(Curve.Frame f, double angle) {
        return f.normal().scale(Math.cos(angle)).add(f.binormal().scale(Math.sin(angle)));
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, Curve.Vec centre, Curve.Vec out, float radius, float u, float v,
                               int argb, int light, int side) {
        vc.vertex(pose.pose(), (float) (centre.x() + out.x() * radius), (float) (centre.y() + out.y() * radius), (float) (centre.z() + out.z() * radius))
                .color(argb)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(pose.normal(), (float) out.x() * side, (float) out.y() * side, (float) out.z() * side)
                .endVertex();
    }
}
