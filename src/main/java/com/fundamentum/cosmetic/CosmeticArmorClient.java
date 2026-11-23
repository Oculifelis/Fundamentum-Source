package com.fundamentum.cosmetic;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client side of {@link CosmeticArmor}: the screen, the inventory button and drawing cosmetics over real armour. */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class CosmeticArmorClient {
    private static final WidgetSprites BUTTON = new WidgetSprites(
        ResourceLocation.fromNamespaceAndPath("fundamentum", "cosmetic_armor/button"),
        ResourceLocation.fromNamespaceAndPath("fundamentum", "cosmetic_armor/button_highlighted"));

    /** The real armour of the player being drawn, put back after the draw; null when nothing is swapped. */
    private static ItemStack[] swapped;

    /** Last, so a cancelled render (which gets no Post) never swaps. Armour is client-side only, so this is visual. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void preRender(RenderPlayerEvent.Pre event) {
        Player player = event.getEntity();
        CosmeticArmor.Outfit outfit = CosmeticArmor.outfit(player);
        if (outfit.isEmpty() || swapped != null) {
            return;
        }
        NonNullList<ItemStack> armor = player.getInventory().armor;
        swapped = armor.toArray(ItemStack[]::new);
        for (int i = 0; i < CosmeticArmor.SLOTS.length; i++) {
            ItemStack cosmetic = outfit.stacks().get(i);
            if (!cosmetic.isEmpty()) {
                armor.set(CosmeticArmor.SLOTS[i].getIndex(), cosmetic);
            }
        }
    }

    @SubscribeEvent
    public static void postRender(RenderPlayerEvent.Post event) {
        if (swapped != null) {
            NonNullList<ItemStack> armor = event.getEntity().getInventory().armor;
            for (int i = 0; i < swapped.length; i++) {
                armor.set(i, swapped[i]);
            }
            swapped = null;
        }
    }

    @SubscribeEvent
    public static void inventoryButton(ScreenEvent.Init.Post event) {
        Component label = Component.translatable("fundamentum.cosmetic_armor");
        Runnable open = () -> PacketDistributor.sendToServer(CosmeticArmor.OpenPayload.INSTANCE);
        if (event.getScreen() instanceof InventoryScreen screen) {
            event.addListener(new CornerButton(screen, 64, 9, label, open));
        } else if (event.getScreen() instanceof CreativeModeInventoryScreen screen) {
            // Creative players never see InventoryScreen (it swaps itself for this one), so the button goes on the
            // creative inventory tab too, at the top right of its smaller player preview, shown only on that tab.
            CornerButton button = new CornerButton(screen, 94, 7, label, open);
            button.shownWhen(screen::isInventoryOpen);
            event.addListener(button);
        }
    }

    /** Shows or hides the creative screen's button as the player switches tabs (it is only for the inventory tab). */
    @SubscribeEvent
    public static void tabSwitch(ScreenEvent.Render.Pre event) {
        if (event.getScreen() instanceof CreativeModeInventoryScreen screen) {
            for (var child : screen.children()) {
                if (child instanceof CornerButton button) {
                    button.visible = button.shown.getAsBoolean();
                }
            }
        }
    }

    /**
     * A 10x10 button in the top right corner of the player preview (Curios' button takes the top left). It follows the
     * screen when the recipe book moves it.
     */
    static final class CornerButton extends ImageButton {
        private final AbstractContainerScreen<?> screen;
        private final int dx;
        private final int dy;
        private java.util.function.BooleanSupplier shown = () -> true;

        CornerButton(AbstractContainerScreen<?> screen, int dx, int dy, Component label, Runnable action) {
            super(screen.getGuiLeft() + dx, screen.getGuiTop() + dy, 10, 10, BUTTON, b -> action.run(), label);
            this.screen = screen;
            this.dx = dx;
            this.dy = dy;
            setTooltip(Tooltip.create(label));
        }

        void shownWhen(java.util.function.BooleanSupplier test) {
            this.shown = test;
            this.visible = test.getAsBoolean();
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float a) {
            setX(screen.getGuiLeft() + dx);
            setY(screen.getGuiTop() + dy);
            super.renderWidget(graphics, mouseX, mouseY, a);
        }
    }

    @EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    static final class ModEvents {
        @SubscribeEvent
        public static void screens(RegisterMenuScreensEvent event) {
            event.register(CosmeticArmor.MENU.get(), CosmeticArmorScreen::new);
        }
    }

    private CosmeticArmorClient() {}
}
