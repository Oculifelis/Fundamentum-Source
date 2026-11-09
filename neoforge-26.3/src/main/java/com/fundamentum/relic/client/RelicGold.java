package com.fundamentum.relic.client;

import com.fundamentum.Fundamentum;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Draws a boss's own renderer in gold for its relic. Every textured model is drawn with a gold copy of its texture (its
 * brightness put on the relic's tint ramp, {@link com.fundamentum.relic.RelicDef#tint}), and over it, fullbright, the
 * boss's own colour shining through wherever its skin is most colourful (the "through" sheet: the most saturated fifth of
 * the texels, at full strength, fading out below). Its glow layers (eyes, emissive) pass as they are, in their own
 * colours. Shadows, name tags, flames and leashes are dropped. The texture is read from the RenderType's own
 * description, so any boss's renderer works unchanged; a type it cannot read is drawn as it was.
 */
public class RelicGold implements SubmitNodeCollector {
    private static final Pattern TEXTURE = Pattern.compile("Sampler0=\\w+\\[location=([^,\\]]+)");
    private static final int FULL_BRIGHT = 0xF000F0;

    /** A gilded type: the gold one and the shining-through one (null: nothing shines through). */
    private record Gilt(RenderType gold, @Nullable RenderType through) {}

    private static final Gilt PASS = new Gilt(null, null);
    /** One ramp's gilded types and sheets. */
    private static final class Ramp {
        final int[] colours;
        final float floor;
        final String key;
        final Map<RenderType, Gilt> gilt = new HashMap<>();
        final Map<Identifier, Identifier[]> sheets = new HashMap<>();

        Ramp(int[] colours, float floor, String key) {
            this.colours = colours;
            this.floor = floor;
            this.key = key;
        }
    }

    private static final Map<String, Ramp> RAMPS = new HashMap<>();

    private final OrderedSubmitNodeCollector out;
    private final SubmitNodeCollector root;
    private final int order;
    private final Ramp ramp;

    /** Gilds onto {@code out} with {@code ramp} (dark to light, two colours or more) from {@code floor} (0 to the ramp's last step). */
    public RelicGold(SubmitNodeCollector out, int[] ramp, float floor) {
        this(out, out, 0, ramp(ramp, floor));
    }

