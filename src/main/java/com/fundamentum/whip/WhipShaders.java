package com.fundamentum.whip;

import com.fundamentum.client.Emissive;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/**
 * The cord shaders behind {@link WhipCordFx} (1.22.0): one fragment shader, {@code fundamentum:core/whip_cord}, with a
 * define per mode, on core/entity's vertex shader. Energy and lightning add light (no depth write), water and wind blend
 * translucent, glow is opaque and full bright.
 *
 * <p>Shader packs (1.25.3, 1.25.4): every pipeline here is handed to Iris ({@link Emissive#glow}, {@link Emissive#assign}):
 * light and glow through the pack's emissive entity program, water and wind through its translucent entities; the pack's program replaces the
 * cord shader, so {@link WhipsClient} animates the cord's vertex colours instead while a pack is on. Every render type
 * binds the overlay and lightmap textures, which a pack's entity programs sample.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class WhipShaders {
    private static final Map<WhipCordFx, RenderPipeline> PIPELINES = new EnumMap<>(WhipCordFx.class);
    private static final Map<WhipCordFx, Map<Identifier, RenderType>> TYPES = new EnumMap<>(WhipCordFx.class);

    static {
        for (WhipCordFx fx : WhipCordFx.values()) {
            if (fx == WhipCordFx.NONE) {
                continue;
            }
            boolean add = fx == WhipCordFx.ENERGY || fx == WhipCordFx.LIGHTNING || fx == WhipCordFx.FIRE;
            boolean opaque = fx == WhipCordFx.GLOW;
            RenderPipeline.Builder b = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("fundamentum", "pipeline/whip_cord_" + fx.name().toLowerCase(java.util.Locale.ROOT)))
                .withVertexShader("core/entity")
                .withFragmentShader(Identifier.fromNamespaceAndPath("fundamentum", "core/whip_cord"))
                .withShaderDefine("EMISSIVE")
                .withShaderDefine("NO_OVERLAY")
                .withShaderDefine("NO_CARDINAL_LIGHTING")
                .withShaderDefine("FX_" + fx.name())
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withCull(false)
                .withVertexBinding(0, DefaultVertexFormat.ENTITY).withPrimitiveTopology(PrimitiveTopology.QUADS);
            // 26.3: a pipeline has no colour target unless it names one
            b.withColorTargetState(ColorTargetState.DEFAULT);
            if (!opaque) {
                b.withColorTargetState(new ColorTargetState(add ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false));
            }
            PIPELINES.put(fx, b.build());
            TYPES.put(fx, new ConcurrentHashMap<>());
        }
    }

    /** Unlit vanilla entity shading: no lightmap, no directional shade (1.22.2). */
    private static RenderPipeline.Builder unlit(String name) {
        return RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("fundamentum", "pipeline/" + name))
            .withVertexShader("core/entity")
            .withFragmentShader("core/entity")
            .withShaderDefine("EMISSIVE")
            .withShaderDefine("NO_OVERLAY")
            .withShaderDefine("NO_CARDINAL_LIGHTING")
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexBinding(0, DefaultVertexFormat.ENTITY).withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withColorTargetState(ColorTargetState.DEFAULT);
    }

    /** Additive, no depth write, both faces: the halo round glowing cords and parts, and {@link #additive} lines. */
    private static final RenderPipeline HALO = unlit("whip_halo").withCull(false)
        .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
        .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false)).build();
    /** Opaque cutout, unlit: an item model face with light_emission 15, a style's glowing 3D part. */
    private static final RenderPipeline UNLIT_CUTOUT = unlit("unlit_cutout").withShaderDefine("ALPHA_CUTOUT", 0.1F).build();
    private static final RenderPipeline UNLIT_TRANSLUCENT = unlit("unlit_translucent").withShaderDefine("ALPHA_CUTOUT", 0.01F)
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
    private static final Identifier HALO_TEXTURE = Identifier.fromNamespaceAndPath("fundamentum", "textures/misc/glow.png");
    private static final Map<Identifier, RenderType> UNLIT = new ConcurrentHashMap<>(), UNLIT_SORTED = new ConcurrentHashMap<>(),
        ADDITIVE = new ConcurrentHashMap<>();
    private static final RenderType HALO_TYPE = RenderType.create("fundamentum_whip_halo",
        textured(HALO, HALO_TEXTURE).sortOnUpload().createRenderSetup());

    /** A setup on {@code pipeline} with {@code texture}, binding the overlay and lightmap too (shader packs sample them). */
    private static RenderSetup.RenderSetupBuilder textured(RenderPipeline pipeline, Identifier texture) {
        return RenderSetup.builder(pipeline).withTexture("Sampler0", texture).useOverlay().useLightmap();
    }

    @SubscribeEvent
    public static void pipelines(RegisterRenderPipelinesEvent event) {
        PIPELINES.values().forEach(event::registerPipeline);
        event.registerPipeline(HALO);
        event.registerPipeline(UNLIT_CUTOUT);
        event.registerPipeline(UNLIT_TRANSLUCENT);
        // Shader packs (1.25.4). Glow and light (energy, fire, lightning, the glow cord, the halo, glowing item faces and
        // parts) through the pack's emissive program, the one vanilla's glowing eyes and entityTranslucentEmissive use
        // (Iris's spider eyes): packs draw it unlit and bright, and Iris adds its light (SRC_ALPHA, ONE). The pack's lit
        // translucent entity program shaded our light like a surface at night, so it came out dim and flat. Water and
        // wind are not light: they stay translucent entities, blended. Pure light casts no shadow; glowing item faces
        // and parts cast one like the rest of the item. (Iris's beacon and lightning programs take other vertex formats,
        // so these entity-format pipelines cannot use them.)
        PIPELINES.forEach((fx, pipeline) -> {
            switch (fx) {
                case WATER, WIND -> Emissive.assign(pipeline, "ENTITIES_TRANSLUCENT", null);
                case GLOW -> Emissive.glow(pipeline, true);
                default -> Emissive.glow(pipeline, false);
            }
        });
        Emissive.glow(HALO, false);
        Emissive.glow(UNLIT_CUTOUT, true);
        Emissive.glow(UNLIT_TRANSLUCENT, true);
    }

    /** The render type a whip's cord is drawn with: its texture through its {@link WhipDefinition#cordFx}. */
    public static RenderType cord(WhipDefinition whip) {
        return type(whip.cordFx, whip.texture);
    }

    public static RenderType type(WhipCordFx fx, Identifier texture) {
        if (fx == WhipCordFx.NONE) {
            return RenderTypes.entityCutout(texture);
        }
        RenderPipeline pipeline = PIPELINES.get(fx);
        return TYPES.get(fx).computeIfAbsent(texture, t -> RenderType.create("fundamentum_whip_cord",
            textured(pipeline, t).sortOnUpload().createRenderSetup()));
    }

    /**
     * Unlit, full colour whatever the light (1.22.2): for a style's glowing geometry, which a full-bright light value
     * alone does not give (it still goes through the lightmap, so it reads as torch-lit and dims at night). Vertex
     * colours apply; texels under 0.1 alpha are cut. {@code translucent} blends instead of cutting. Glows under shader
     * packs too (1.25.3, {@link Emissive}).
     */
    public static RenderType unlit(Identifier texture, boolean translucent) {
        return translucent
            ? UNLIT_SORTED.computeIfAbsent(texture, t -> RenderType.create("fundamentum_unlit_translucent",
                textured(UNLIT_TRANSLUCENT, t).sortOnUpload().createRenderSetup()))
            : UNLIT.computeIfAbsent(texture, t -> RenderType.create("fundamentum_unlit_cutout",
                textured(UNLIT_CUTOUT, t).createRenderSetup()));
    }

    /** Additive light with any texture (1.25.3): no depth write, both faces, unlit. See {@link Emissive#additive}. */
    public static RenderType additive(Identifier texture) {
        return ADDITIVE.computeIfAbsent(texture, t -> RenderType.create("fundamentum_additive",
            textured(HALO, t).sortOnUpload().createRenderSetup()));
    }

    /**
     * The soft additive halo (1.22.2), textured with a round falloff ({@code fundamentum:textures/misc/glow.png}):
     * draw strips with u across 0..1 at v 0.5, or quads over the whole texture for a round glow, in the halo's colour
     * (alpha is its strength). See {@link WhipsClient#halo} and {@link WhipsClient#glowDot}.
     */
    public static RenderType halo() {
        return HALO_TYPE;
    }

    private WhipShaders() {}
}
