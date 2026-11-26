package com.fundamentum.weight.client;

import com.fundamentum.client.HudBox;
import com.fundamentum.hud.HudLayout;
import com.fundamentum.hud.client.HudLayerRenderer;
import com.fundamentum.weight.Weights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * Client only: the weight meter, shown only while the server has a weight part on. The {@code meter} config picks the
 * look: {@code BAR} is one bar left of the dodge bars (drawn by the dodge layer), {@code ICONS} is a row of ten weights
 * above the food bar ({@code fundamentum:weight}, movable in the HUD editor). With inventory weight on the meter shows
 * the carried fraction, otherwise the equipment load.
 */
public final class WeightClient {
    public static final ResourceLocation LAYER = new ResourceLocation("fundamentum", "weight");
    public static final int BAR_W = 5;
    private static final int ICONS = 10;
    private static final int ICON = 9;
    private static final int STEP = 8;
    private static final int BOX_W = STEP * (ICONS - 1) + ICON;
    private static final int EMPTY = 0xA0000000;
    /** Green, yellow, orange, red: the meter colour by step. */
    private static final int[] LEVEL = {0xFF78C85A, 0xFFE6C83C, 0xFFE68228, 0xFFD23228};
    private static final String[] PATTERN = {
        "..#####..",
        ".#.....#.",
        ".#.###.#.",
        "..#####..",
        ".#22223#.",
        "#2111223#",
        "#2111223#",
        "#2222233#",
        ".#######.",
    };
    private static final int[] GREY = {0xFFC8C8C8, 0xFF828282, 0xFF464646};
    private static final int[] RED = {0xFFF0786E, 0xFFBE2828, 0xFF6E1414};
    private static final int[] DARK = {0x80303030, 0x80303030, 0x80303030};

    private WeightClient() { }

    public static void init() {
        HudLayerRenderer.register(LAYER, 413, true, new HudLayout.Placement(HudBox.Anchor.BOTTOM_CENTER, 50, -40),
            () -> iconsShown() ? BOX_W : 0, () -> iconsShown() ? ICON : 0, WeightClient::draw);
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END && Minecraft.getInstance().player == null && Weights.client() != Weights.WeightState.NONE) Weights.clientReset();
        });
    }

    private static boolean shown() {
        Minecraft mc = Minecraft.getInstance();
        return Weights.hud() && Weights.client().on() && mc.player != null && !mc.player.isSpectator();
    }

    /** True when the bar look is on; the dodge layer then draws it. */
    public static boolean barShown() {
        return shown() && Weights.meter() == Weights.Meter.BAR;
    }

    private static boolean iconsShown() {
        return shown() && Weights.meter() == Weights.Meter.ICONS;
    }

    /** The fraction the meter shows: carried weight when that part is on, else the equipment load. */
    private static float fraction() {
        Weights.WeightState s = Weights.client();
        return s.invOn() ? s.inv() : s.load();
    }

    /** The colour level from 0 (green) to 3 (red). */
    private static int level() {
        Weights.WeightState s = Weights.client();
        int step = s.invOn() ? s.invStep() : s.loadStep();
        int top = s.invOn() ? s.invSteps() : s.loadSteps() - 1;
        if (top <= 0) return 0;
        return Math.max(0, Math.min(3, (int) Math.round(step * 3.0 / top)));
    }

    /** Draws the bar look at {@code x}, {@code h} tall; the top lights red when over capacity. */
    public static void drawBar(GuiGraphics g, int x, int h) {
        float f = fraction();
        g.fill(x, 0, x + BAR_W, h, HudLayerRenderer.fade(EMPTY));
        int inner = h - 2;
        int fill = (int) Math.round(inner * Math.min(1.0, f));
        if (fill > 0) g.fill(x + 1, h - 1 - fill, x + BAR_W - 1, h - 1, HudLayerRenderer.fade(LEVEL[level()]));
        if (f > 1) g.fill(x, 0, x + BAR_W, 1, HudLayerRenderer.fade(LEVEL[3]));
    }

    private static void draw(GuiGraphics g, float partialTick) {
        float f = fraction();
        boolean over = f > 1;
        double units = Math.min(1.0, f) * ICONS;
        for (int i = 0; i < ICONS; i++) {
            int x = BOX_W - ICON - i * STEP;
            icon(g, x, DARK, ICON);
            int cols = units >= i + 1 ? ICON : units >= i + 0.5 ? 5 : 0;
            if (cols > 0) icon(g, x, over ? RED : GREY, cols);
        }
    }

    /** One weight icon, only its first {@code cols} columns (a half weight shows the left half). */
    private static void icon(GuiGraphics g, int x, int[] shade, int cols) {
        for (int y = 0; y < ICON; y++) {
            String row = PATTERN[y];
            int run = -1;
            int colour = 0;
            for (int c = 0; c <= cols; c++) {
                int k = c < cols ? colourOf(row.charAt(c), shade) : 0;
                if (k != colour || c == cols) {
                    if (colour != 0) g.fill(x + run, y, x + c, y + 1, HudLayerRenderer.fade(colour));
                    colour = k;
                    run = c;
                }
            }
        }
    }

    private static int colourOf(char ch, int[] shade) {
        return switch (ch) {
            case '#' -> shade == DARK ? 0x80000000 : 0xFF000000;
            case '1' -> shade[0];
            case '2' -> shade[1];
            case '3' -> shade[2];
            default -> 0;
        };
    }
}