    private RelicGold(OrderedSubmitNodeCollector out, SubmitNodeCollector root, int order, Ramp ramp) {
        this.out = out;
        this.root = root;
        this.order = order;
        this.ramp = ramp;
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
        Identifier tex = Identifier.tryParse(m.group(1).trim());
        if (tex == null) {
            return PASS;
        }
        Identifier[] sheets = ramp.sheets.computeIfAbsent(tex, t -> sheets(ramp, t));
        if (sheets == null) {
            return PASS;
        }
        RenderType gold = name.contains("translucent") ? RenderTypes.entityTranslucent(sheets[0]) : RenderTypes.entityCutout(sheets[0]);
        return new Gilt(gold, sheets[1] == null ? null : RenderTypes.entityTranslucentEmissive(sheets[1]));
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

    /**
     * The gold copy of {@code tex} and its shining-through sheet, registered as runtime textures (null: unreadable). The
     * through sheet keeps the texels whose colour is in the top fifth of the sheet (and at least a third saturated), their
     * hue at full brightness, fading in over the fifth below.
     */
    private static Identifier @Nullable [] sheets(Ramp ramp, Identifier tex) {
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
                    int p = img.getPixel(x, y);
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
                    int p = img.getPixel(x, y);
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
                        through.setPixel(x, y, (a << 24) | (Math.round(r / max * 255F) << 16) | (Math.round(g / max * 255F) << 8) | Math.round(b / max * 255F));
                        any = true;
                    }
                    img.setPixel(x, y, gold(ramp.colours, ramp.floor, p, lo, hi));
                }
            }
            String path = tex.getNamespace() + "/" + tex.getPath();
            Identifier goldId = Identifier.fromNamespaceAndPath(Fundamentum.MODID, "relic_gold/" + ramp.key + "/" + path);
            mc.getTextureManager().register(goldId, new DynamicTexture(goldId::toString, img));
            Identifier throughId = null;
            if (any) {
                throughId = Identifier.fromNamespaceAndPath(Fundamentum.MODID, "relic_through/" + ramp.key + "/" + path);
                Identifier id = throughId;
                mc.getTextureManager().register(throughId, new DynamicTexture(id::toString, through));
            } else {
                through.close();
            }
            return new Identifier[] {goldId, throughId};
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(Fundamentum.MODID).warn("relic could not gild {}", tex, e);
            return null;
        }
    }

    private OrderedSubmitNodeCollector above() {
        return this.root.order(this.order + 1);
    }

    // ---- SubmitNodeCollector
    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return new RelicGold(this.root.order(this.order + order), this.root, this.order + order, this.ramp);
    }

    // ---- what is gilded (model parts arrive here too: the interface wraps them as Model.Simple)
    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack pose, RenderType type, int light, int overlay, int tint,
                                @Nullable UvMapping uv, int outline) {
        Gilt g = gilt(type);
        if (g.gold() == null) {
            this.out.submitModel(model, state, pose, type, light, overlay, tint, uv, outline);
            return;
        }
        this.out.submitModel(model, state, pose, g.gold(), light, overlay, tint, uv, outline);
        if (g.through() != null) {
            this.above().submitModel(model, state, pose, g.through(), FULL_BRIGHT, overlay, tint, uv, 0);
        }
    }

    @Override
    public void submitCustomGeometry(PoseStack pose, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {
        Gilt g = gilt(type);
        this.out.submitCustomGeometry(pose, g.gold() == null ? type : g.gold(), renderer);
    }

    // ---- what is dropped: a relic casts no shadow, wears no name and does not burn
    @Override
    public void submitShadow(PoseStack pose, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public void submitNameTag(PoseStack pose, @Nullable Vec3 at, int offset, Component name, boolean seeThrough, int light,
                              CameraRenderState camera) {
    }

    @Override
    public void submitFlame(PoseStack pose, EntityRenderState state, Quaternionf rotation) {
    }

    @Override
    public void submitLeash(PoseStack pose, EntityRenderState.LeashState leash) {
    }

    // ---- what passes as it is
    @Override
    public void submitText(PoseStack pose, float x, float y, FormattedCharSequence string, boolean dropShadow, Font.DisplayMode mode, int light,
                           int color, int background, int outline) {
        this.out.submitText(pose, x, y, string, dropShadow, mode, light, color, background, outline);
    }

    @Override
    public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack pose, RenderType type, int light, int overlay, int tint,
                                           ModelFeatureRenderer.CrumblingOverlay crumbling) {
        this.out.submitCrumblingOverlay(model, state, pose, type, light, overlay, tint, crumbling);
    }

    @Override
    public void submitTextBackground(PoseStack pose, float x0, float y0, float x1, float y1, int color, Font.DisplayMode mode, int light) {
        this.out.submitTextBackground(pose, x0, y0, x1, y1, color, mode, light);
    }

    @Override
    public void submitMovingBlock(PoseStack pose, MovingBlockRenderState state, int outline) {
        this.out.submitMovingBlock(pose, state, outline);
    }

    @Override
    public void submitBlockModel(PoseStack pose, RenderType type, List<BlockStateModelPart> parts, int[] tints, int light, int overlay, int outline) {
        this.out.submitBlockModel(pose, type, parts, tints, light, overlay, outline);
    }

    @Override
    public void submitBreakingBlockModel(PoseStack pose, List<BlockStateModelPart> parts, int progress, boolean translucent) {
        this.out.submitBreakingBlockModel(pose, parts, progress, translucent);
    }

    @Override
    public void submitShapeOutline(PoseStack pose, VoxelShape shape, RenderType type, int color, float width, boolean afterTerrain) {
        this.out.submitShapeOutline(pose, shape, type, color, width, afterTerrain);
    }

    @Override
    public void submitItem(PoseStack pose, ItemDisplayContext context, int light, int overlay, int outline, int[] tints, ItemQuads quads,
                           ItemStackRenderState.FoilType foil) {
        this.out.submitItem(pose, context, light, overlay, outline, tints, quads, foil);
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
        this.out.submitQuadParticleGroup(particles);
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
        this.out.submitGizmoPrimitives(group, camera, onTop);
    }
}
