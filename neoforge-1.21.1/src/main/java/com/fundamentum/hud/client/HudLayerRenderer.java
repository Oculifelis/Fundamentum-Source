package com.fundamentum.hud.client;

import com.fundamentum.hud.HudLayers;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws the {@link HudLayers} layers. Fundamentum adds one GUI layer above the hotbar ({@code fundamentum:hud_layers});
 * inside it every visible layer's drawer runs in {@link HudLayers#visible()} order, so the pack's order and switches
 * apply. Hidden with F1. Reads {@code config/fundamentum-hud.json} once, when the GUI layers are registered.
 *
 * <pre>{@code HudLayerRenderer.register(ResourceLocation.fromNamespaceAndPath("mymod", "mana_bar"), 100, (g, delta) -> drawBar(g));}</pre>
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class HudLayerRenderer {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    private static final Map<ResourceLocation, Drawer> DRAWERS = new ConcurrentHashMap<>();

    /** Draws one layer. */
    @FunctionalInterface
    public interface Drawer {
        void draw(GuiGraphics graphics, DeltaTracker delta);
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

    @SubscribeEvent
    public static void layers(RegisterGuiLayersEvent event) {
        HudLayers.loadConfig(HudLayers.defaultConfig());
        event.registerAbove(VanillaGuiLayers.HOTBAR, ResourceLocation.fromNamespaceAndPath("fundamentum", "hud_layers"), HudLayerRenderer::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        if (DRAWERS.isEmpty() || Minecraft.getInstance().options.hideGui) return;
        for (ResourceLocation id : HudLayers.visible()) {
            Drawer d = DRAWERS.get(id);
            if (d == null) continue;
            try {
                d.draw(g, delta);
            } catch (RuntimeException e) {
                LOG.error("HUD layer {} failed and is now off", id, e);
                HudLayers.setEnabled(id, false);
            }
        }
    }
}
