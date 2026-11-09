package com.fundamentum.sky;

import com.fundamentum.client.IrisSky;
import com.fundamentum.moon.MoonsClient;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.util.Comparator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws the fractal sky ({@link FractalSky}) the moon, night event or dimension calls for: a cube round the camera,
 * right after the vanilla sky, through {@code fundamentum:core/fractal_sky}, which works the fractal out per pixel from
 * the view direction. It fades in and out over the sky's {@code fade_seconds}, and blends into the vanilla sky toward
 * the horizon. A sky with {@code melt} also draws the melting vision over the world ({@code fundamentum:post/melt}),
 * scaled by {@link SkyConfig}. {@code /fundamentum_sky preview <id> | off} (client command) shows a sky by id.
 *
 * <p>Under a shader pack it is drawn at the same point into the pack's own sky buffer ({@link IrisSky}), so the pack
 * composites it as its sky.
 *
 * <p>1.21.1: the two passes are core shaders ({@code fundamentum:fractal_sky}, {@code fundamentum:melt}) loaded in
 * {@link RegisterShadersEvent} and drawn with the Tesselator; the melt copies the main target with a framebuffer blit.
 */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class FractalSkiesClient {
    private FractalSkiesClient() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static ShaderInstance skyShader;
    private static ShaderInstance meltShader;
    private static TextureTarget copy;
    private static FractalSky shown;
    private static FractalSky preview;
    private static float alpha;
    private static float alphaO;

    /** The mod bus half (1.21.1's subscribers do not pick their bus by the event). */
    @Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus {
        @SubscribeEvent
        public static void shaders(RegisterShadersEvent event) {
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(), new ResourceLocation("fundamentum", "fractal_sky"),
                    DefaultVertexFormat.POSITION), shader -> skyShader = shader);
                event.registerShader(new ShaderInstance(event.getResourceProvider(), new ResourceLocation("fundamentum", "melt"),
                    DefaultVertexFormat.POSITION), shader -> meltShader = shader);
            } catch (IOException e) {
                LOG.error("Fractal sky shaders failed to load; no fractal skies or melt will be drawn", e);
            }
        }
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
        String dim = mc.level.dimension().location().toString();
        return FractalSkies.all().stream().filter(s -> s.matches(MoonsClient.moon(), MoonsClient.event(), dim))
            .max(Comparator.comparingInt(FractalSky::priority)).orElse(null);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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
    public static void stages(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            afterSky(event);
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            afterLevel();
        }
    }

    private static void afterSky(RenderLevelStageEvent event) {
        FractalSky sky = shown;
        float a = alpha(Minecraft.getInstance().getFrameTime());
        if (sky == null || a <= 0.0F || !SkyConfig.FRACTAL_SKIES.get() || skyShader == null) {
            return;
        }
        draw(sky, a, event.getPoseStack().last().pose());
    }

    private static void draw(FractalSky s, float a, Matrix4fc modelView) {
        Matrix4f params = new Matrix4f(
            s.fractal(), s.iterations(), seconds(), a,
            s.zoomSpeed(), s.zoomDepth(), s.paletteSpeed(), s.morphSpeed(),
            s.centerX(), s.centerY(), s.brightness(), s.rotationSpeed(),
            s.palette(), s.horizon(), s.mapping(), s.scale());
        skyShader.safeGetUniform("Params").set(params);
        skyShader.safeGetUniform("Tint").set((s.tint() >> 16 & 255) / 255.0F, (s.tint() >> 8 & 255) / 255.0F, (s.tint() & 255) / 255.0F, 1.0F);
        skyShader.safeGetUniform("Tour").set(s.tour() ? 1.0F : 0.0F, 0.0F, 0.0F);
        // under a shader pack, into the pack's sky buffer, which it composites as its own sky (else its final pass
        // would draw over it); its tone mapping keeps the colours close to the shaders-off sky, so they go in as
        // they are
        boolean pack = IrisSky.bind();
        // the sky stage runs before the level's view matrix is on the stack: put the camera's turn there for this draw
        PoseStack stack = RenderSystem.getModelViewStack();
        stack.pushPose();
        stack.setIdentity();
        stack.mulPoseMatrix(new Matrix4f(modelView));
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(() -> skyShader);
        BufferUploader.drawWithShader(cube());
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        stack.popPose();
        RenderSystem.applyModelViewMatrix();
        if (pack) {
            IrisSky.unbind();
        }
    }

    private static void afterLevel() {
        FractalSky sky = shown;
        if (sky == null || sky.melt() <= 0.0F || !SkyConfig.MELT.get() || meltShader == null) {
            return;
        }
        float k = alpha(Minecraft.getInstance().getFrameTime()) * sky.melt()
            * SkyConfig.MELT_INTENSITY.get().floatValue();
        if (k > 0.001F) {
            melt(k);
        }
    }

    private static void melt(float strength) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (copy == null || copy.width != main.width || copy.height != main.height) {
            if (copy != null) {
                copy.destroyBuffers();
            }
            copy = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
            copy.setFilterMode(GL30.GL_LINEAR);
        }
        // copy what the world drew, then draw it back warped over the whole screen
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copy.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, main.width, main.height, GL30.GL_COLOR_BUFFER_BIT, GL30.GL_NEAREST);
        main.bindWrite(false);
        meltShader.safeGetUniform("Melt").set(strength, seconds(), 0.0F, 0.0F);
        RenderSystem.setShaderTexture(0, copy.getColorTextureId());
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(() -> meltShader);
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.vertex(-1.0F, -1.0F, 0.0F).endVertex();
        b.vertex(1.0F, -1.0F, 0.0F).endVertex();
        b.vertex(1.0F, 1.0F, 0.0F).endVertex();
        b.vertex(-1.0F, 1.0F, 0.0F).endVertex();
        BufferUploader.drawWithShader(b.end());
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    /** Six faces 100 blocks out, two triangles each. */
    private static BufferBuilder.RenderedBuffer cube() {
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
        float r = 100.0F;
        float[][] corners = new float[8][];
        for (int i = 0; i < 8; i++) {
            corners[i] = new float[] {(i & 1) == 0 ? -r : r, (i & 2) == 0 ? -r : r, (i & 4) == 0 ? -r : r};
        }
        int[][] faces = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
        for (int[] f : faces) {
            for (int i : new int[] {f[0], f[1], f[2], f[0], f[2], f[3]}) {
                b.vertex(corners[i][0], corners[i][1], corners[i][2]).endVertex();
            }
        }
        return b.end();
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
