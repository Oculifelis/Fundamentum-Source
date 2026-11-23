package com.fundamentum.client;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shader pack's sky buffer, for a mod's own sky drawn in the sky pass (the fractal skies).
 *
 * <p><b>Why.</b> Under Iris, vanilla's sky geometry is drawn by the pack's {@code gbuffers_skybasic} and
 * {@code gbuffers_skytextured} into the pack's colour buffer {@code colortex0}; the pack's deferred and composite passes
 * then keep that colour where nothing nearer was drawn, put its clouds, aurora and fog over it and tone-map it, and its
 * final pass overwrites Minecraft's main target. A mod pipeline Iris has no program for keeps its own shader but draws
 * into Minecraft's main target, so a sky drawn in the sky pass is lost under the pack's final picture. Handing the
 * pipeline to a pack program instead would replace its fragment shader (the pack's sky programs work their colour out
 * from the view direction or a texture, and fade custom skies out toward the horizon). So the sky keeps its own shader
 * and draws in the sky pass into the pack's {@code colortex0} itself, as the pack's own sky does: the pack composites
 * it like its sky, with terrain, clouds and fog over it.
 *
 * <p>Everything is reflection on Iris 1.11 internals ({@code IrisRenderingPipeline.renderTargets}, the colour target
 * the gbuffers write after the prepare passes); {@link #colorTarget} is null without Iris, without a pack, or if
 * Iris's internals have changed, and the caller then draws as without a pack.
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
    private static Method width;
    private static Method height;

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
            width = target.getMethod("getWidth");
            height = target.getMethod("getHeight");
            ready = true;
        } catch (Throwable t) {
            ready = false;        // no Iris, or internals this doesn't know: draw as without a pack
        }
    }

    private IrisSky() { }

    private static int viewId = -1;
    private static int viewW;
    private static int viewH;
    private static @Nullable GpuTextureView view;
    private static boolean warned;

    /**
     * The pack's {@code colortex0} as the gbuffers (the sky included) write it this frame, as a render target for a
     * {@code RenderPass}, or null when no pack is on or it can't be found. Never close it: Iris owns the texture.
     */
    public static @Nullable GpuTextureView colorTarget() {
        if (!ready || !Emissive.shadersOn()) {
            return null;
        }
        try {
            Object pipeline = getPipeline.invoke(getManager.invoke(null));
            if (!irisPipeline.isInstance(pipeline)) {
                return null;
            }
            Object target = get.invoke(renderTargets.get(pipeline), 0);
            if (target == null) {
                return null;
            }
            boolean flipped = ((Set<?>) flippedAfterPrepare.invoke(pipeline)).contains(0);
            int id = (Integer) (flipped ? altTexture : mainTexture).invoke(target);
            int w = (Integer) width.invoke(target);
            int h = (Integer) height.invoke(target);
            if (view == null || id != viewId || w != viewW || h != viewH) {
                if (view != null) {
                    view.close();     // our view's framebuffers only; the texture stays Iris's
                }
                view = new View(new Borrowed(id, w, h));
                viewId = id;
                viewW = w;
                viewH = h;
            }
            return view;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LOG.warn("Fundamentum: can't find the shader pack's sky buffer, the fractal sky draws as without a pack", t);
            }
            ready = false;
            return null;
        }
    }

    /** Iris's texture, borrowed by id: never closed from here, so the texture itself is never deleted. */
    private static final class Borrowed extends GlTexture {
        Borrowed(int id, int w, int h) {
            super(GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, "Iris colortex0 (borrowed)", TextureFormat.RGBA8,
                w, h, 1, 1, id);
        }
    }

    private static final class View extends GlTextureView {
        View(GlTexture texture) {
            super(texture, 0, 1);
        }
    }
}
