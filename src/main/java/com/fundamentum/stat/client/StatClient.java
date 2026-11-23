package com.fundamentum.stat.client;

import com.fundamentum.client.HudBox;
import com.fundamentum.client.Keys;
import com.fundamentum.hud.HudLayout;
import com.fundamentum.hud.client.HudLayerRenderer;
import com.fundamentum.port.FId;
import com.fundamentum.stat.StatDef;
import com.fundamentum.stat.Stats;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

/**
 * Client only: the stats HUD layer ({@code fundamentum:stats}, the player's stats marked {@code hud}), the stats screen
 * key and item tooltips for item stats. The HUD text is built when the values change (the server sends them only then),
 * not every frame.
 */
public final class StatClient {
    public static final ResourceLocation LAYER = new ResourceLocation("fundamentum", "stats");

    private static final int ROW = 10;
    private static List<Component> lines = List.of();
    private static int width;
    private static int builtFor = -1;
    private static String builtLang = "";
    private static int builtEpoch = -1;

    private StatClient() { }

    public static void init() {
        HudLayerRenderer.register(LAYER, 400, true, new HudLayout.Placement(HudBox.Anchor.TOP_LEFT, 4, 4),
            () -> refresh() ? width : 0, () -> refresh() ? lines.size() * ROW - 1 : 0, StatClient::draw);
        MinecraftForge.EVENT_BUS.addListener(StatClient::tick);
        MinecraftForge.EVENT_BUS.addListener(StatClient::tooltip);
    }

    private static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        while (Keys.STATS.consumeClick()) {
            if (mc.player != null && mc.screen == null) mc.setScreen(new StatsScreen());
        }
    }

    private static void tooltip(ItemTooltipEvent event) {
        event.getToolTip().addAll(Stats.tooltip(event.getItemStack(), Stats.clientDefs()));
    }

    /** Rebuilds the lines when the values, declarations or language changed; true when there is something to show. */
    private static boolean refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        int v = Stats.clientVersion();
        String lang = mc.options.languageCode;
        int epoch = com.fundamentum.client.ClientRefresh.epoch();
        if (v != builtFor || epoch != builtEpoch || !lang.equals(builtLang)) {
            builtFor = v;
            builtEpoch = epoch;
            builtLang = lang;
            Map<FId, StatDef> defs = Stats.clientDefs();
            List<Component> out = new ArrayList<>();
            int w = 0;
            for (Map.Entry<FId, Double> e : Stats.clientValues().entrySet()) {
                StatDef d = defs.get(e.getKey());
                if (d == null || !d.hud() || d.scope() == StatDef.Scope.ITEM) continue;
                Component line = Stats.line(d, e.getValue());
                out.add(line);
                w = Math.max(w, mc.font.width(line));
            }
            lines = List.copyOf(out);
            width = w;
        }
        return !lines.isEmpty();
    }

    private static void draw(GuiGraphics g, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        int color = HudLayerRenderer.fade(0xFFFFFFFF);
        int y = 0;
        for (Component line : lines) {
            g.drawString(mc.font, line, 0, y, color, true);
            y += ROW;
        }
    }
}
