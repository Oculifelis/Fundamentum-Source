package com.fundamentum.client;

import com.fundamentum.whip.WhipShaders;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Glow that survives shader packs (1.25.3), for whips, spears, bolts, beams and any other glowing geometry.
 *
 * <p><b>Why.</b> Iris replaces vanilla's shader programs with the shader pack's; a mod's own core shader is drawn
 * as it is into the pack's buffers and comes out flat or wrong. On 1.21.1 every glow type here is a vanilla one Iris
 * maps to the pack's emissive programs ({@code eyes}, {@code entityTranslucentEmissive}), and Fundamentum's animated
 * whip cords swap their own shader for vanilla's emissive entity shader while a pack is on ({@link #shadersOn}),
 * animating their vertex colours instead.
 *
 * <p><b>Use.</b> Draw glowing parts with one of these render types and full-bright light; nothing else is needed:
 * <ul>
 *   <li>{@link #solid}: opaque, cut out under 0.1 alpha, unlit (full colour at night), e.g. a glowing 3D part.</li>
 *   <li>{@link #translucent}: the same, blended.</li>
 *   <li>{@link #additive}: adds light, no depth write, both faces: energy, lightning cores, beams, sparks.</li>
 *   <li>{@link #halo}: {@link #additive} on a soft round falloff, for glows round a line ({@link #strip} with
 *   {@code u} across) or a dot ({@link #dot}).</li>
 * </ul>
 * Lines and bolts (camera-relative points, as a {@code SubmitCustomGeometryEvent} or a renderer's pose gives them):
 * <pre>{@code
 * Vec3[] path = Emissive.bolt(from, to, 12, 0.35, seed);           // a jagged lightning path, world or relative
 * Emissive.line(collector, pose, path, 0.06, 0xFFFFFFFF, 0.5, 0xA060A0FF);   // white core, blue glow round it
 * }</pre>
 * Vanilla's own {@code RenderTypes.eyes} and {@code entityTranslucentEmissive} are safe under packs too. Item model
 * faces with {@code "light_emission": 15} already draw through {@link #solid} / {@link #translucent}.
 *
 * <p>Iris (or Oculus) is optional: everything here is reflection, and without it {@link #shadersOn} is false.
 */
public final class Emissive {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    private static final ResourceLocation WHITE = ResourceLocation.fromNamespaceAndPath("fundamentum", "textures/misc/white.png");

    private static Object api;
    private static Method inUse;
    private static Method shadowPass;

    static {
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            api = apiClass.getMethod("getInstance").invoke(null);
            inUse = apiClass.getMethod("isShaderPackInUse");
            shadowPass = apiClass.getMethod("isRenderingShadowPass");
        } catch (Throwable t) {
            api = null;           // no Iris (or an Iris without the pipeline API): nothing to do
        }
    }

    private Emissive() { }

    /** Whether Iris (with its pipeline API) is loaded. */
    public static boolean irisLoaded() {
        return api != null;
    }

    /** Whether a shader pack is drawing the world now: custom fragment shaders are replaced, see the class notes. */
    public static boolean shadersOn() {
        if (api == null) {
            return false;
        }
        try {
            return (Boolean) inUse.invoke(api);
        } catch (Throwable t) {
            return false;
        }
    }

    /** How much stronger a soft glow (a halo's alpha) is drawn while a shader pack is on, see {@link #packGlow}. */
    public static final float PACK_GLOW = 1.6F;

    /**
     * A soft glow colour as it should be drawn now (1.25.4): unchanged without a shader pack; with one, its alpha (the
     * glow's strength) times {@link #PACK_GLOW}, since a pack's emissive program darkens mid tones (Complementary
     * raises the colour to the power 1.5) and its tone mapping and brighter night sky wash a faint additive glow out.
     */
    public static int packGlow(int argb) {
        if (!shadersOn()) {
            return argb;
        }
        int a = Math.min(255, Math.round((argb >>> 24) * PACK_GLOW));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Whether Iris is drawing its shadow map now (skip pure-light geometry there if it matters). */
    public static boolean shadowPass() {
        if (api == null) {
            return false;
        }
        try {
            return (Boolean) shadowPass.invoke(api);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Opaque, cut out under 0.1 alpha, unlit: full colour whatever the light. Vertex colours apply. */
    public static RenderType solid(ResourceLocation texture) {
        return WhipShaders.unlit(texture, false);
    }

    /** Blended, unlit. */
    public static RenderType translucent(ResourceLocation texture) {
        return WhipShaders.unlit(texture, true);
    }

    /** Additive light, no depth write, both faces, textured ({@code null}: plain white). On 1.21.1 vanilla's eyes type. */
    public static RenderType additive(ResourceLocation texture) {
        return RenderType.eyes(texture == null ? WHITE : texture);
    }

    /** {@link #additive} on a soft round falloff: strips with u across 0..1 at v 0.5, or quads over it for a dot. */
    public static RenderType halo() {
        return WhipShaders.halo();
    }

    /**
     * A camera-facing strip through camera-relative points, {@code width} wide at the first to {@code tipWidth} at the
     * last, u across (0..1), v along (0..1), full bright. For {@link #halo} it reads as a soft glow; for
     * {@link #additive} or {@link #solid} as a hard line.
     */
    public static void strip(PoseStack.Pose at, VertexConsumer buffer, Vec3[] p, double width, double tipWidth, int argb) {
        int n = p.length;
        if (n < 2) {
            return;
        }
        Vec3[] left = new Vec3[n], right = new Vec3[n], normal = new Vec3[n];
        for (int j = 0; j < n; j++) {
            Vec3 tangent = p[Math.min(n - 1, j + 1)].subtract(p[Math.max(0, j - 1)]);
            Vec3 side = tangent.cross(p[j].scale(-1));
            side = side.lengthSqr() < 1.0E-10 ? new Vec3(0, 1, 0) : side.normalize();
            double half = Mth.lerp((double) j / (n - 1), width, tipWidth) * 0.5;
            left[j] = p[j].add(side.scale(half));
            right[j] = p[j].subtract(side.scale(half));
            normal[j] = p[j].lengthSqr() < 1.0E-10 ? new Vec3(0, 1, 0) : p[j].scale(-1).normalize();
        }
        for (int j = 0; j < n - 1; j++) {
            float v0 = (float) j / (n - 1), v1 = (float) (j + 1) / (n - 1);
            vertex(at, buffer, left[j], argb, 0, v0, normal[j]);
            vertex(at, buffer, right[j], argb, 1, v0, normal[j]);
            vertex(at, buffer, right[j + 1], argb, 1, v1, normal[j + 1]);
            vertex(at, buffer, left[j + 1], argb, 0, v1, normal[j + 1]);
        }
    }

    /** A round camera-facing quad at camera-relative {@code c} (uv over the whole texture), full bright: for {@link #halo}. */
    public static void dot(PoseStack.Pose at, VertexConsumer buffer, Vec3 c, double radius, int argb) {
        Vec3 toEye = c.lengthSqr() < 1.0E-10 ? new Vec3(0, 0, 1) : c.scale(-1).normalize();
        Vec3 a = toEye.cross(Math.abs(toEye.y) < 0.95 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize().scale(radius);
        Vec3 b = toEye.cross(a).normalize().scale(radius);
        vertex(at, buffer, c.subtract(a).subtract(b), argb, 0, 0, toEye);
        vertex(at, buffer, c.add(a).subtract(b), argb, 1, 0, toEye);
        vertex(at, buffer, c.add(a).add(b), argb, 1, 1, toEye);
        vertex(at, buffer, c.subtract(a).add(b), argb, 0, 1, toEye);
    }

    /**
     * A glowing line: a hard {@link #additive} core {@code coreWidth} wide in {@code coreArgb}, and (when
     * {@code glowArgb}'s alpha is above 0) a soft {@link #halo} {@code glowWidth} wide round it. Camera-relative points.
     */
    public static void line(MultiBufferSource buffers, PoseStack pose, Vec3[] p, double coreWidth, int coreArgb, double glowWidth,
                            int glowArgb) {
        strip(pose.last(), buffers.getBuffer(additive(null)), p, coreWidth, coreWidth, coreArgb);
        if ((glowArgb >>> 24) > 0) {
            strip(pose.last(), buffers.getBuffer(halo()), p, glowWidth, glowWidth, glowArgb);
        }
    }

    /**
     * A jagged lightning path from {@code from} to {@code to}: {@code segments} steps, each midpoint pushed sideways by up
     * to {@code jag} blocks (most in the middle, none at the ends). The same seed gives the same bolt; change it every
     * few ticks for a crackle. Works in world or camera-relative space alike.
     */
    public static Vec3[] bolt(Vec3 from, Vec3 to, int segments, double jag, long seed) {
        Random r = new Random(seed);
        Vec3 d = to.subtract(from);
        Vec3 u = d.cross(Math.abs(d.normalize().y) < 0.95 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0));
        u = u.lengthSqr() < 1.0E-10 ? new Vec3(1, 0, 0) : u.normalize();
        Vec3 w = d.cross(u);
        w = w.lengthSqr() < 1.0E-10 ? new Vec3(0, 0, 1) : w.normalize();
        Vec3[] p = new Vec3[segments + 1];
        for (int i = 0; i <= segments; i++) {
            double f = (double) i / segments;
            double env = Math.sin(f * Math.PI);
            p[i] = from.add(d.scale(f)).add(u.scale((r.nextDouble() - 0.5) * 2 * jag * env)).add(w.scale((r.nextDouble() - 0.5) * 2 * jag * env));
        }
        return p;
    }

    /** {@link #bolt} with forks: the main path first, then up to {@code forks} short branches off it. */
    public static List<Vec3[]> forkedBolt(Vec3 from, Vec3 to, int segments, double jag, int forks, long seed) {
        List<Vec3[]> out = new ArrayList<>();
        Vec3[] main = bolt(from, to, segments, jag, seed);
        out.add(main);
        Random r = new Random(seed * 31 + 7);
        double len = to.subtract(from).length();
        for (int k = 0; k < forks; k++) {
            int at = 1 + r.nextInt(Math.max(1, segments - 2));
            Vec3 dir = new Vec3(r.nextDouble() - 0.5, r.nextDouble() - 0.5, r.nextDouble() - 0.5).normalize()
                .add(to.subtract(from).normalize()).normalize();
            out.add(bolt(main[at], main[at].add(dir.scale(len * (0.15 + 0.2 * r.nextDouble()))), Math.max(2, segments / 3), jag * 0.6,
                seed + k + 1));
        }
        return out;
    }

    private static void vertex(PoseStack.Pose at, VertexConsumer buffer, Vec3 p, int color, float u, float v, Vec3 n) {
        buffer.addVertex(at, (float) p.x, (float) p.y, (float) p.z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(LightTexture.FULL_BRIGHT).setNormal(at, (float) n.x, (float) n.y, (float) n.z);
    }
}
