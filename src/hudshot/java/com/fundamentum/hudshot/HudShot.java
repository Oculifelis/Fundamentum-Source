package com.fundamentum.hudshot;

import com.mojang.blaze3d.platform.InputConstants;
import com.fundamentum.guide.Guides;
import com.fundamentum.guide.client.GuideCanvas;
import com.fundamentum.guide.client.GuideScreen;
import com.fundamentum.hud.HudLayers;
import com.fundamentum.hud.HudLayout;
import com.fundamentum.hud.client.HudEditorScreen;
import com.fundamentum.hud.client.HudLayerRenderer;
import com.fundamentum.multiblock.MultiblockPattern;
import com.fundamentum.multiblock.Multiblocks;
import com.fundamentum.multiblock.client.MultiblockProjector;
import com.fundamentum.multiblock.client.MultiblockScene;
import com.fundamentum.port.FId;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Test runs only ({@code gradlew runClient -Phudshot}): after {@link HudPerf} it walks the HUD editor, the guide (page,
 * link, back, list, multiblock page and its rotation), the guide key on a hovered item, the tooltip hint, a resource
 * reload (F3+T and /reload) and the ghost block preview, checking each and saving screenshots/hudshot-*.png. Log lines
 * start with HUDSHOT; the last is DONE failed=N.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class HudShot {
    private HudShot() { }

    private static final FId BASICS = FId.of("guidetest", "basics");
    private static final FId FURNACE_PAGE = FId.of("guidetest", "furnace");
    private static final FId ALTAR = FId.of("hudshot", "altar");
    private static final FId FURNACE = FId.of("multiblocktest", "furnace");

    private static int t;
    private static int failed;
    private static Identifier moved;
    private static CompletableFuture<?> reload;
    private static Object libBefore;
    private static Object patternBefore;
    private static int reloadStep;
    private static int reloadWait;
    private static BlockPos controller;
    private static int checksBefore;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!HudPerf.ON || "perf".equals(HudPerf.MODE) || !HudPerf.done) return;
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.player == null || server == null) return;
        if (reloadStep > 0 && reloadStep < 4) {
            reloadTick(server);
            return;
        }
        t++;
        try {
            step(mc, server);
        } catch (Exception e) {
            fail("step " + t + " threw " + e);
            e.printStackTrace();
        }
    }

    private static void step(Minecraft mc, MinecraftServer server) throws Exception {
        switch (t) {
            case 5 -> mc.gui.setScreen(new HudEditorScreen(null));
            case 25 -> {
                HudPerf.shot(mc, "02-hud-editor");
                // the meter rows are cached until a value changes: they must match rows built from the live values now
                for (String side : new String[] {"RIGHT", "LEFT"}) {
                    Field f = com.fundamentum.survival.client.SurvivalClient.class.getDeclaredField(side);
                    f.setAccessible(true);
                    Object s = f.get(null);
                    Field rf = s.getClass().getDeclaredField("rows");
                    rf.setAccessible(true);
                    Object cached = rf.get(s);
                    var fresh = com.fundamentum.survival.SurvivalHud.stack(com.fundamentum.survival.Survival.clientMeters(),
                        com.fundamentum.stat.Stats.clientValues(), side.equals("LEFT")).rows();
                    check(fresh.equals(cached), " cached " + side.toLowerCase() + " meter rows match the live values: " + fresh);
                }
            }
            case 30 -> {
                HudEditorScreen editor = (HudEditorScreen) mc.gui.screen();
                for (Identifier id : HudLayers.ordered()) {
                    // something with text on screen, so the moved shot shows it
                    if (id.getPath().equals("survival_left") && HudLayers.enabled(id)) {
                        moved = id;
                        break;
                    }
                }
                for (Identifier id : HudLayers.ordered()) {
                    if (moved != null) break;
                    if (HudLayerRenderer.isSized(id) && !id.equals(HudLayerRenderer.BOSS_BARS) && HudLayers.enabled(id)) {
                        moved = id;
                        break;
                    }
                }
                if (moved == null) moved = HudLayerRenderer.BOSS_BARS;
                say("moving " + moved);
                editor.select(moved);
                editor.moveTo(moved, 8, 40);
                HudLayout.Placement p = HudLayout.get(moved.toString());
                HudLayout.set(moved.toString(), p.withScale(1.5F));
                p = HudLayout.get(moved.toString());
                check(p.anchor() == com.fundamentum.client.HudBox.Anchor.TOP_LEFT && Math.abs(p.scale() - 1.5F) < 0.01F,
                    " the editor moved it to the top left anchor and scaled it: " + p);
            }
            case 50 -> HudPerf.shot(mc, "03-hud-editor-moved");
            case 55 -> {
                mc.gui.screen().onClose();
                var file = mc.gameDirectory.toPath().resolve("config/fundamentum-hud-layout.json");
                check(Files.exists(file) && Files.readString(file).contains(moved.toString()), " Done saved " + file.getFileName());
            }
            case 75 -> HudPerf.shot(mc, "04-hud-moved");
            case 80 -> {
                HudLayout.resetAll();
                HudLayers.saveDefault();
                mc.gui.setScreen(GuideScreen.at(BASICS));
            }
            case 100 -> HudPerf.shot(mc, "05-guide-basics");
            case 105 -> {
                Method link = GuideScreen.class.getDeclaredMethod("link", FId.class);
                link.setAccessible(true);
                link.invoke(mc.gui.screen(), FURNACE_PAGE);
                check(String.valueOf(view(mc.gui.screen())).contains("furnace"), " the link opened the furnace page: " + view(mc.gui.screen()));
            }
            case 125 -> HudPerf.shot(mc, "06-guide-link");
            case 130 -> {
                check(click(mc, Component.translatable("gui.back").getString()), " pressed Back");
                check(String.valueOf(view(mc.gui.screen())).contains("basics"), " Back went to basics: " + view(mc.gui.screen()));
            }
            case 150 -> HudPerf.shot(mc, "07-guide-back");
            case 155 -> {
                check(click(mc, "Furnaces"), " pressed the Furnaces page in the list");
                check(String.valueOf(view(mc.gui.screen())).contains("furnace"), " the list opened the furnace page: " + view(mc.gui.screen()));
            }
            case 175 -> HudPerf.shot(mc, "08-guide-list-page");
            case 180 -> {
                MultiblockPattern pattern = Multiblocks.pattern(FURNACE);
                MultiblockScene.Scene scene = pattern == null ? null : MultiblockScene.of(pattern);
                check(scene != null, " the furnace pattern has a scene: " + (scene == null ? "none" : scene.cells().size() + " blocks"));
                mc.gui.setScreen(GuideScreen.at(ALTAR));
            }
            case 200 -> HudPerf.shot(mc, "09-multiblock-page");
            case 205 -> {
                GuideCanvas c = canvas(mc.gui.screen());
                check(c != null, " the guide has a canvas");
                if (c != null) c.setYaw(c.yaw() + 90);
            }
            case 225 -> HudPerf.shot(mc, "10-multiblock-rotated");
            case 230 -> mc.gui.setScreen(new InventoryScreen(mc.player));
            case 240 -> {
                List<Component> lines = Screen.getTooltipFromItem(mc, new ItemStack(Items.STONE));
                boolean hint = lines.stream().anyMatch(l -> l.getString().contains("guide page"));
                check(hint, " stone's tooltip has the guide hint: " + lines.stream().map(Component::getString).toList());
                ScreenEvent.KeyPressed.Pre key = new ScreenEvent.KeyPressed.Pre(mc.gui.screen(), new KeyEvent(InputConstants.KEY_G, 0, 0));
                NeoForge.EVENT_BUS.post(key);
                check(key.isCanceled() && mc.gui.screen() instanceof GuideScreen, " G on the hovered stone opened the guide: " + mc.gui.screen());
                if (mc.gui.screen() instanceof GuideScreen) check(String.valueOf(view(mc.gui.screen())).contains("basics"), " at the basics page: " + view(mc.gui.screen()));
            }
            case 260 -> HudPerf.shot(mc, "11-guide-from-g-key");
            case 265 -> mc.gui.setScreen(new TipScreen(new ItemStack(Items.STONE)));
            case 285 -> HudPerf.shot(mc, "12-tooltip-hint");
            case 290 -> {
                mc.gui.setScreen(null);
                libBefore = Guides.library();
                patternBefore = Multiblocks.pattern(FURNACE);
                say("F3+T: reloading resources");
                reload = mc.reloadResourcePacks();
                reloadStep = 1;
            }
            case 300 -> mc.gui.setScreen(GuideScreen.at(ALTAR));
            case 320 -> HudPerf.shot(mc, "13-after-reload");
            case 325 -> {
                mc.gui.setScreen(null);
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    BlockPos feet = p.blockPosition();
                    controller = feet.offset(0, 0, 6);
                    p.teleportTo(p.level(), feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, java.util.Set.of(), 0.0F, 25.0F, true);
                    p.level().setBlockAndUpdate(controller, Blocks.CRAFTING_TABLE.defaultBlockState());
                    Multiblocks.preview(p, FURNACE, controller, Direction.NORTH, false);
                });
            }
            case 345 -> {
                List<MultiblockProjector.Ghost> g = MultiblockProjector.ghosts();
                check(!g.isEmpty(), " the preview has ghosts: " + g.size());
                BlockPos wrong = g.stream().filter(x -> x.kind() == MultiblockProjector.Kind.MISSING)
                    .map(MultiblockProjector.Ghost::pos).findFirst().orElse(null);
                server.execute(() -> {
                    ServerLevel level = server.getPlayerList().getPlayers().get(0).level();
                    if (wrong != null) level.setBlockAndUpdate(wrong, Blocks.STONE.defaultBlockState());
                    Multiblocks.pattern(FURNACE).forEach(controller, Direction.NORTH, false, (pos, part) -> {
                        if (part.air()) level.setBlockAndUpdate(pos.immutable(), Blocks.DIRT.defaultBlockState());
                    });
                });
            }
            case 365 -> {
                List<MultiblockProjector.Ghost> g = MultiblockProjector.ghosts();
                long wrong = g.stream().filter(x -> x.kind() == MultiblockProjector.Kind.WRONG).count();
                long clear = g.stream().filter(x -> x.kind() == MultiblockProjector.Kind.CLEAR).count();
                long missing = g.stream().filter(x -> x.kind() == MultiblockProjector.Kind.MISSING).count();
                check(wrong >= 1 && clear >= 1 && missing >= 1,
                    " block changes reached the ghosts: missing " + missing + ", wrong " + wrong + ", clear " + clear);
                checksBefore = MultiblockProjector.checks();
            }
            case 385 -> {
                int extra = MultiblockProjector.checks() - checksBefore;
                check(extra <= 1, " no rechecks while nothing changes (" + extra + " in 20 ticks)");
                HudPerf.shot(mc, "14-ghost-blocks");
            }
            case 390 -> server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                p.teleportTo(p.level(), controller.getX() + 5.5, controller.getY() + 3, controller.getZ() - 4.5, java.util.Set.of(), 50.0F, 23.0F, true);
            });
            case 410 -> HudPerf.shot(mc, "15-ghost-blocks-angle");
            case 420 -> {
                say("DONE failed=" + failed);
                mc.stop();
            }
            default -> { }
        }
    }

    /** F3+T, then /reload, then a short wait for the synced tables. */
    private static void reloadTick(MinecraftServer server) {
        if (reloadStep == 1 && reload.isDone()) {
            Object lib = Guides.library();
            check(lib != libBefore && Guides.library().pages().containsKey(BASICS), " F3+T reloaded the guide pages");
            say("/reload");
            reload = server.reloadResources(server.getPackRepository().getSelectedIds());
            reloadStep = 2;
        } else if (reloadStep == 2 && reload.isDone()) {
            reloadStep = 3;
            reloadWait = 40;
        } else if (reloadStep == 3 && --reloadWait <= 0) {
            Object p = Multiblocks.pattern(FURNACE);
            check(p != null, " the furnace pattern is there after /reload (new object: " + (p != patternBefore) + ")");
            reloadStep = 4;
        }
    }

    private static Object view(Screen s) {
        try {
            Field f = GuideScreen.class.getDeclaredField("view");
            f.setAccessible(true);
            return f.get(s);
        } catch (ReflectiveOperationException e) {
            return "?" + e;
        }
    }

    private static GuideCanvas canvas(Screen s) {
        for (var w : s.children()) if (w instanceof GuideCanvas c) return c;
        return null;
    }

    /** Clicks the active button with this label through the screen's own mouse handling. */
    private static boolean click(Minecraft mc, String label) {
        for (var w : mc.gui.screen().children()) {
            if (w instanceof Button b && b.active && b.getMessage().getString().equals(label)) {
                double x = b.getX() + b.getWidth() / 2.0;
                double y = b.getY() + b.getHeight() / 2.0;
                MouseButtonEvent e = new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
                mc.gui.screen().mouseClicked(e, false);
                mc.gui.screen().mouseReleased(e);
                return true;
            }
        }
        return false;
    }

    /** Draws an item's tooltip at a fixed spot, so the shot shows it without moving the mouse. */
    private static final class TipScreen extends Screen {
        private final ItemStack stack;

        TipScreen(ItemStack stack) {
            super(Component.literal("tooltip"));
            this.stack = stack;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
            super.extractRenderState(g, mx, my, a);
            g.item(this.stack, this.width / 2 - 60, this.height / 2 - 8);
            g.setTooltipForNextFrame(this.font, this.stack, this.width / 2 - 40, this.height / 2);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    static void check(boolean ok, String what) {
        if (!ok) failed++;
        say((ok ? "PASS" : "FAIL") + what);
    }

    static void fail(String why) {
        failed++;
        say("FAIL " + why);
    }

    static void say(String line) {
        HudPerf.say(line);
    }
}
