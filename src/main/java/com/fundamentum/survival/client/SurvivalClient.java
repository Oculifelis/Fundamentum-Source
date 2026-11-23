package com.fundamentum.survival.client;

import com.fundamentum.client.HudBox;
import com.fundamentum.hud.HudLayout;
import com.fundamentum.hud.client.HudLayerRenderer;
import com.fundamentum.port.FNet;
import com.fundamentum.stat.Stats;
import com.fundamentum.survival.Survival;
import com.fundamentum.survival.SurvivalHud;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * Client only: the survival meters HUD elements ({@code fundamentum:survival} over the hunger bar and
 * {@code fundamentum:survival_left} over the hearts) and drinking from blocks with an empty hand. The rows are worked out
 * when a value or a declaration changes, not every frame.
 */
public final class SurvivalClient {
    public static final ResourceLocation LAYER = new ResourceLocation("fundamentum", "survival");
    public static final ResourceLocation LAYER_LEFT = new ResourceLocation("fundamentum", "survival_left");

    /** One side's cached rows. */
    private static final class Side {
        final boolean left;
        List<SurvivalHud.Row> rows = List.of();
        List<Component> texts = List.of();
        int width;
        int height;
        int builtStats = -1;
        int builtMeters = -1;
        String builtLang = "";
        int builtEpoch = -1;

        Side(boolean left) {
            this.left = left;
        }

        boolean refresh() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.player.isCreative() || mc.player.isSpectator() || Survival.clientMeters().isEmpty()) return false;
            int sv = Stats.clientVersion();
            int mv = Survival.clientVersion();
            String lang = mc.options.languageCode;
            int epoch = com.fundamentum.client.ClientRefresh.epoch();
            if (sv != this.builtStats || mv != this.builtMeters || epoch != this.builtEpoch || !lang.equals(this.builtLang)) {
                this.builtStats = sv;
                this.builtEpoch = epoch;
                this.builtMeters = mv;
                this.builtLang = lang;
                SurvivalHud.Stack stack = SurvivalHud.stack(Survival.clientMeters(), Stats.clientValues(), this.left);
                List<Component> t = new ArrayList<>();
                int w = SurvivalHud.WIDTH;
                for (SurvivalHud.Row r : stack.rows()) {
                    Component c = r.bar() ? null : r.text();
                    t.add(c);
                    if (c != null) w = Math.max(w, mc.font.width(c));
                }
                this.rows = stack.rows();
                this.texts = t;
                this.width = w;
                this.height = stack.height();
            }
            return !this.rows.isEmpty();
        }

        void draw(GuiGraphics g, float partial) {
            Minecraft mc = Minecraft.getInstance();
            int back = HudLayerRenderer.fade(0xA0000000);
            for (int i = 0; i < this.rows.size(); i++) {
                SurvivalHud.Row r = this.rows.get(i);
                int color = HudLayerRenderer.fade(r.color());
                if (r.bar()) {
                    g.fill(r.x(), r.y(), r.x() + r.width(), r.y() + SurvivalHud.BAR_HEIGHT, back);
                    int fill = (int) Math.round((r.width() - 2) * r.fill());
                    if (fill > 0) g.fill(r.x() + 1, r.y() + 1, r.x() + 1 + fill, r.y() + SurvivalHud.BAR_HEIGHT - 1, color);
                } else {
                    g.drawString(mc.font, this.texts.get(i), r.x(), r.y(), color, true);
                }
            }
        }
    }

    private static final Side RIGHT = new Side(false);
    private static final Side LEFT = new Side(true);

    private SurvivalClient() { }

    public static void init() {
        HudLayerRenderer.register(LAYER, 410, true, new HudLayout.Placement(HudBox.Anchor.BOTTOM_CENTER, 50, -52),
            () -> RIGHT.refresh() ? RIGHT.width : 0, () -> RIGHT.refresh() ? RIGHT.height : 0, RIGHT::draw);
        HudLayerRenderer.register(LAYER_LEFT, 411, true, new HudLayout.Placement(HudBox.Anchor.BOTTOM_CENTER, -51, -52),
            () -> LEFT.refresh() ? LEFT.width : 0, () -> LEFT.refresh() ? LEFT.height : 0, LEFT::draw);
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickEmpty e) -> {
            if (e.getHand() == InteractionHand.MAIN_HAND) tryDrink(e.getEntity());
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock e) -> {
            if (e.getLevel().isClientSide() && e.getHand() == InteractionHand.MAIN_HAND && e.getEntity().getMainHandItem().isEmpty()) tryDrink(e.getEntity());
        });
    }

    private static void tryDrink(Player p) {
        if (p == null || !p.getMainHandItem().isEmpty() || Survival.clientMeters().isEmpty()) return;
        if (Survival.drinkable(Survival.clientMeters(), Survival.drinkTarget(p))) FNet.toServer(new Survival.DrinkRequest());
    }
}
