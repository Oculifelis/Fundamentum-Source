package com.fundamentum.guide.client;

import com.fundamentum.client.Keys;
import com.fundamentum.guide.GuidePage;
import com.fundamentum.guide.GuideResources;
import com.fundamentum.guide.Guides;
import com.fundamentum.port.FId;
import com.fundamentum.port.FVanilla;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;

/**
 * The guide on the client: reads pages on resource reload, the guide key (G: the hovered item's page in an inventory,
 * otherwise where the guide was left), a tooltip line on items that have a page, and right-clicking a book's opener
 * item.
 */
@Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class GuideClientHooks {
    /** The mod bus side: the key, the opener and the page reload. */
    @Mod.EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        private ModEvents() { }

        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            event.register(GUIDE);
        }

        @SubscribeEvent
        public static void setup(FMLClientSetupEvent event) {
            Guides.setOpener(id -> Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(GuideScreen.at(id))));
        }

        @SubscribeEvent
        public static void reload(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener(new ResourceManagerReloadListener() {
                @Override
                public void onResourceManagerReload(ResourceManager manager) {
                    Guides.set(GuideResources.read(manager));
                }
            });
        }
    }

    public static final KeyMapping GUIDE = new KeyMapping("key.fundamentum.guide", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, Keys.CATEGORY);
    private static Item hovered;
    private static long hoveredAt;

    private GuideClientHooks() { }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        while (GUIDE.consumeClick()) {
            if (mc.player != null && mc.screen == null) mc.setScreen(new GuideScreen());
        }
    }

    private static GuidePage hoveredPage() {
        if (hovered == null || System.currentTimeMillis() - hoveredAt > 300) return null;
        return pageOf(hovered);
    }

    private static final java.util.Map<Item, java.util.Optional<GuidePage>> PAGES = new java.util.HashMap<>();
    private static final java.util.Map<Item, java.util.Optional<Guides.Book>> BOOKS = new java.util.HashMap<>();
    private static Object cachedLib;
    private static Object cachedUnlocks;
    private static int cachedEpoch = -1;

    /**
     * The tooltip runs every frame while an item is hovered, so the page and book lookups are kept per item until the
     * guides reload, the player's unlocks change or the safety timer moves.
     */
    private static void checkCache() {
        Object lib = Guides.library();
        Object unlocks = com.fundamentum.unlock.Unlocks.client();
        int epoch = com.fundamentum.client.ClientRefresh.epoch();
        if (lib != cachedLib || unlocks != cachedUnlocks || epoch != cachedEpoch) {
            PAGES.clear();
            BOOKS.clear();
            cachedLib = lib;
            cachedUnlocks = unlocks;
            cachedEpoch = epoch;
        }
    }

    static GuidePage pageOf(Item item) {
        checkCache();
        return PAGES.computeIfAbsent(item, i -> java.util.Optional.ofNullable(
            Guides.pageFor(Guides.library(), FVanilla.idOf(i), GuideScreen.unlocked()))).orElse(null);
    }

    static Guides.Book bookOf(Item item) {
        checkCache();
        return BOOKS.computeIfAbsent(item, i -> java.util.Optional.ofNullable(Guides.bookOpenedBy(Guides.library(), FVanilla.idOf(i)))).orElse(null);
    }

    @SubscribeEvent
    public static void screenKey(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?>)) return;
        if (event.getScreen().getFocused() instanceof EditBox) return;
        if (!GUIDE.matches(event.getKeyCode(), event.getScanCode())) return;
        GuidePage page = hoveredPage();
        if (page == null) return;
        Minecraft.getInstance().setScreen(GuideScreen.at(page.id()));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        if (event.getItemStack().isEmpty()) return;
        Item item = event.getItemStack().getItem();
        hovered = item;
        hoveredAt = System.currentTimeMillis();
        Guides.Book book = bookOf(item);
        if (book != null) {
            event.getToolTip().add(Component.translatableWithFallback("guide.fundamentum.opener", "Use to read the %s guide", book.title())
                .withStyle(ChatFormatting.GRAY));
        }
        if (!GUIDE.isUnbound() && pageOf(item) != null) {
            event.getToolTip().add(Component.translatableWithFallback("guide.fundamentum.tooltip", "%s: guide page", GUIDE.getTranslatedKeyMessage())
                .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    @SubscribeEvent
    public static void use(PlayerInteractEvent.RightClickItem event) {
        if (!event.getLevel().isClientSide()) return;
        Guides.Book book = Guides.bookOpenedBy(Guides.library(), FVanilla.idOf(event.getItemStack().getItem()));
        if (book == null) return;
        Minecraft.getInstance().setScreen(GuideScreen.at(FId.of(book.namespace(), "book")));
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}
