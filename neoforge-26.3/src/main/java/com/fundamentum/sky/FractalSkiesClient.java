package com.fundamentum.sky;

import com.fundamentum.client.IrisSky;
import com.fundamentum.moon.MoonsClient;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Comparator;
import java.util.OptionalDouble;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Draws the fractal sky ({@link FractalSky}) the moon, night event or dimension calls for: a cube round the camera,
 * right after the vanilla sky, through {@code fundamentum:core/fractal_sky}, which works the fractal out per pixel from
 * the view direction. It fades in and out over the sky's {@code fade_seconds}, and blends into the vanilla sky toward
 * the horizon. A sky with {@code melt} also draws the melting vision over the world ({@code fundamentum:post/melt}),
 * scaled by {@link SkyConfig}. {@code /fundamentum_sky preview <id> | off} (client command) shows a sky by id.
 * Under a shader pack it is drawn at the same point into the pack's own sky buffer ({@link IrisSky}), so the pack
 * composites it as its sky.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class FractalSkiesClient {
    private FractalSkiesClient() { }

    private static final RenderPipeline SKY = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withLocation(Identifier.fromNamespaceAndPath("fundamentum", "pipeline/fractal_sky"))
        .withVertexShader(Identifier.fromNamespaceAndPath("fundamentum", "core/fractal_sky"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("fundamentum", "core/fractal_sky"))
        .withVertexBinding(0, DefaultVertexFormat.POSITION)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
        .withCull(false)
        .build();
    private static final RenderPipeline MELT = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("fundamentum", "pipeline/melt"))
        .withVertexShader("core/screenquad")
        .withFragmentShader(Identifier.fromNamespaceAndPath("fundamentum", "post/melt"))
        .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .build();

    private static GpuBuffer cube;
    private static TextureTarget copy;
    private static FractalSky shown;
    private static FractalSky preview;
    private static float alpha;
    private static float alphaO;

    @SubscribeEvent
    public static void pipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(SKY);
        event.registerPipeline(MELT);
    }

    /** The sky a preview shows instead of the one data calls for (null: back to data). For tests and the command. */
    public static void preview(FractalSky sky) {
        preview = sky;
    }

    /** The sky being drawn now, or null. */
    public static FractalSky shown() {
        return alpha > 0.0F ? shown : null;
    }

    /** How far the shown sky has faded in, 0..1. */
    public static float alpha() {
        return alpha;
    }

    private static FractalSky wanted() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        if (preview != null) {
            return preview;
        }
        String dim = mc.level.dimension().identifier().toString();
        return FractalSkies.all().stream().filter(s -> s.matches(MoonsClient.moon(), MoonsClient.event(), dim))
            .max(Comparator.comparingInt(FractalSky::priority)).orElse(null);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        FractalSky want = wanted();
        alphaO = alpha;
        if (want != null && shown == null) {
            shown = want;
        }
        float step = shown == null ? 1.0F : 1.0F / (20.0F * shown.fadeSeconds());
        if (want != null && want.equals(shown)) {
            alpha = Math.min(1.0F, alpha + step);
        } else {
            alpha = Math.max(0.0F, alpha - step);           // fade the old one out before the next comes in
            if (alpha <= 0.0F) {
                shown = want;
            }
        }
    }

    private static float alpha(float partialTick) {
        return alphaO + (alpha - alphaO) * partialTick;
    }

    /** For screenshots: the sky's clock in seconds, or -1 for the real one. */
    static float timeOverride = -1.0F;

    private static float seconds() {
        if (timeOverride >= 0.0F) {
            return timeOverride;
        }
        return (float) (Util.getMillis() % 100_000_000L / 1000.0);
    }

    @SubscribeEvent
    public static void afterSky(RenderLevelStageEvent.AfterSky event) {
        FractalSky sky = shown;
        float a = alpha(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (sky == null || a <= 0.0F || !SkyConfig.FRACTAL_SKIES.getAsBoolean()) {
            return;
        }
        draw(sky, a, event.getModelViewMatrix());
    }

    private static void draw(FractalSky s, float a, Matrix4fc modelView) {
        if (cube == null) {
            cube = buildCube();
        }
        Matrix4f params = new Matrix4f(
            s.fractal(), s.iterations(), seconds(), a,
            s.zoomSpeed(), s.zoomDepth(), s.paletteSpeed(), s.morphSpeed(),
            s.centerX(), s.centerY(), s.brightness(), s.rotationSpeed(),
            s.palette(), s.horizon(), s.mapping(), s.scale());
        Vector4f tint = new Vector4f((s.tint() >> 16 & 255) / 255.0F, (s.tint() >> 8 & 255) / 255.0F, (s.tint() & 255) / 255.0F, 1.0F);
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(modelView), tint, new Vector3f(s.tour() ? 1.0F : 0.0F, 0.0F, 0.0F), params);
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        // under a shader pack, into the pack's sky buffer, which it composites as its own sky (else its final pass
        // would draw over it; its tone mapping keeps the colours close to the shaders-off sky, so they go in as they
        // are); the pack's depth is Minecraft's, so the depth target stays main's
        GpuTextureView pack = IrisSky.colorTarget();
        GpuTextureView color = pack != null ? pack : main.getColorTextureView();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Fundamentum fractal sky",
                color, Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(SKY));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, cube.slice());
            pass.draw(36, 1, 0, 0);
        }
    }

    @SubscribeEvent
    public static void afterLevel(RenderLevelStageEvent.AfterLevel event) {
        FractalSky sky = shown;
        if (sky == null || sky.melt() <= 0.0F || !SkyConfig.MELT.getAsBoolean()) {
            return;
        }
        float k = alpha(Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false)) * sky.melt()
            * SkyConfig.MELT_INTENSITY.get().floatValue();
        if (k > 0.001F) {
            melt(k);
        }
    }

    private static void melt(float strength) {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (main.getColorTexture() == null) {
            return;
        }
        if (copy == null || copy.width != main.width || copy.height != main.height) {
            if (copy != null) {
                copy.destroyBuffers();
            }
            copy = new TextureTarget("Fundamentum melt", main.width, main.height, main.getColorTexture().getFormat(), null);
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(main.getColorTexture(), copy.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(),
            new Vector4f(strength, seconds(), 0.0F, 0.0F), new Vector3f(), new Matrix4f());
        try (RenderPass pass = encoder.createRenderPass(() -> "Fundamentum melt", main.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(MELT));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setUniform("InSampler", copy.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(3, 1, 0, 0);
        }
    }

    /** Six faces 100 blocks out, two triangles each. */
    private static GpuBuffer buildCube() {
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(36 * DefaultVertexFormat.POSITION.getVertexSize())) {
            BufferBuilder b = new BufferBuilder(bytes, PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION);
            float r = 100.0F;
            float[][] corners = new float[8][];
            for (int i = 0; i < 8; i++) {
                corners[i] = new float[] {(i & 1) == 0 ? -r : r, (i & 2) == 0 ? -r : r, (i & 4) == 0 ? -r : r};
            }
            int[][] faces = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
            for (int[] f : faces) {
                for (int i : new int[] {f[0], f[1], f[2], f[0], f[2], f[3]}) {
                    b.addVertex(corners[i][0], corners[i][1], corners[i][2]);
                }
            }
            try (MeshData mesh = b.buildOrThrow()) {
                return RenderSystem.getDevice().createBuffer(() -> "Fundamentum fractal sky cube", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    mesh.vertexBuffer());
            }
        }
    }

    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fundamentum_sky")
            .then(Commands.literal("off").executes(c -> {
                preview(null);
                c.getSource().sendSuccess(() -> Component.translatable("commands.fundamentum.sky.off"), false);
                return 1;
            }))
            .then(Commands.literal("preview").then(Commands.argument("id", StringArgumentType.greedyString()).executes(c -> {
                String id = StringArgumentType.getString(c, "id");
                FractalSky sky = FractalSkies.all().stream().filter(s -> s.id().toString().equals(id)).findFirst().orElse(null);
                if (sky == null) {
                    c.getSource().sendFailure(Component.translatable("commands.fundamentum.sky.unknown", id));
                    return 0;
                }
                preview(sky);
                c.getSource().sendSuccess(() -> Component.translatable("commands.fundamentum.sky.preview", id), false);
                return 1;
            }))));
    }
}
