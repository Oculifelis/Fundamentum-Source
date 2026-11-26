package com.fundamentum.quest.client;

import com.fundamentum.client.HudBox;
import com.fundamentum.client.Keys;
import com.fundamentum.hud.HudLayout;
import com.fundamentum.hud.client.HudLayerRenderer;
import com.fundamentum.port.FId;
import com.fundamentum.quest.QuestSync;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client only: takes the server's quest snapshots, opens the quest screen when the server asks (an offer, a quest-giver,
 * {@code /quests}) or on the journal key, and draws the quest tracker HUD layer {@code fundamentum:quest_tracker}.
 */
public final class QuestClient {
    public static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath("fundamentum", "quest_tracker");
    private static final int ROW = 10;
    private static Object connection;

    private QuestClient() { }

    public static void init() {
        QuestSync.sink = json -> Minecraft.getInstance().execute(() -> accept(json));
        HudLayerRenderer.register(LAYER, 420, true, new HudLayout.Placement(HudBox.Anchor.TOP_RIGHT, -4, 52),
            QuestClient::width, () -> Math.max(0, QuestJournal.hudLines().size() * ROW - 1), QuestClient::draw);
        NeoForge.EVENT_BUS.addListener(QuestClient::tick);
    }

    private static void accept(String json) {
        String[] r = QuestJournal.accept(json);
        Minecraft mc = Minecraft.getInstance();
        if (r == null || mc.player == null) return;
        if (mc.screen instanceof QuestScreen open) {
            open.focus(r[2]);
            return;
        }
        mc.setScreen(new QuestScreen(QuestScreens.get(r[1] == null ? null : FId.tryOf(r[1])), r[2]));
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Object now = mc.getConnection();
        if (now != connection) {
            connection = now;
            if (now != null) QuestJournal.send("hello", null);
        }
        while (Keys.QUESTS.consumeClick()) {
            if (mc.player != null && mc.screen == null) QuestJournal.send("open", null);
        }
    }

    private static int width() {
        Minecraft mc = Minecraft.getInstance();
        int w = 0;
        for (Component c : QuestJournal.hudLines()) w = Math.max(w, mc.font.width(c));
        return w;
    }

    private static void draw(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        int color = HudLayerRenderer.fade(0xFFFFFFFF);
        List<Component> lines = QuestJournal.hudLines();
        int y = 0;
        for (Component line : lines) {
            g.drawString(mc.font, line, 0, y, color, true);
            y += ROW;
        }
    }
}
