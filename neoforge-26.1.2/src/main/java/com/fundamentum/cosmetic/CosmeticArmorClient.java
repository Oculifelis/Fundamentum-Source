package com.fundamentum.cosmetic;

import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;

/** Client side of {@link CosmeticArmor}: the screen, the inventory button and drawing cosmetics over real armour. */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class CosmeticArmorClient {
    private static final WidgetSprites BUTTON = new WidgetSprites(
        Identifier.fromNamespaceAndPath("fundamentum", "cosmetic_armor/button"),
        Identifier.fromNamespaceAndPath("fundamentum", "cosmetic_armor/button_highlighted"));

    @SubscribeEvent
    public static void screens(RegisterMenuScreensEvent event) {
        event.register(CosmeticArmor.MENU.get(), CosmeticArmorScreen::new);
    }

    @SubscribeEvent
    public static void renderStates(RegisterRenderStateModifiersEvent event) {
        event.registerAvatarEntityModifier(new AvatarRenderStateModifier() {
            @Override
            public <T extends Avatar & ClientAvatarEntity> void accept(T entity, AvatarRenderState state) {
                if (entity instanceof Player player) {
                    CosmeticArmor.Outfit outfit = CosmeticArmor.outfit(player);
                    if (!outfit.isEmpty()) {
                        state.headEquipment = pick(outfit, 0, state.headEquipment);
                        state.chestEquipment = pick(outfit, 1, state.chestEquipment);
                        state.legsEquipment = pick(outfit, 2, state.legsEquipment);
                        state.feetEquipment = pick(outfit, 3, state.feetEquipment);
                    }
                }
            }
        });
    }

    private static ItemStack pick(CosmeticArmor.Outfit outfit, int index, ItemStack real) {
        ItemStack cosmetic = outfit.stacks().get(index);
        return cosmetic.isEmpty() ? real : cosmetic;
    }

    @SubscribeEvent
    public static void inventoryButton(ScreenEvent.Init.Post event) {
        Component label = Component.translatable("fundamentum.cosmetic_armor");
        Runnable open = () -> ClientPacketDistributor.sendToServer(CosmeticArmor.OpenPayload.INSTANCE);
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
            super(screen.getLeftPos() + dx, screen.getTopPos() + dy, 10, 10, BUTTON, b -> action.run(), label);
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
        public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            setX(screen.getLeftPos() + dx);
            setY(screen.getTopPos() + dy);
            super.extractContents(graphics, mouseX, mouseY, a);
        }
    }

    private CosmeticArmorClient() {}
}
