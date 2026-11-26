package com.fundamentum.hudshot;

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
import org.lwjgl.glfw.GLFW;

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
    private static final FId DRAGONLING = FId.of("ostentum", "dragonling");

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
        if (!HudPerf.ON || "perf".equals(HudPerf.MODE) || "guidesplit".equals(HudPerf.MODE) || "guideextras".equals(HudPerf.MODE) || !HudPerf.done) return;
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
            case 5 -> mc.setScreen(new HudEditorScreen(null));
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
                HudEditorScreen editor = (HudEditorScreen) mc.screen;
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
                mc.screen.onClose();
                var file = mc.gameDirectory.toPath().resolve("config/fundamentum-hud-layout.json");
                check(Files.exists(file) && Files.readString(file).contains(moved.toString()), " Done saved " + file.getFileName());
            }
            case 75 -> HudPerf.shot(mc, "04-hud-moved");
            case 80 -> {
                HudLayout.resetAll();
                HudLayers.saveDefault();
                mc.setScreen(GuideScreen.at(BASICS));
            }
            case 100 -> HudPerf.shot(mc, "05-guide-basics");
            case 105 -> {
                Method link = GuideScreen.class.getDeclaredMethod("link", FId.class);
                link.setAccessible(true);
                link.invoke(mc.screen, FURNACE_PAGE);
                check(String.valueOf(view(mc.screen)).contains("furnace"), " the link opened the furnace page: " + view(mc.screen));
            }
            case 125 -> HudPerf.shot(mc, "06-guide-link");
            case 130 -> {
                check(clickBack(mc), " pressed Back");
                check(String.valueOf(view(mc.screen)).contains("basics"), " Back went to basics: " + view(mc.screen));
            }
            case 150 -> HudPerf.shot(mc, "07-guide-back");
            case 155 -> {
                check(clickRow(mc, "Furnaces"), " pressed the Furnaces page in the list");
                check(String.valueOf(view(mc.screen)).contains("furnace"), " the list opened the furnace page: " + view(mc.screen));
            }
            case 175 -> HudPerf.shot(mc, "08-guide-list-page");
            case 180 -> {
                MultiblockPattern pattern = Multiblocks.pattern(FURNACE);
                MultiblockScene.Scene scene = pattern == null ? null : MultiblockScene.of(pattern);
                check(scene != null, " the furnace pattern has a scene: " + (scene == null ? "none" : scene.cells().size() + " blocks"));
                mc.setScreen(GuideScreen.at(ALTAR));
            }
            case 200 -> HudPerf.shot(mc, "09-multiblock-page");
            case 205 -> {
                GuideCanvas c = canvas(mc.screen);
                check(c != null, " the guide has a canvas");
                if (c != null) c.setYaw(c.yaw() + 90);
            }
            case 225 -> HudPerf.shot(mc, "10-multiblock-rotated");
            case 230 -> mc.setScreen(new InventoryScreen(mc.player));
            case 240 -> {
                List<Component> lines = Screen.getTooltipFromItem(mc, new ItemStack(Items.STONE));
                boolean hint = lines.stream().anyMatch(l -> l.getString().contains("guide page"));
                check(hint, " stone's tooltip has the guide hint: " + lines.stream().map(Component::getString).toList());
                ScreenEvent.KeyPressed.Pre key = new ScreenEvent.KeyPressed.Pre(mc.screen, new KeyEvent(GLFW.GLFW_KEY_G, 0, 0));
                NeoForge.EVENT_BUS.post(key);
                check(key.isCanceled() && mc.screen instanceof GuideScreen, " G on the hovered stone opened the guide: " + mc.screen);
                if (mc.screen instanceof GuideScreen) check(String.valueOf(view(mc.screen)).contains("basics"), " at the basics page: " + view(mc.screen));
            }
            case 260 -> HudPerf.shot(mc, "11-guide-from-g-key");
            case 265 -> mc.setScreen(new TipScreen(new ItemStack(Items.STONE)));
            case 285 -> HudPerf.shot(mc, "12-tooltip-hint");
            case 290 -> {
                mc.setScreen(null);
                libBefore = Guides.library();
                patternBefore = Multiblocks.pattern(FURNACE);
                say("F3+T: reloading resources");
                reload = mc.reloadResourcePacks();
                reloadStep = 1;
            }
            case 300 -> mc.setScreen(GuideScreen.at(ALTAR));
            case 320 -> HudPerf.shot(mc, "13-after-reload");
            case 325 -> {
                mc.setScreen(null);
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
            // the shipped themes and the list tools for big packs: tab pages, folding chapters, pins and recent pages
            case 430 -> mc.setScreen(GuideScreen.at(FId.of("shotslate", "intro")));
            case 450 -> {
                HudPerf.shot(mc, "16-theme-deepslate");
                String rows = String.valueOf(field(mc.screen, "rows"));
                check(rows.contains("fold=shotslate:power, open=false"), " other chapters fold shut: " + rows);
                check(rows.contains("fold=shotslate:start, open=true"), " the open page's chapter is open");
            }
            case 455 -> mc.setScreen(GuideScreen.at(FId.of("shotdark", "intro")));
            case 475 -> HudPerf.shot(mc, "17-theme-dark");
            case 480 -> mc.setScreen(GuideScreen.at(FId.of("shotbanner", "intro")));
            case 500 -> HudPerf.shot(mc, "18-theme-cherry");
            case 505 -> {
                prefs("togglePin", new Class<?>[] {FId.class}, FId.of("shotbanner", "intro"));
                mc.setScreen(GuideScreen.at(FId.of("shotbanner", "intro")));
            }
            case 525 -> {
                HudPerf.shot(mc, "19-pinned-star");
                check(Boolean.TRUE.equals(prefs("isPinned", new Class<?>[] {FId.class}, FId.of("shotbanner", "intro"))), " the star pinned the page");
                check(field(mc.screen, "pins") instanceof List<?> pins && pins.size() == 1, " the pinned page has a tab on the right: " + field(mc.screen, "pins"));
                check(!String.valueOf(field(mc.screen, "rows")).contains("fold=#pinned"), " pinned pages are tabs, not a list section");
            }
            case 530 -> mc.setScreen(GuideScreen.at(FId.of("shotbooke", "intro")));
            case 550 -> {
                HudPerf.shot(mc, "20-tab-scroll");
                Object tabs = field(mc.screen, "tabs");
                Object scroll = field(null, "tabScroll");
                say("book tabs: " + (tabs instanceof List<?> l ? l.size() : tabs) + ", scrolled " + scroll);
                check(tabs instanceof List<?> l && field(mc.screen, "h") instanceof Integer hh && 8 + l.size() * 29 > hh - 4, " there are more book tabs than fit: " + tabs);
                check(scroll instanceof Integer n && n > 0, " the tab column scrolled to show the open book's tab: " + scroll);
            }
            case 552 -> {
                // wheel up over the tab column scrolls it back toward the top
                Object before = field(null, "tabScroll");
                int x = (Integer) field(mc.screen, "x0") - 14;
                int y = (Integer) field(mc.screen, "y0") + 40;
                mc.screen.mouseScrolled(x, y, 0, 3);
                check(field(null, "tabScroll") instanceof Integer n && before instanceof Integer b && n < b, " the wheel scrolls the book tabs: " + before + " -> " + field(null, "tabScroll"));
            }
            case 554 -> HudPerf.shot(mc, "20b-tab-scrolled");
            case 555 -> {
                prefs("toggle", new Class<?>[] {String.class, boolean.class}, "shotbooke:power", false);
                mc.setScreen(GuideScreen.at(FId.of("shotbooke", "intro")));
            }
            case 575 -> {
                HudPerf.shot(mc, "21-chapter-opened");
                String rows = String.valueOf(field(mc.screen, "rows"));
                check(rows.contains("Generators"), " opening Power lists its pages");
                check(rows.contains("depth=2, tree=") , " pages sit two levels down the tree: " + rows);
            }
            case 580 -> mc.setScreen(GuideScreen.at(BASICS));
            case 600 -> {
                HudPerf.shot(mc, "22-pinned-and-recent");
                String rows = String.valueOf(field(mc.screen, "rows"));
                check(rows.contains("fold=#recent, open=true") && rows.contains("Intro"), " Recent lists the pages just read: " + rows);
                var file = mc.gameDirectory.toPath().resolve("config/fundamentum-guide.json");
                check(Files.exists(file) && Files.readString(file).contains("shotbanner:intro"), " the pin is saved in " + file.getFileName());
            }
            case 605 -> mc.setScreen(new com.fundamentum.guide.client.GuideThemePicker(mc.screen));
            case 625 -> HudPerf.shot(mc, "23-theme-picker");
            // the shared bestiary, with Ostentum in the mods folder: locked until a dragonling has been seen, then its stat sheet
            case 630 -> {
                if (!net.neoforged.fml.ModList.get().isLoaded("ostentum")) {
                    say("bestiary skipped: Ostentum is not in run-hudshot/mods");
                    t = 869;
                    return;
                }
                var book = com.fundamentum.guide.book.GuideBooks.library().books().get(com.fundamentum.guide.client.Bestiary.BOOK);
                check(book != null, " the bestiary book is loaded");
                if (book != null) {
                    var ids = book.entries().stream().map(e -> e.entity().toString()).toList();
                    say("bestiary entries " + ids);
                    check(ids.containsAll(List.of("ostentum:dragonling", "ostentum:sky_dolphin", "ostentum:bite_bat", "ostentum:guardian_totem"))
                        && !ids.contains("ostentum:puff") && !ids.contains("ostentum:zipline"), " the four Ostentum mobs are listed, puff and non-mobs left out");
                }
                check(!com.fundamentum.guide.client.BookProgress.seen(DRAGONLING), " the dragonling is not seen yet");
                mc.setScreen(com.fundamentum.guide.client.BookScreen.of(com.fundamentum.guide.client.Bestiary.BOOK));
                check(mc.screen instanceof com.fundamentum.guide.client.BookScreen, " the bestiary opens");
            }
            case 660 -> HudPerf.shot(mc, "24-bestiary-locked");
            case 665 -> {
                mc.setScreen(null);
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                    // Ostentum's mobs are noSummon, so the dragonling is spawned from code
                    int i = 0;
                    for (String mob : List.of("dragonling", "bite_bat", "sky_dolphin", "guardian_totem")) {
                        var e = com.fundamentum.port.FVanilla.entityType(FId.of("ostentum", mob)).create(p.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                        if (e == null) { say("FAIL " + mob + " could not be made"); continue; }
                        e.snapTo(p.getX() + 3, p.getY() + 1, p.getZ() + 2 * i++, 0F, 0F);
                        if (e instanceof net.minecraft.world.entity.Mob m) m.setNoAi(true);
                        p.level().addFreshEntity(e);
                    }
                });
            }
            case 720 -> {
                check(com.fundamentum.guide.client.BookProgress.seen(DRAGONLING), " the dragonling counts as seen");
                var rows = com.fundamentum.guide.client.Bestiary.rows(DRAGONLING);
                say("dragonling rows " + rows);
                check(rows.size() >= 4 && rows.get(0).key().endsWith(".health"), " the dragonling has a stat table");
                mc.setScreen(com.fundamentum.guide.client.BookScreen.of(com.fundamentum.guide.client.Bestiary.BOOK));
            }
            case 745 -> mc.screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_RIGHT, 0, 0));
            case 770 -> HudPerf.shot(mc, "25-bestiary-dragonling");
            case 775, 805, 835 -> mc.screen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_RIGHT, 0, 0));
            case 800 -> HudPerf.shot(mc, "25b-bestiary-bite-bat");
            case 830 -> HudPerf.shot(mc, "25c-bestiary-sky-dolphin");
            case 860 -> HudPerf.shot(mc, "25d-bestiary-guardian-totem");
            case 870 -> {
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

    private static Object field(Screen s, String name) {
        try {
            Field f = GuideScreen.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(s);
        } catch (ReflectiveOperationException e) {
            return "?" + e;
        }
    }

    private static Object prefs(String name, Class<?>[] types, Object... args) {
        try {
            Method m = Class.forName("com.fundamentum.guide.client.GuidePrefs").getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (ReflectiveOperationException e) {
            fail("GuidePrefs." + name + " threw " + e);
            return null;
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
    /** The guide draws its own back arrow and list rows, so these click where they are drawn. */
    private static boolean clickBack(Minecraft mc) {
        Object rx = field(mc.screen, "rx");
        Object ly = call(mc.screen, "backY");
        if (!(rx instanceof Integer x) || !(ly instanceof Integer y)) return false;
        return clickAt(mc, x + 6, y + 8);
    }

    private static boolean clickRow(Minecraft mc, String label) {
        if (!(field(mc.screen, "rows") instanceof List<?> rows) || !(field(mc.screen, "lx") instanceof Integer lx)
            || !(field(mc.screen, "wellY") instanceof Integer wellY) || !(field(mc.screen, "listScroll") instanceof Double scroll)) return false;
        int y = wellY + 1 - (int) (double) scroll;
        for (Object r : rows) {
            int h = (Integer) call(r, "height");
            if (call(r, "label") instanceof Component c && c.getString().startsWith(label) && call(r, "target") != null) {
                return clickAt(mc, lx + 20, y + h / 2);
            }
            y += h;
        }
        return false;
    }

    private static boolean clickAt(Minecraft mc, double x, double y) {
        MouseButtonEvent e = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
        boolean hit = mc.screen.mouseClicked(e, false);
        mc.screen.mouseReleased(e);
        return hit;
    }

    private static Object call(Object o, String name) {
        try {
            Method m = o.getClass().getDeclaredMethod(name);
            m.setAccessible(true);
            return m.invoke(o);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static boolean click(Minecraft mc, String label) {
        for (var w : mc.screen.children()) {
            if (w instanceof Button b && b.active && b.getMessage().getString().equals(label)) {
                double x = b.getX() + b.getWidth() / 2.0;
                double y = b.getY() + b.getHeight() / 2.0;
                MouseButtonEvent e = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
                mc.screen.mouseClicked(e, false);
                mc.screen.mouseReleased(e);
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
