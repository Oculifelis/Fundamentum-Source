package com.fundamentum.relic.client;

import com.fundamentum.Fundamentum;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.jetbrains.annotations.Nullable;

/**
 * Draws a boss's own renderer in gold for its relic. Every textured model is drawn with a gold copy of its texture (its
 * brightness put on the relic's tint ramp, {@link com.fundamentum.relic.RelicDef#tint}), and over it, fullbright, the
 * boss's own colour shining through wherever its skin is most colourful (the "through" sheet: the most saturated fifth of
 * the texels, at full strength, fading out below). Its glow layers (eyes, emissive) pass as they are, in their own
 * colours. The texture is read from the RenderType's own description, so any boss's renderer works unchanged; a type it
 * cannot read is drawn as it was.
 * <p>
 * On forge-1.20.1 a renderer writes straight into the buffers it is handed, and a level's buffer source ends one type's
 * batch when another is asked for, so the gold and the shining through cannot be written side by side. The figure is drawn
 * twice instead: once through {@link #gold} (body types swapped for their gold ones, everything else as it is), then once
 * through {@link #through} (only the through sheets, fullbright; everything else goes nowhere). Shadows and flames are not
 * drawn because the relic calls the boss's renderer directly, not through the dispatcher.
 */
public class RelicGold {
    private static final Pattern TEXTURE = Pattern.compile("texture\\[Optional\\[([^\\]]+)\\]");
    private static final int FULL_BRIGHT = 0xF000F0;

    /** A gilded type: the gold one and the shining-through one (null: nothing shines through). */
    private record Gilt(@Nullable RenderType gold, @Nullable RenderType through) {}

    private static final Gilt PASS = new Gilt(null, null);

    /** One ramp's gilded types and sheets. */
    private static final class Ramp {
        final int[] colours;
        final float floor;
        final String key;
        final Map<RenderType, Gilt> gilt = new HashMap<>();
        final Map<ResourceLocation, ResourceLocation[]> sheets = new HashMap<>();

        Ramp(int[] colours, float floor, String key) {
            this.colours = colours;
            this.floor = floor;
            this.key = key;
        }
    }

    private static final Map<String, Ramp> RAMPS = new HashMap<>();

    private final Ramp ramp;

    /** Gilds with {@code ramp} (dark to light, two colours or more) from {@code floor} (0 to the ramp's last step). */
    public RelicGold(int[] ramp, float floor) {
        this.ramp = ramp(ramp, floor);
    }

    private static Ramp ramp(int[] colours, float floor) {
        StringBuilder k = new StringBuilder();
        for (int c : colours) {
            k.append(String.format("%06x", c & 0xFFFFFF));
        }
        k.append('_').append(Math.round(floor * 1000));
        String key = k.toString();
        return RAMPS.computeIfAbsent(key, x -> new Ramp(colours.clone(), floor, Integer.toHexString(key.hashCode() & 0x7FFFFFFF)));
    }

    /** Forgets every gold sheet (a resource reload). */
    public static void clear() {
        RAMPS.clear();
    }

    /** The first pass: body types drawn with their gold sheets, everything else as it is. */
    public MultiBufferSource gold(MultiBufferSource out) {
        return type -> {
            Gilt g = this.gilt(type);
            return out.getBuffer(g.gold() == null ? type : g.gold());
        };
    }

    /** The second pass: the boss's colour shining through, fullbright, over the gold; nothing else is drawn. */
    public MultiBufferSource through(MultiBufferSource out) {
        return type -> {
            Gilt g = this.gilt(type);
            return g.through() == null ? Nowhere.INSTANCE : new FullBright(out.getBuffer(g.through()));
        };
    }

    private Gilt gilt(RenderType type) {
        return this.ramp.gilt.computeIfAbsent(type, t -> toGilt(this.ramp, t));
    }

    private static Gilt toGilt(Ramp ramp, RenderType type) {
        String s = type.toString();
        String name = s.startsWith("RenderType[") ? s.substring(11, Math.max(11, s.indexOf(':'))) : "";
        // only the plain textured body types are gilded; glows, beams, flames and anything of the mod's own pass as they are
        boolean body = (name.startsWith("entity_") || name.startsWith("armor_") || name.startsWith("item_"))
            && !name.contains("emissive") && !name.contains("shadow") && !name.contains("glint");
        if (!body) {
            return PASS;
        }
        Matcher m = TEXTURE.matcher(s);
        if (!m.find()) {
            return PASS;
        }
        ResourceLocation tex = ResourceLocation.tryParse(m.group(1).trim());
        if (tex == null) {
            return PASS;
        }
        ResourceLocation[] sheets = ramp.sheets.computeIfAbsent(tex, t -> sheets(ramp, t));
        if (sheets == null) {
            return PASS;
        }
        RenderType gold = name.contains("translucent") ? RenderType.entityTranslucent(sheets[0]) : RenderType.entityCutout(sheets[0]);
        return new Gilt(gold, sheets[1] == null ? null : RenderType.entityTranslucentEmissive(sheets[1]));
    }

    private static float luma(int c) {
        return (0.3F * ((c >> 16) & 0xFF) + 0.55F * ((c >> 8) & 0xFF) + 0.15F * (c & 0xFF)) / 255F;
    }

    /** Saturation times value (HSV), 0 to 1: how much colour a texel carries. */
    private static float colourful(int c) {
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max == 0 ? 0F : (max - min) / 255F;
    }

