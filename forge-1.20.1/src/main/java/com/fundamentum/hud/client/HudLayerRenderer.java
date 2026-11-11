package com.fundamentum.hud.client;

import com.fundamentum.hud.HudLayers;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws the {@link HudLayers} layers. Fundamentum adds one GUI overlay above the hotbar ({@code fundamentum:hud_layers});
 * inside it every visible layer's drawer runs in {@link HudLayers#visible()} order, so the pack's order and switches
 * apply. Hidden with F1. Reads {@code config/fundamentum-hud.json} once, when the overlays are registered.
 *
 * <pre>{@code HudLayerRenderer.register(new ResourceLocation("mymod", "mana_bar"), 100, (g, partial) -> drawBar(g));}</pre>
 */
public final class HudLayerRenderer {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    private static final Map<ResourceLocation, Drawer> DRAWERS = new ConcurrentHashMap<>();

    /** Draws one layer (1.20.1: the frame's partial tick). */
    @FunctionalInterface
    public interface Drawer {
        void draw(GuiGraphics graphics, float partialTick);
    }

    private HudLayerRenderer() {
    }

    /** Declares the layer (on by default) and its drawer. */
    public static void register(ResourceLocation id, int order, Drawer drawer) {
        register(id, order, true, drawer);
    }

    public static void register(ResourceLocation id, int order, boolean defaultOn, Drawer drawer) {
        HudLayers.register(id, order, defaultOn);
        DRAWERS.put(id, drawer);
    }

    /** The overlay registration, on the mod bus. */
    @Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Layers {
        private Layers() {
        }

        @SubscribeEvent
        public static void layers(RegisterGuiOverlaysEvent event) {
            HudLayers.loadConfig(HudLayers.defaultConfig());
            event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "hud_layers", (ForgeGui gui, GuiGraphics g, float partial, int width, int height) -> render(g, partial));
        }
    }

    private static void render(GuiGraphics g, float partial) {
        if (DRAWERS.isEmpty() || Minecraft.getInstance().options.hideGui) return;
        for (ResourceLocation id : HudLayers.visible()) {
            Drawer d = DRAWERS.get(id);
            if (d == null) continue;
            try {
                d.draw(g, partial);
            } catch (RuntimeException e) {
                LOG.error("HUD layer {} failed and is now off", id, e);
                HudLayers.setEnabled(id, false);
            }
        }
    }
}
