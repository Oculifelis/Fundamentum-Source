package com.fundamentum.cosmetic;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

/**
 * The cosmetic armour screen: the vanilla inventory background with the crafting area blanked, the cosmetic slots
 * where the armour column is, the player (wearing the result) and a button back to the inventory.
 */
public class CosmeticArmorScreen extends AbstractContainerScreen<CosmeticArmorMenu> {
    private static final int PANEL = 0xFFC6C6C6;
    private static final int LABEL = 0xFF404040;
    private float xMouse;
    private float yMouse;

    public CosmeticArmorScreen(CosmeticArmorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.titleLabelX = 80;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new CosmeticArmorClient.CornerButton(this, 64, 9, Component.translatable("fundamentum.cosmetic_armor.back"), () -> {
            minecraft.player.closeContainer();
            minecraft.setScreen(new InventoryScreen(minecraft.player));
        }));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float a) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, a);
        renderTooltip(graphics, mouseX, mouseY);
        this.xMouse = mouseX;
        this.yMouse = mouseY;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float a, int mouseX, int mouseY) {
        int xo = leftPos;
        int yo = topPos;
        graphics.blit(INVENTORY_LOCATION, xo, yo, 0, 0, imageWidth, imageHeight);
        // Blank the offhand slot and the crafting grid.
        graphics.fill(xo + 76, yo + 3, xo + 173, yo + 83, PANEL);
        InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, xo + 51, yo + 75, 30, xo + 51 - xMouse, yo + 75 - 50 - yMouse, minecraft.player);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int xm, int ym) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, LABEL, false);
        int y = titleLabelY + 14;
        for (FormattedCharSequence line : font.split(Component.translatable("fundamentum.cosmetic_armor.hint"), imageWidth - titleLabelX - 8)) {
            if (y + font.lineHeight > 80) break;
            graphics.drawString(font, line, titleLabelX, y, 0xFF606060, false);
            y += font.lineHeight + 1;
        }
    }
}
