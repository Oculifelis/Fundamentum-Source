package com.fundamentum.cosmetic;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.RenderPipelines;
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
            minecraft.gui.setScreen(new InventoryScreen(minecraft.player));
        }));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        this.xMouse = mouseX;
        this.yMouse = mouseY;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int xo = leftPos;
        int yo = topPos;
        graphics.blit(RenderPipelines.GUI_TEXTURED, INVENTORY_LOCATION, xo, yo, 0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
        // Blank the offhand slot and the crafting grid.
        graphics.fill(xo + 76, yo + 3, xo + 173, yo + 83, PANEL);
        InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, xo + 26, yo + 8, xo + 75, yo + 78, 30, 0.0625F, xMouse, yMouse, minecraft.player);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int xm, int ym) {
        graphics.text(font, title, titleLabelX, titleLabelY, LABEL, false);
        int y = titleLabelY + 14;
        for (FormattedCharSequence line : font.split(Component.translatable("fundamentum.cosmetic_armor.hint"), imageWidth - titleLabelX - 8)) {
            graphics.text(font, line, titleLabelX, y, 0xFF606060, false);
            y += font.lineHeight + 1;
        }
    }
}
