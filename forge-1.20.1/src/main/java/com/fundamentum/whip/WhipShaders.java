package com.fundamentum.whip;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cord shaders behind {@link WhipCordFx} (1.22.0). On 1.21.1 each mode is its own core shader,
 * {@code fundamentum:whip_cord_<mode>} (a json, and a fragment shader that sets the mode's define and imports
 * {@code fundamentum:whip_cord.glsl}), all on {@code fundamentum:whip_cord.vsh}. Energy, lightning and fire add light
 * (no depth write), water and wind blend translucent, glow is opaque and full bright. If a shader fails to load (or a
 * shader pack replaces it) the cord draws through vanilla's emissive entity shader instead.
 */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class WhipShaders {
    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final Map<WhipCordFx, ShaderInstance> SHADERS = new EnumMap<>(WhipCordFx.class);
    private static final Map<WhipCordFx, Map<ResourceLocation, RenderType>> TYPES = new EnumMap<>(WhipCordFx.class);
    private static final ResourceLocation HALO_TEXTURE = new ResourceLocation("fundamentum", "textures/misc/glow.png");

    static {
        for (WhipCordFx fx : WhipCordFx.values()) {
            TYPES.put(fx, new ConcurrentHashMap<>());
        }
    }

    @SubscribeEvent
    public static void shaders(RegisterShadersEvent event) {
        for (WhipCordFx fx : WhipCordFx.values()) {
            if (fx == WhipCordFx.NONE) {
                continue;
            }
            ResourceLocation id = new ResourceLocation("fundamentum", "whip_cord_" + fx.name().toLowerCase(Locale.ROOT));
            try {
                event.registerShader(new ShaderInstance(event.getResourceProvider(), id, DefaultVertexFormat.NEW_ENTITY),
                    shader -> SHADERS.put(fx, shader));
            } catch (IOException e) {
                LOG.error("Whip cord shader {} failed to load; those cords draw plainly", id, e);
            }
        }
    }

    /** The render type a whip's cord is drawn with: its texture through its {@link WhipDefinition#cordFx}. */
    public static RenderType cord(WhipDefinition whip) {
        return type(whip.cordFx, whip.texture);
    }

    public static RenderType type(WhipCordFx fx, ResourceLocation texture) {
        if (fx == WhipCordFx.NONE) {
            return RenderType.entityCutoutNoCull(texture);
        }
        return TYPES.get(fx).computeIfAbsent(texture, t -> Types.cord(fx, t));
    }

    /**
     * Unlit, full colour whatever the light (1.22.2): for a style's glowing geometry. On 1.21.1 both kinds go through
     * vanilla's emissive entity shader (no lightmap, texels under 0.1 alpha cut); {@code translucent} keeps its blending.
     */
    public static RenderType unlit(ResourceLocation texture, boolean translucent) {
        return RenderType.entityTranslucentEmissive(texture, !translucent);
    }

    /**
     * The soft additive halo (1.22.2), textured with a round falloff ({@code fundamentum:textures/misc/glow.png}):
     * draw strips with u across 0..1 at v 0.5, or quads over the whole texture for a round glow, in the halo's colour
     * (alpha is its strength). See {@link WhipsClient#halo} and {@link WhipsClient#glowDot}. On 1.21.1 this is
     * vanilla's additive {@code eyes} type.
     */
    public static RenderType halo() {
        return RenderType.eyes(HALO_TEXTURE);
    }

    /** RenderType's building blocks are protected; a subclass reaches them. */
    private abstract static class Types extends RenderType {
        private Types() {
            super("", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 0, false, false, () -> {}, () -> {});
        }

        static RenderType cord(WhipCordFx fx, ResourceLocation texture) {
            boolean add = fx == WhipCordFx.ENERGY || fx == WhipCordFx.LIGHTNING || fx == WhipCordFx.FIRE;
            boolean opaque = fx == WhipCordFx.GLOW;
            CompositeState.CompositeStateBuilder state = CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(() -> shader(fx)))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setCullState(NO_CULL)
                .setOverlayState(OVERLAY);
            if (!opaque) {
                state.setTransparencyState(add ? LIGHTNING_TRANSPARENCY : TRANSLUCENT_TRANSPARENCY).setWriteMaskState(COLOR_WRITE);
            }
            return create("fundamentum_whip_cord_" + fx.name().toLowerCase(Locale.ROOT), DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 1536, true, !opaque, state.createCompositeState(opaque));
        }

        private static @Nullable ShaderInstance shader(WhipCordFx fx) {
            // A shader pack replaces vanilla's programs but would draw this one as it is, flat (1.25.3): through vanilla's
            // emissive entity shader instead, which the pack maps to its own emissive program; WhipsClient animates the
            // cord's colours meanwhile.
            ShaderInstance shader = com.fundamentum.client.Emissive.shadersOn() ? null : SHADERS.get(fx);
            return shader != null ? shader : net.minecraft.client.renderer.GameRenderer.getRendertypeEntityTranslucentEmissiveShader();
        }
    }

    private WhipShaders() {}
}