    /** {@code argb}'s brightness (between {@code lo} and {@code hi}) put on the ramp from its floor to its lightest colour. */
    static int gold(int[] ramp, float floor, int argb, float lo, float hi) {
        int last = ramp.length - 1;
        float t = (luma(argb) - lo) / Math.max(hi - lo, 1e-3F);
        t = floor + Math.max(0F, Math.min(1F, t)) * (last - floor);
        int i = Math.max(0, Math.min(last - 1, (int) Math.floor(t)));
        float f = Math.max(0F, Math.min(1F, t - i));
        int a = ramp[i];
        int b = ramp[i + 1];
        int r = Math.round(((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f);
        int bl = Math.round((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | bl;
    }

    /** NativeImage on 1.20.1 holds ABGR; this swaps red and blue, so it turns ABGR to ARGB and back. */
    private static int swapRB(int c) {
        return (c & 0xFF00FF00) | ((c & 0xFF) << 16) | ((c >> 16) & 0xFF);
    }

    private static int pixel(NativeImage img, int x, int y) {
        return swapRB(img.getPixelRGBA(x, y));
    }

    private static void pixel(NativeImage img, int x, int y, int argb) {
        img.setPixelRGBA(x, y, swapRB(argb));
    }

    /**
     * The gold copy of {@code tex} and its shining-through sheet, registered as runtime textures (null: unreadable). The
     * through sheet keeps the texels whose colour is in the top fifth of the sheet (and at least a third saturated), their
     * hue at full brightness, fading in over the fifth below.
     */
    private static ResourceLocation @Nullable [] sheets(Ramp ramp, ResourceLocation tex) {
        Minecraft mc = Minecraft.getInstance();
        Optional<Resource> res = mc.getResourceManager().getResource(tex);
        if (res.isEmpty()) {
            return null;
        }
        try (InputStream in = res.get().open()) {
            NativeImage img = NativeImage.read(in);
            int w = img.getWidth();
            int h = img.getHeight();
            float[] l = new float[w * h];
            float[] col = new float[w * h];
            int n = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = pixel(img, x, y);
                    if ((p >>> 24) > 16) {
                        l[n] = luma(p);
                        col[n++] = colourful(p);
                    }
                }
            }
            if (n == 0) {
                img.close();
                return null;
            }
            float[] sorted = Arrays.copyOf(l, n);
            Arrays.sort(sorted);
            float lo = sorted[(int) (0.03F * (n - 1))];
            float hi = sorted[(int) (0.97F * (n - 1))];
            float[] cs = Arrays.copyOf(col, n);
            Arrays.sort(cs);
            float top = Math.max(0.33F, cs[(int) (0.80F * (n - 1))]);
            float fade = Math.max(0.05F, top - cs[(int) (0.60F * (n - 1))]);
            NativeImage through = new NativeImage(w, h, true);
            boolean any = false;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = pixel(img, x, y);
                    int alpha = p >>> 24;
                    if (alpha == 0) {
                        continue;
                    }
                    float c = colourful(p);
                    float k = Math.max(0F, Math.min(1F, (c - (top - fade)) / fade));
                    if (k > 0F && c >= 0.25F) {
                        int r = (p >> 16) & 0xFF;
                        int g = (p >> 8) & 0xFF;
                        int b = p & 0xFF;
                        float max = Math.max(1, Math.max(r, Math.max(g, b)));
                        int a = Math.round(alpha * k * 0.6F);
                        pixel(through, x, y, (a << 24) | (Math.round(r / max * 255F) << 16) | (Math.round(g / max * 255F) << 8) | Math.round(b / max * 255F));
                        any = true;
                    }
                    pixel(img, x, y, gold(ramp.colours, ramp.floor, p, lo, hi));
                }
            }
            String path = tex.getNamespace() + "/" + tex.getPath();
            ResourceLocation goldId = new ResourceLocation(Fundamentum.MODID, "relic_gold/" + ramp.key + "/" + path);
            mc.getTextureManager().register(goldId, new DynamicTexture(img));
            ResourceLocation throughId = null;
            if (any) {
                throughId = new ResourceLocation(Fundamentum.MODID, "relic_through/" + ramp.key + "/" + path);
                mc.getTextureManager().register(throughId, new DynamicTexture(through));
            } else {
                through.close();
            }
            return new ResourceLocation[] {goldId, throughId};
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(Fundamentum.MODID).warn("relic could not gild {}", tex, e);
            return null;
        }
    }

    /** Passes every vertex on with its light set to full. */
    private record FullBright(VertexConsumer out) implements VertexConsumer {
        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            this.out.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            this.out.color(r, g, b, a);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            this.out.uv(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            this.out.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            this.out.uv2(FULL_BRIGHT & 0xFFFF, FULL_BRIGHT >> 16);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            this.out.normal(x, y, z);
            return this;
        }

        @Override
        public void endVertex() {
            this.out.endVertex();
        }

        @Override
        public void vertex(float x, float y, float z, float r, float g, float b, float a, float u, float v, int overlay, int light,
                           float nx, float ny, float nz) {
            this.out.vertex(x, y, z, r, g, b, a, u, v, overlay, FULL_BRIGHT, nx, ny, nz);
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            this.out.defaultColor(r, g, b, a);
        }

        @Override
        public void unsetDefaultColor() {
            this.out.unsetDefaultColor();
        }
    }

    /** Takes vertices and draws nothing. */
    private enum Nowhere implements VertexConsumer {
        INSTANCE;

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }
}
