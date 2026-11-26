package com.fundamentum.dodge.client;

import com.fundamentum.client.HudBox;
import com.fundamentum.client.Keys;
import com.fundamentum.dodge.Dodges;
import com.fundamentum.hud.HudLayout;
import com.fundamentum.hud.client.HudLayerRenderer;
import com.fundamentum.port.FNet;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * Client only: sends a dodge request on the Dodge key or a double tap of a movement key (the {@code input} config picks
 * which), and draws the dodge bars left of the hotbar ({@code fundamentum:dodge}, movable in the HUD editor). The server
 * does the dodge itself.
 */
public final class DodgeClient {
    public static final ResourceLocation LAYER = new ResourceLocation("fundamentum", "dodge");
    /** The box the bars sit in, right-aligned so they hug the hotbar (past the offhand slot). */
    private static final int BOX_W = 60;
    private static final int BOX_H = 16;
    private static final int BAR_H = 16;
    private static final int READY = 0xFF5AC8FF;
    private static final int FILL = 0xFF2E6E8E;
    private static final int EMPTY = 0xA0000000;

    private static int ticks;
    /** Client tick each movement key (forward, back, left, right) was last pressed, and whether it is held now. */
    private static final int[] lastTap = {-100, -100, -100, -100};
    private static final boolean[] wasDown = new boolean[4];
    private static int progress;
    private static Dodges.DodgeState seen = Dodges.DodgeState.NONE;

    private DodgeClient() { }

    public static void init() {
        HudLayerRenderer.register(LAYER, 412, true, new HudLayout.Placement(HudBox.Anchor.BOTTOM_CENTER, -122 - BOX_W / 2, -3),
            () -> visible() ? BOX_W : 0, () -> visible() ? BOX_H : 0, DodgeClient::draw);
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) tick();
        });
    }

    private static boolean visible() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && !mc.player.isSpectator()
            && ((Dodges.hud() && Dodges.client().max() > 0) || com.fundamentum.weight.client.WeightClient.barShown());
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            if (Dodges.client() != Dodges.DodgeState.NONE) Dodges.clientReset();
            while (Keys.DODGE.consumeClick()) { }
            return;
        }
        ticks++;
        Dodges.DodgeState s = Dodges.client();
        if (s != seen) {
            seen = s;
            progress = s.progress();
        } else if (s.need() > 0 && progress < s.need()) {
            progress++;
        }
        KeyMapping[] keys = {mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight};
        Dodges.Input in = Dodges.input();
        boolean wanted = false;
        float forward = 0, strafe = 0;
        while (Keys.DODGE.consumeClick()) {
            if (in != Dodges.Input.DOUBLE_TAP && mc.screen == null) wanted = true;
        }
        if (wanted) {
            forward = (keys[0].isDown() ? 1 : 0) - (keys[1].isDown() ? 1 : 0);
            strafe = (keys[2].isDown() ? 1 : 0) - (keys[3].isDown() ? 1 : 0);
        }
        for (int i = 0; i < 4; i++) {
            boolean down = keys[i].isDown();
            if (down && !wasDown[i] && mc.screen == null) {
                boolean allowed = in != Dodges.Input.KEY && (i != 0 || Dodges.doubleTapForward());
                if (allowed && ticks - lastTap[i] <= Dodges.doubleTapTicks() && !wanted) {
                    wanted = true;
                    forward = i == 0 ? 1 : i == 1 ? -1 : 0;
                    strafe = i == 2 ? 1 : i == 3 ? -1 : 0;
                    lastTap[i] = -100;
                } else {
                    lastTap[i] = ticks;
                }
            }
            wasDown[i] = down;
        }
        if (wanted && s.max() > 0 && s.charges() > 0) {
            FNet.toServer(new Dodges.DodgeRequest(forward, strafe, mc.options.keyJump.isDown()));
        }
    }

    private static void draw(GuiGraphics g, float partialTick) {
        Dodges.DodgeState s = Dodges.client();
        int n = Dodges.hud() ? Math.max(0, s.max()) : 0;
        int gap = 2;
        boolean weight = com.fundamentum.weight.client.WeightClient.barShown();
        int room = weight ? BOX_W - com.fundamentum.weight.client.WeightClient.BAR_W - gap : BOX_W;
        int w = n == 0 ? 0 : Math.max(1, Math.min(5, (room + gap) / n - gap));
        int x = BOX_W - n * (w + gap) + gap;
        if (weight) com.fundamentum.weight.client.WeightClient.drawBar(g, Math.min(x, BOX_W + gap) - gap - com.fundamentum.weight.client.WeightClient.BAR_W, BAR_H);
        if (n == 0) return;
        for (int i = 0; i < n; i++) {
            int bx = x + i * (w + gap);
            g.fill(bx, 0, bx + w, BAR_H, HudLayerRenderer.fade(EMPTY));
            if (i < s.charges()) {
                g.fill(bx, 0, bx + w, BAR_H, HudLayerRenderer.fade(READY));
            } else if (i == s.charges() && s.need() > 0) {
                int h = (int) Math.round((BAR_H - 2) * Math.min(1.0, progress / (double) s.need()));
                if (h > 0 && w > 2) g.fill(bx + 1, BAR_H - 1 - h, bx + w - 1, BAR_H - 1, HudLayerRenderer.fade(FILL));
            }
        }
    }
}
