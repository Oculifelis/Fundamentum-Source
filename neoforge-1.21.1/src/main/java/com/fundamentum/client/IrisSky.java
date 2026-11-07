package com.fundamentum.client;

import com.mojang.blaze3d.platform.GlStateManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shader pack's sky buffer, for a mod's own sky drawn in the sky stage (the fractal skies).
 *
 * <p><b>Why.</b> Under Iris, vanilla's sky is drawn by the pack's {@code gbuffers_skybasic} and
 * {@code gbuffers_skytextured} into the pack's colour buffer {@code colortex0}; the pack's deferred and composite passes
 * keep that colour where nothing nearer was drawn, put its clouds and fog over it and tone-map it, and its final pass
 * overwrites Minecraft's main target. A mod shader Iris has no program for draws into Minecraft's main target, so a sky
 * drawn there is lost under the pack's final picture; handing it to a pack program instead would replace its fragment
 * shader. So the sky keeps its own shader and draws into the pack's {@code colortex0} itself ({@link #bind} /
 * {@link #unbind} round the draw), as the pack's own sky does, and the pack composites it like its sky.
 *
 * <p>Everything is reflection on Iris 1.8 internals ({@code IrisRenderingPipeline.renderTargets}, the colour target the
 * gbuffers write after the prepare passes); {@link #bind} is false without Iris, without a pack, or if Iris's internals
 * have changed, and the caller then draws as without a pack.
 */
public final class IrisSky {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");

    private static boolean ready;
    private static Method getManager;
    private static Method getPipeline;
    private static Class<?> irisPipeline;
    private static Field renderTargets;
    private static Method flippedAfterPrepare;
    private static Method get;
    private static Method mainTexture;
    private static Method altTexture;

    static {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            getManager = iris.getMethod("getPipelineManager");
            getPipeline = Class.forName("net.irisshaders.iris.pipeline.PipelineManager").getMethod("getPipelineNullable");
            irisPipeline = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            renderTargets = irisPipeline.getDeclaredField("renderTargets");
            renderTargets.setAccessible(true);
            flippedAfterPrepare = irisPipeline.getMethod("getFlippedAfterPrepare");
            get = Class.forName("net.irisshaders.iris.targets.RenderTargets").getMethod("get", int.class);
            Class<?> target = Class.forName("net.irisshaders.iris.targets.RenderTarget");
            mainTexture = target.getMethod("getMainTexture");
            altTexture = target.getMethod("getAltTexture");
            ready = true;
        } catch (Throwable t) {
            ready = false;        // no Iris, or internals this doesn't know: draw as without a pack
        }
    }

    private IrisSky() { }

    private static int fbo = -1;
    private static int fboTexture = -1;
    private static int previous;
    private static boolean warned;

    /**
     * Binds a framebuffer drawing into the pack's {@code colortex0} as the gbuffers (the sky included) write it this
     * frame, and returns true; false (nothing bound) when no pack is on or it can't be found. Call {@link #unbind}
     * after the draw.
     */
    public static boolean bind() {
        if (!ready || !Emissive.shadersOn()) {
            return false;
        }
        try {
            Object pipeline = getPipeline.invoke(getManager.invoke(null));
            if (!irisPipeline.isInstance(pipeline)) {
                return false;
            }
            Object target = get.invoke(renderTargets.get(pipeline), 0);
            if (target == null) {
                return false;
            }
            boolean flipped = ((Set<?>) flippedAfterPrepare.invoke(pipeline)).contains(0);
            int id = (Integer) (flipped ? altTexture : mainTexture).invoke(target);
            previous = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            if (fbo == -1) {
                fbo = GlStateManager.glGenFramebuffers();
            }
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            if (id != fboTexture) {   // Iris made new textures (a resize or a pack reload)
                GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, id, 0);
                fboTexture = id;
            }
            if (GlStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
                throw new IllegalStateException("the framebuffer on colortex0 is not complete");
            }
            return true;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LOG.warn("Fundamentum: can't find the shader pack's sky buffer, the fractal sky draws as without a pack", t);
            }
            ready = false;
            return false;
        }
    }

    /** Puts back the framebuffer that was bound before {@link #bind}. */
    public static void unbind() {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previous);
    }
}
