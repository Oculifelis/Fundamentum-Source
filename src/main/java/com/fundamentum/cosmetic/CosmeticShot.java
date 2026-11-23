package com.fundamentum.cosmetic;

import java.lang.reflect.Method;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Dev client only ({@code gradlew runClient -Pcosmeticshot}, which also sets {@code fundamentum.nofocus}): muted, off
 * screen, in a fresh flat survival world wearing iron armour it opens the inventory and clicks the cosmetic armour
 * button, fills the cosmetic slots with gold armour through the menu, checks the outfit saved on the server and synced
 * to the client, looks at the player from the front, then does the same from the creative inventory tab, and closes
 * the game. Pictures go to run/screenshots/cosmeticshot-*.png; log lines start with COSMETICSHOT.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class CosmeticShot {
    private CosmeticShot() { }

    static final boolean ON = Boolean.getBoolean("fundamentum.cosmeticshot");

    private static boolean hidden;
    private static boolean started;
    private static int step;
    private static int ticks;
    private static int failed;
    private static int waited;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!ON) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!hidden) {
            hidden = true;
            GLFW.glfwSetWindowPos(mc.getWindow().handle(), -4000, -4000);
            mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            mc.options.pauseOnLostFocus = false;
        }
        if (mc.level == null || mc.player == null) {
            if (!started && mc.gui.screen() instanceof TitleScreen) {
                started = true;
                create(mc);
            }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        ticks++;
        switch (step) {
            case 0 -> {                                     // settle, put on iron armour, carry gold armour
                if (ticks == 1 && mc.gui.screen() != null) {
                    mc.gui.setScreen(null);
                }
                if (ticks == 60) {
                    server.execute(() -> {
                        ServerPlayer p = player(server);
                        p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                        p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                        p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                        p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                        p.getInventory().add(new ItemStack(Items.GOLDEN_HELMET));
                        p.snapTo(p.getX(), p.getY(), p.getZ(), 0.0F, 0.0F);
                    });
                }
                if (ticks == 100) {
                    mc.gui.setScreen(new InventoryScreen(mc.player));
                    next(1);
                }
            }
            case 1 -> {                                     // the survival inventory: is the button there and clear?
                if (ticks == 20) {
                    CosmeticArmorClient.CornerButton button = button(mc.gui.screen());
                    if (!(mc.gui.screen() instanceof InventoryScreen) || button == null) {
                        fail("survival inventory has no cosmetic button (screen " + name(mc.gui.screen()) + ")");
                        next(3);
                        return;
                    }
                    say("PASS survival button at " + button.getX() + "," + button.getY() + " visible=" + button.visible);
                    overlaps(mc.gui.screen(), button);
                    shot(mc, "1-survival-inventory");
                } else if (ticks == 30) {
                    click(mc.gui.screen(), button(mc.gui.screen()));
                    next(2);
                }
            }
            case 2 -> {                                     // the cosmetic screen opened; fill it through the menu
                if (ticks == 1 && !(mc.gui.screen() instanceof CosmeticArmorScreen) && waited++ < 200) {
                    // the payload round trip takes a tick or two
                    ticks = 0;
                }
                if (waited >= 200) {
                    fail("the cosmetic screen never opened (screen " + name(mc.gui.screen()) + ")");
                    next(3);
                    return;
                }
                if (mc.gui.screen() instanceof CosmeticArmorScreen && ticks == 20) {
                    say("PASS the button opened the cosmetic armour screen");
                    shot(mc, "2-cosmetic-screen-empty");
                    server.execute(() -> {
                        ServerPlayer p = player(server);
                        if (!(p.containerMenu instanceof CosmeticArmorMenu menu)) {
                            fail("server menu is " + p.containerMenu.getClass().getSimpleName());
                            return;
                        }
                        ItemStack[] gold = {new ItemStack(Items.GOLDEN_HELMET), new ItemStack(Items.GOLDEN_CHESTPLATE),
                            new ItemStack(Items.GOLDEN_LEGGINGS), new ItemStack(Items.GOLDEN_BOOTS)};
                        for (int i = 0; i < gold.length; i++) {
                            menu.slots.get(i).setByPlayer(gold[i]);
                        }
                        menu.broadcastChanges();
                        CosmeticArmor.Outfit saved = CosmeticArmor.outfit(p);
                        if (saved.get(EquipmentSlot.CHEST).is(Items.GOLDEN_CHESTPLATE) && saved.get(EquipmentSlot.FEET).is(Items.GOLDEN_BOOTS)) {
                            say("PASS the menu saved the outfit on the server");
                        } else {
                            fail("server outfit after filling the menu: " + saved.stacks());
                        }
                    });
                } else if (mc.gui.screen() instanceof CosmeticArmorScreen && ticks == 60) {
                    CosmeticArmor.Outfit synced = CosmeticArmor.outfit(mc.player);
                    if (synced.get(EquipmentSlot.HEAD).is(Items.GOLDEN_HELMET) && synced.get(EquipmentSlot.LEGS).is(Items.GOLDEN_LEGGINGS)) {
                        say("PASS the outfit synced to the client");
                    } else {
                        fail("client outfit: " + synced.stacks());
                    }
                    shot(mc, "3-cosmetic-screen-filled");
                } else if (ticks == 80) {
                    mc.player.closeContainer();
                    mc.gui.setScreen(null);
                    next(3);
                }
            }
            case 3 -> {                                     // the render: gold on top of the real iron, from the front
                if (ticks == 1) {
                    mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                    hideHud(mc, true);
                } else if (ticks == 60) {
                    shot(mc, "4-render-front");
                    ItemStack real = mc.player.getItemBySlot(EquipmentSlot.CHEST);
                    ItemStack shown = CosmeticArmor.shown(mc.player, EquipmentSlot.CHEST);
                    say((real.is(Items.IRON_CHESTPLATE) && shown.is(Items.GOLDEN_CHESTPLATE) ? "PASS" : "FAIL")
                        + " real chest " + real + ", shown " + shown + " (the picture should show gold)");
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                } else if (ticks == 100) {
                    shot(mc, "5-render-back");
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    hideHud(mc, false);
                    server.execute(() -> player(server).setGameMode(GameType.CREATIVE));
                } else if (ticks == 160) {
                    mc.gui.setScreen(new InventoryScreen(mc.player));   // swaps itself for the creative screen
                    next(4);
                }
            }
            case 4 -> {                                     // creative: the inventory tab has the button too
                if (ticks == 10 && mc.gui.screen() instanceof CreativeModeInventoryScreen screen) {
                    CreativeModeTab inventory = BuiltInRegistries.CREATIVE_MODE_TAB.getValueOrThrow(CreativeModeTabs.INVENTORY);
                    try {
                        Method select = CreativeModeInventoryScreen.class.getDeclaredMethod("selectTab", CreativeModeTab.class);
                        select.setAccessible(true);
                        select.invoke(screen, inventory);
                    } catch (ReflectiveOperationException e) {
                        fail("could not select the inventory tab: " + e);
                    }
                } else if (ticks == 30) {
                    CosmeticArmorClient.CornerButton button = button(mc.gui.screen());
                    if (!(mc.gui.screen() instanceof CreativeModeInventoryScreen screen) || button == null || !screen.isInventoryOpen() || !button.visible) {
                        fail("creative inventory tab has no visible cosmetic button (screen " + name(mc.gui.screen()) + ", button " + button + ")");
                        next(9);
                        return;
                    }
                    say("PASS creative button at " + button.getX() + "," + button.getY());
                    overlaps(mc.gui.screen(), button);
                    shot(mc, "6-creative-inventory");
                } else if (ticks == 40) {
                    click(mc.gui.screen(), button(mc.gui.screen()));
                } else if (ticks == 100) {
                    if (mc.gui.screen() instanceof CosmeticArmorScreen) {
                        say("PASS the creative button opened the cosmetic armour screen");
                        shot(mc, "7-creative-cosmetic-screen");
                    } else {
                        fail("creative button opened " + name(mc.gui.screen()));
                    }
                } else if (ticks == 120) {
                    next(9);
                }
            }
            case 9 -> {
                if (ticks == 20) {
                    say("DONE failed=" + failed);
                    mc.stop();
                }
            }
            default -> {
            }
        }
    }

    private static CosmeticArmorClient.CornerButton button(Screen screen) {
        if (screen != null) {
            for (GuiEventListener child : screen.children()) {
                if (child instanceof CosmeticArmorClient.CornerButton button) {
                    return button;
                }
            }
        }
        return null;
    }

    /** Logs every other widget whose box touches the button: another mod's button sitting on top of ours. */
    private static void overlaps(Screen screen, CosmeticArmorClient.CornerButton button) {
        int n = 0;
        for (GuiEventListener child : screen.children()) {
            if (child != button && child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible
                && w.getX() < button.getX() + button.getWidth() && button.getX() < w.getX() + w.getWidth()
                && w.getY() < button.getY() + button.getHeight() && button.getY() < w.getY() + w.getHeight()) {
                say("WARN overlapping widget " + w.getClass().getName() + " at " + w.getX() + "," + w.getY() + " " + w.getWidth() + "x" + w.getHeight());
                n++;
            }
        }
        say("overlapping widgets: " + n);
    }

    private static void click(Screen screen, CosmeticArmorClient.CornerButton button) {
        if (screen == null || button == null) {
            fail("nothing to click");
            return;
        }
        MouseButtonEvent press = new MouseButtonEvent(button.getX() + 5, button.getY() + 5, new MouseButtonInfo(0, 0));
        screen.mouseClicked(press, false);
        screen.mouseReleased(press);
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static String name(Screen screen) {
        return screen == null ? "none" : screen.getClass().getSimpleName();
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "cosmeticshot-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
            msg -> say("saved cosmeticshot-" + name + ".png"));
    }

    private static void next(int s) {
        step = s;
        ticks = 0;
    }

    private static void fail(String why) {
        failed++;
        say("FAIL " + why);
    }

    /** options.hideGui is gone on 26.2+: the HUD is hidden with gui.hud.toggle(). */
    private static void hideHud(Minecraft mc, boolean hidden) {
        if (mc.gui.hud.isHidden() != hidden) {
            mc.gui.hud.toggle();
        }
    }

    private static void say(String line) {
        System.out.println("COSMETICSHOT " + line);
    }

    private static void create(Minecraft mc) {
        say("creating a flat world");
        mc.options.renderDistance().set(8);
        mc.options.simulationDistance().set(6);
        String name = "cosmeticshot" + System.currentTimeMillis() / 1000;
        LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL,
            new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261106L, false, false),
            provider -> provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
            new TitleScreen());
    }
}
