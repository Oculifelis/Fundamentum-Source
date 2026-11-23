package com.fundamentum.hud.client;

import com.fundamentum.client.HudBox;
import com.fundamentum.hud.HudLayers;
import com.fundamentum.hud.HudLayout;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws the {@link HudLayers} layers where {@link HudLayout} puts them. Fundamentum adds one GUI overlay above the hotbar
 * ({@code fundamentum:hud_layers}); inside it every visible layer's drawer runs in {@link HudLayers#visible()} order.
 * Hidden with F1. Reads {@code config/fundamentum-hud-layout.json} once, when the overlays are registered.
 *
 * <p>A layer registered with a size is a HUD element: the renderer moves and scales it to its placement and the drawer
 * draws at 0, 0 in its own box, using {@link #fade(int)} on its colours so the player's opacity applies. The player can
 * then drag, scale, fade and switch it off in the HUD editor with no more code:
 * <pre>{@code HudLayerRenderer.register(new ResourceLocation("mymod", "mana_bar"), 100, true,
 *     new HudLayout.Placement(HudBox.Anchor.BOTTOM_LEFT, 4, -4), () -> 82, () -> 7, (g, partial) -> drawBar(g));}</pre>
 * A layer registered without a size draws in screen space as before; the editor can still switch it off, move it and
 * scale it as a whole.
 *
 * <p>The vanilla boss bars are an element too ({@link #BOSS_BARS}): moving or scaling them in the editor moves the
 * vanilla overlay, so boss bars from {@code Boss} and every other mod follow.
 */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HudLayerRenderer {
    private static final Logger LOG = LoggerFactory.getLogger("fundamentum");
    private static final Map<ResourceLocation, Element> ELEMENTS = new ConcurrentHashMap<>();
    /** The vanilla boss bars, as an element the editor can move. */
    public static final ResourceLocation BOSS_BARS = new ResourceLocation("fundamentum", "boss_bars");
    private static final int BOSS_W = 182;
    private static final int BOSS_H = 19;
    private static final int BOSS_TOP = 3;

    private static final boolean PROFILE = Boolean.getBoolean("fundamentum.hudprofile");
    private static final long[] FRAMES = new long[8192];
    private static int frames;

    private static float opacity = 1;
    private static int visibleVersion = -1;
    private static java.util.List<ResourceLocation> visible = java.util.List.of();
    private static boolean bossPushed;

    /** Draws one layer (1.20.1: the frame's partial tick). */
    @FunctionalInterface
    public interface Drawer {
        void draw(GuiGraphics graphics, float partialTick);
    }

    private record Element(Drawer drawer, IntSupplier width, IntSupplier height, boolean sized) {
    }

    static {
        HudLayers.register(BOSS_BARS, 0, true);
        HudLayout.setDefault(BOSS_BARS.toString(), new HudLayout.Placement(HudBox.Anchor.TOP_CENTER, 0, BOSS_TOP));
        ELEMENTS.put(BOSS_BARS, new Element(null, () -> BOSS_W, () -> BOSS_H, true));
    }

    private HudLayerRenderer() {
    }

    /** Declares a layer (on by default) drawn in screen space. */
    public static void register(ResourceLocation id, int order, Drawer drawer) {
        register(id, order, true, drawer);
    }

    public static void register(ResourceLocation id, int order, boolean defaultOn, Drawer drawer) {
        HudLayers.register(id, order, defaultOn);
        ELEMENTS.put(id, new Element(drawer, () -> 0, () -> 0, false));
        HudLayout.touch();
    }

    /**
     * Declares a HUD element: a layer with a box {@code width} by {@code height} GUI pixels (asked each frame, so keep it
     * cheap) that the player can place. The drawer draws inside the box from 0, 0. A size of 0 draws nothing.
     */
    public static void register(ResourceLocation id, int order, boolean defaultOn, HudLayout.Placement defaults, IntSupplier width,
        IntSupplier height, Drawer drawer) {
        HudLayers.register(id, order, defaultOn);
        HudLayout.setDefault(id.toString(), defaults);
        ELEMENTS.put(id, new Element(drawer, width, height, true));
    }

    /** Whether the layer has its own box (false: it draws in screen space). */
    public static boolean isSized(ResourceLocation id) {
        Element e = ELEMENTS.get(id);
        return e != null && e.sized();
    }

    /** Opens the HUD editor when its key is pressed. */
    @SubscribeEvent
    public static void editorKey(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        while (com.fundamentum.client.Keys.HUD_EDITOR.consumeClick()) {
            if (mc.player != null && mc.screen == null) mc.setScreen(new HudEditorScreen(null));
        }
    }

    /** Whether the editor can show this layer. */
    public static boolean isElement(ResourceLocation id) {
        return ELEMENTS.containsKey(id);
    }

    /** The opacity of the element being drawn now (1 outside elements). */
    public static float opacity() {
        return opacity;
    }

    /** An ARGB colour with the current element's opacity applied. */
    public static int fade(int argb) {
        if (opacity >= 1) return argb;
        int a = Math.round((argb >>> 24) * opacity);
        return a << 24 | argb & 0xFFFFFF;
    }

    /**
     * Where an element is on a {@code guiW} x {@code guiH} screen, {x, y, width, height} after scaling; a layer without a
     * size covers the screen, and an element with nothing to show gets a small box. Null for an unknown id.
     */
    public static int[] box(ResourceLocation id, int guiW, int guiH) {
        Element e = ELEMENTS.get(id);
        if (e == null) return null;
        HudLayout.Placement p = HudLayout.get(id.toString());
        if (!e.sized()) {
            return new int[] {p.x(), p.y(), Math.round(guiW * p.scale()), Math.round(guiH * p.scale())};
        }
        int w = e.width().getAsInt();
        int h = e.height().getAsInt();
        if (w <= 0 || h <= 0) {
            w = 40;
            h = 10;
        }
        int[] at = HudLayout.place(p, guiW, guiH, w, h, hotbarX(guiW));
        return new int[] {at[0], at[1], (int) Math.ceil(w * p.scale()), (int) Math.ceil(h * p.scale())};
    }

    /** The first free x right of the hotbar for the local player. */
    public static int hotbarX(int guiW) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return HudBox.hotbarRight(guiW, false, false);
        HumanoidArm offArm = mc.player.getMainArm().getOpposite();
        return HudBox.hotbarRight(guiW, offArm == HumanoidArm.RIGHT && !mc.player.getOffhandItem().isEmpty(),
            offArm == HumanoidArm.LEFT && mc.options.attackIndicator().get() == AttackIndicatorStatus.HOTBAR);
    }

    /** Frame times of the HUD layers in nanoseconds since the last call (only with -Dfundamentum.hudprofile=true). */
    public static synchronized long[] takeFrameNanos() {
        long[] out = Arrays.copyOf(FRAMES, Math.min(frames, FRAMES.length));
        frames = 0;
        return out;
    }

    /** The overlay registration, on the mod bus. */
    @Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Layers {
        private Layers() {
        }

        @SubscribeEvent
        public static void layers(RegisterGuiOverlaysEvent event) {
            HudLayers.loadDefault();
            event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "hud_layers", (ForgeGui gui, GuiGraphics g, float partial, int width, int height) -> render(g, partial));
        }
    }

    @SubscribeEvent
    public static void bossPre(RenderGuiOverlayEvent.Pre event) {
        if (!VanillaGuiOverlay.BOSS_EVENT_PROGRESS.id().equals(event.getOverlay().id())) return;
        bossPushed = false;
        if (!HudLayers.enabled(BOSS_BARS)) {
            event.setCanceled(true);
            return;
        }
        String key = BOSS_BARS.toString();
        if (!HudLayout.changed(key)) return;
        GuiGraphics g = event.getGuiGraphics();
        HudLayout.Placement p = HudLayout.get(key);
        int[] at = HudLayout.place(p, g.guiWidth(), g.guiHeight(), BOSS_W, BOSS_H, 0);
        g.pose().pushPose();
        g.pose().translate(at[0], at[1], 0);
        g.pose().scale(p.scale(), p.scale(), 1);
        g.pose().translate(-(g.guiWidth() / 2 - 91), -BOSS_TOP, 0);
        bossPushed = true;
    }

    @SubscribeEvent
    public static void bossPost(RenderGuiOverlayEvent.Post event) {
        if (bossPushed && VanillaGuiOverlay.BOSS_EVENT_PROGRESS.id().equals(event.getOverlay().id())) {
            event.getGuiGraphics().pose().popPose();
            bossPushed = false;
        }
    }

    private static void render(GuiGraphics g, float partial) {
        if (ELEMENTS.size() <= 1 || Minecraft.getInstance().options.hideGui) return;
        long start = PROFILE ? System.nanoTime() : 0;
        int guiW = g.guiWidth();
        int guiH = g.guiHeight();
        int version = HudLayout.version();
        if (version != visibleVersion) {
            visible = HudLayers.visible();
            visibleVersion = version;
        }
        for (ResourceLocation id : visible) {
            Element e = ELEMENTS.get(id);
            if (e == null || e.drawer() == null) continue;
            HudLayout.Placement p = HudLayout.get(id.toString());
            int[] at = null;
            if (e.sized()) {
                int w = e.width().getAsInt();
                int h = e.height().getAsInt();
                if (w <= 0 || h <= 0) continue;
                at = HudLayout.place(p, guiW, guiH, w, h, hotbarX(guiW));
            }
            g.pose().pushPose();
            try {
                if (at != null) g.pose().translate(at[0], at[1], 0);
                else g.pose().translate(p.x(), p.y(), 0);
                if (p.scale() != 1) g.pose().scale(p.scale(), p.scale(), 1);
                opacity = p.opacity();
                e.drawer().draw(g, partial);
            } catch (RuntimeException ex) {
                LOG.error("HUD layer {} failed and is now off", id, ex);
                HudLayers.setEnabled(id, false);
            } finally {
                opacity = 1;
                g.pose().popPose();
            }
        }
        if (PROFILE) record(System.nanoTime() - start);
    }

    private static synchronized void record(long nanos) {
        FRAMES[frames % FRAMES.length] = nanos;
        frames++;
    }
}
