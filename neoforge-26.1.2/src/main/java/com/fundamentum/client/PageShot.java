package com.fundamentum.client;

import com.fundamentum.sky.FractalSkiesClient;
import com.fundamentum.whip.Whips;
import com.fundamentum.whip.WhipsConfig;
import com.mojang.datafixers.util.Pair;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Dev client only ({@code gradlew runClient -Ppageshot} or {@code -Ppageshot=shaders}, in its own run-pageshot folder,
 * which also sets {@code fundamentum.nofocus}): muted, off screen at 1920x1080, in a fresh normal world (seed fixed) it
 * takes the mod page's pictures: Ostentum's moons and the Psychedelic Moon's fractal sky over a cherry grove, then in a
 * meadow a whip swing, cosmetic armour, the "Hold [Shift] for details" tooltip, the Jade jukebox line, portal crafting
 * and a summon, and closes the game. Needs Ostentum (and Jade for the jukebox line) in the run folder's mods. Pictures
 * go to run-pageshot/screenshots/page-*.png ("-shaders" added in the shaders run); log lines start with PAGESHOT.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class PageShot {
    private PageShot() { }

    private static final String MODE = System.getProperty("fundamentum.pageshot");
    static final boolean ON = MODE != null;
    private static final String SUFFIX = MODE != null && MODE.contains("shaders") ? "-shaders" : "";
    /** Extra settle time with a shader pack (slower chunk builds, shadow pass). */
    private static final int SLOW = SUFFIX.isEmpty() ? 1 : 2;

    private record Step(int delay, Runnable action, String section) { }

    private static final List<Step> STEPS = new ArrayList<>();
    /** The section the next steps belong to; a mode like "plain:moons,portal" runs only those (and "setup"). */
    private static String section = "setup";
    private static boolean hidden;
    private static boolean started;
    private static boolean built;
    private static int index;
    private static int ticks;
    private static int failed;
    private static BlockPos ground;
    private static BlockPos box;
    private static ItemEntity thrown;
    private static BlockPos portalBase;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!ON) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!hidden) {
            hidden = true;
            long handle = mc.getWindow().handle();
            GLFW.glfwSetWindowPos(handle, -4000, -4000);
            GLFW.glfwSetWindowSize(handle, 1920, 1080);
            mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            mc.options.pauseOnLostFocus = false;
            mc.options.hideGui = true;
            mc.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.HIDDEN);
            mc.getTutorial().setStep(TutorialSteps.NONE);
        }
        mc.getToastManager().clear();
        if (mc.level == null || mc.player == null) {
            if (!started && mc.screen instanceof TitleScreen) {
                started = true;
                create(mc);
            }
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        if (!built) {
            built = true;
            if (mc.screen != null) {
                mc.setScreen(null);
            }
            script(mc, server);
        }
        if (index >= STEPS.size()) {
            return;
        }
        ticks++;
        Step step = STEPS.get(index);
        while (!wanted(step.section) && index < STEPS.size() - 1) {
            index++;
            step = STEPS.get(index);
        }
        if (ticks >= step.delay) {
            ticks = 0;
            index++;
            try {
                step.action.run();
            } catch (RuntimeException e) {
                fail("step " + index + ": " + e);
                e.printStackTrace();
            }
        }
    }

    private static boolean wanted(String name) {
        int colon = MODE.indexOf(':');
        return name.equals("setup") || colon < 0 || java.util.Arrays.asList(MODE.substring(colon + 1).split(",")).contains(name);
    }

    private static void at(int wait, Runnable action) {
        STEPS.add(new Step(Math.max(1, wait), action, section));
    }

    private static void script(Minecraft mc, MinecraftServer server) {
        // settle, clear weather, go up over a cherry grove
        at(100, () -> {
            say("window " + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight() + " gui scale " + mc.getWindow().getGuiScale());
            cmd(server, "weather clear 1000000");
            srv(server, p -> {
                BlockPos at = find(p.level(), Biomes.CHERRY_GROVE, p.blockPosition());
                int y = surface(p.level(), Heightmap.Types.MOTION_BLOCKING, at);
                p.getAbilities().flying = true;
                p.onUpdateAbilities();
                p.teleportTo(at.getX() + 0.5, y + 14, at.getZ() + 0.5);
                say("sky spot " + at.getX() + "," + (y + 14) + "," + at.getZ());
            });
        });
        at(20, () -> mc.player.getAbilities().flying = true);
        section = "moons";
        // the moons, each rising low in the east
        String[][] moons = {{"ostentum:harvest", "harvest"}, {"ostentum:enchanted", "enchanted"}, {"ostentum:glimmer", "glimmer"}};
        boolean first = true;
        for (String[] moon : moons) {
            at(first ? 300 * SLOW : 60, () -> {
                mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
                cmd(server, "fundamentum moon start " + moon[0]);
            });
            first = false;
            at(20, () -> {
                cmd(server, "time set 14200");
                aim(mc, server, -90.0F, -14.0F);
            });
            at(200 * SLOW, () -> {
                say("moon on the client: " + com.fundamentum.moon.MoonsClient.moon());
                shot(mc, "moon-" + moon[1]);
                aim(mc, server, -60.0F, -28.0F);
            });
            at(40, () -> shot(mc, "moon-" + moon[1] + "-high"));
            at(10, () -> cmd(server, "fundamentum moon stop"));
        }
        section = "fractal";
        // the Psychedelic Moon's fractal sky
        at(100, () -> {
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.FANCY);
            cmd(server, "fundamentum moon start fundamentum:psychedelic");
        });
        at(20, () -> {
            cmd(server, "time set 18000");
            aim(mc, server, 20.0F, -22.0F);
            skyTime(0.0F);
        });
        at(300 * SLOW, () -> {
            say("fractal sky alpha " + FractalSkiesClient.alpha());
            shot(mc, "fractal-sky");
            skyTime(40.0F);
        });
        at(60, () -> shot(mc, "fractal-sky-deep"));
        at(10, () -> aim(mc, server, 45.0F, -45.0F));
        at(40, () -> shot(mc, "fractal-sky-deep-up"));
        at(10, () -> {
            skyTime(-1.0F);
            cmd(server, "fundamentum moon stop");
        });
        section = "setup";
        // down to the ground in a meadow, morning
        at(60, () -> {
            cmd(server, "time set 1500");
            srv(server, p -> {
                BlockPos at = find(p.level(), Biomes.MEADOW, p.blockPosition());
                int y = surface(p.level(), Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at);
                ground = new BlockPos(at.getX(), y, at.getZ());
                p.getAbilities().flying = false;
                p.onUpdateAbilities();
                p.teleportTo(at.getX() + 0.5, y, at.getZ() + 0.5);
                say("ground spot " + ground.toShortString());
            });
        });
        at(10, () -> mc.player.getAbilities().flying = false);
        section = "whip";
        // whip: the Blaze Whip mid-swing, from the front and behind, by day and at dusk
        at(300 * SLOW, () -> {
            hold(server, "ostentum:blaze_whip");
            aim(mc, server, 30.0F, 5.0F);
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        });
        swing(mc, server, "whip-blaze-front");
        at(40, () -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        swing(mc, server, "whip-blaze-back");
        at(40, () -> {
            cmd(server, "time set 13100");
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        });
        swing(mc, server, "whip-blaze-dusk-front");
        at(40, () -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        swing(mc, server, "whip-blaze-dusk-back");
        section = "cosmetic";
        // cosmetic armour: diamond worn, a gold outfit shown over it
        at(40, () -> {
            cmd(server, "time set 1500");
            srv(server, p -> {
                p.setGameMode(GameType.SURVIVAL);
                p.getInventory().clearContent();
                p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
                p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
                p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
                p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
                p.setItemInHand(InteractionHand.MAIN_HAND, item("ostentum:blaze_whip"));
            });
            aim(mc, server, 30.0F, 5.0F);
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        });
        at(60, () -> shot(mc, "cosmetic-1-real-armour"));
        at(10, () -> {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.hideGui = false;
            mc.setScreen(new InventoryScreen(mc.player));
        });
        at(20, () -> {
            mouse(mc, 2, 2);
            clickCorner(mc);
        });
        at(40, () -> {
            say("screen " + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()));
            srv(server, p -> {
                AbstractContainerMenu menu = p.containerMenu;
                if (!menu.getClass().getSimpleName().equals("CosmeticArmorMenu")) {
                    fail("server menu is " + menu.getClass().getSimpleName());
                    return;
                }
                ItemStack[] gold = {new ItemStack(Items.GOLDEN_HELMET), new ItemStack(Items.GOLDEN_CHESTPLATE),
                    new ItemStack(Items.GOLDEN_LEGGINGS), new ItemStack(Items.GOLDEN_BOOTS)};
                for (int i = 0; i < gold.length; i++) {
                    menu.slots.get(i).setByPlayer(gold[i]);
                }
                menu.broadcastChanges();
            });
        });
        at(40, () -> {
            mc.gui.getChat().clearMessages(false);
            shot(mc, "cosmetic-2-screen");
        });
        at(10, () -> {
            mc.player.closeContainer();
            mc.setScreen(null);
            mc.options.hideGui = true;
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        });
        at(60, () -> shot(mc, "cosmetic-3-outfit"));
        section = "tooltip";
        // the tooltip, folded and unfolded, hovering the whip in the survival inventory
        at(10, () -> {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.options.hideGui = false;
            mc.setScreen(new InventoryScreen(mc.player));
        });
        at(20, () -> hoverSlot(mc, 36));
        at(10, () -> {
            mc.gui.getChat().clearMessages(false);
            shot(mc, "tooltip-1-hold-shift");
        });
        at(5, () -> WhipsConfig.SHIFT_FOR_DETAILS.set(false));
        at(10, () -> shot(mc, "tooltip-2-details"));
        at(5, () -> {
            WhipsConfig.SHIFT_FOR_DETAILS.set(true);
            mouse(mc, 2, 2);
            mc.player.closeContainer();
            mc.setScreen(null);
        });
        section = "jade";
        // the Jade jukebox line: cat at 1:12
        at(10, () -> {
            srv(server, p -> {
                p.setGameMode(GameType.CREATIVE);
                p.getInventory().clearContent();
                for (EquipmentSlot s : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                    p.setItemSlot(s, ItemStack.EMPTY);
                }
                BlockPos feet = p.blockPosition();
                box = feet.offset(0, 0, 2);
                p.teleportTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
                ServerLevel level = p.level();
                level.setBlockAndUpdate(box.above(), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(box, Blocks.JUKEBOX.defaultBlockState());
                if (level.getBlockEntity(box) instanceof JukeboxBlockEntity j) {
                    j.setTheItem(new ItemStack(Items.MUSIC_DISC_CAT));
                }
            });
            aim(mc, server, 0.0F, 30.0F);
        });
        at(20, () -> srv(server, p -> {
            if (p.level().getBlockEntity(box) instanceof JukeboxBlockEntity j) {
                try {
                    Field f = JukeboxSongPlayer.class.getDeclaredField("ticksSinceSongStarted");
                    f.setAccessible(true);
                    f.setLong(j.getSongPlayer(), 66 * 20L);
                } catch (ReflectiveOperationException e) {
                    fail("could not jump the song: " + e);
                }
            }
        }));
        at(120, () -> {
            mc.gui.getChat().clearMessages(false);
            shot(mc, "jade-jukebox");
        });
        section = "portal";
        // portal crafting: the Dormant Delver's Pick thrown through a Nether portal wakes up
        at(20, () -> {
            mc.options.hideGui = true;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            srv(server, p -> {
                ServerLevel level = p.level();
                p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                if (box != null) {
                    level.setBlockAndUpdate(box, Blocks.AIR.defaultBlockState());
                }
                BlockPos feet = p.blockPosition();
                BlockPos base = feet.offset(0, 0, 4);
                portalBase = base;
                for (int dx = -5; dx <= 5; dx++) {
                    for (int dz = -7; dz <= 6; dz++) {
                        for (int dy = 0; dy <= 5; dy++) {
                            level.setBlockAndUpdate(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                        }
                        level.setBlockAndUpdate(feet.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
                    }
                }
                for (int dx = -2; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 3; dy++) {
                        boolean frame = dx == -2 || dx == 1 || dy == -1 || dy == 3;
                        level.setBlock(base.offset(dx, dy, 0), frame ? Blocks.OBSIDIAN.defaultBlockState()
                            : Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X), 2 | 16);
                    }
                }
                // stand off to one side, looking at the portal's front
                Vec3 eye = new Vec3(base.getX() + 1.4, feet.getY() + 1.62, base.getZ() - 2.6);
                Vec3 look = new Vec3(base.getX() - 0.2, base.getY() + 0.7, base.getZ() - 0.4);
                float[] yp = yawPitch(eye, look);
                p.teleportTo(level, eye.x, feet.getY(), eye.z, java.util.Set.of(), yp[0], yp[1], true);
                ItemEntity item = new ItemEntity(level, base.getX(), base.getY() + 0.1, base.getZ() - 1.2, item("ostentum:dormant_delvers_pick"));
                item.setDeltaMovement(Vec3.ZERO);
                item.setPickUpDelay(32767);
                level.addFreshEntity(item);
                thrown = item;
                say("portal at " + base.toShortString() + " aim " + yp[0] + "," + yp[1]);
                mc.execute(() -> {
                    mc.player.setYRot(yp[0]);
                    mc.player.setXRot(yp[1]);
                });
            });
        });
        at(80 * SLOW, () -> shot(mc, "portal-1-before"));
        at(5, () -> srv(server, p -> {
            if (thrown != null && portalBase != null) {     // into the portal; the craft pushes the result back out
                say("before: " + thrown.getItem());
                thrown.setPos(portalBase.getX(), portalBase.getY() + 0.4, portalBase.getZ() + 0.5);
                thrown.setDeltaMovement(0.0, 0.0, 0.05);
            }
        }));
        for (int i = 1; i <= 24; i++) {
            int n = i;
            at(1, () -> shot(mc, String.format("portal-burst-%02d", n)));
        }
        at(60, () -> {
            shot(mc, "portal-3-after");
            srv(server, p -> say("after: " + (thrown == null ? "none" : thrown.getItem() + " at " + thrown.position())));
        });
        section = "summon";
        // a summon: the Bottled Dragonling in the summon slot calls its dragonling
        at(20, () -> {
            srv(server, p -> {
                for (ItemEntity e : p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(16))) {
                    e.discard();
                }
                CuriosApi.getCuriosInventory(p).flatMap(inv -> inv.getStacksHandler("summon")).ifPresentOrElse(
                    h -> h.getStacks().setStackInSlot(0, item("ostentum:bottled_dragonling")),
                    () -> fail("no summon slot"));
                BlockPos b = portalBase != null ? portalBase : p.blockPosition().offset(0, 0, 3);
                p.teleportTo(p.level(), b.getX() + 0.5, b.getY(), b.getZ() - 2.0, java.util.Set.of(), 180.0F, 8.0F, true);
            });
            aim(mc, server, 180.0F, 8.0F);
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        });
        for (int i = 1; i <= 8; i++) {
            int n = i;
            at(i == 1 ? 120 : 25, () -> shot(mc, "summon-dragonling-" + n));
        }
        section = "setup";
        at(40, () -> {
            say("DONE failed=" + failed);
            mc.stop();
        });
    }

    /** Swings the held whip and takes a burst of frames through the swing. */
    private static void swing(Minecraft mc, MinecraftServer server, String name) {
        at(60, () -> {
            shot(mc, name + "-idle");
            srv(server, p -> say(name + " swing started=" + Whips.swing(p)));
        });
        for (int i = 1; i <= 8; i++) {
            int n = i;
            at(1, () -> shot(mc, name + "-" + n));
        }
    }

    private static BlockPos find(ServerLevel level, ResourceKey<Biome> biome, BlockPos from) {
        Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(h -> h.is(biome), from, 6400, 32, 64);
        if (found == null) {
            fail("no " + biome.identifier() + " near " + from.toShortString());
            return from;
        }
        return found.getFirst();
    }

    /** The surface height there, generating the chunk first (an unloaded chunk reports the world's floor). */
    private static int surface(ServerLevel level, Heightmap.Types type, BlockPos at) {
        return level.getChunk(at.getX() >> 4, at.getZ() >> 4).getHeight(type, at.getX() & 15, at.getZ() & 15) + 1;
    }

    private static float[] yawPitch(Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return new float[] {yaw, pitch};
    }

    private static void aim(Minecraft mc, MinecraftServer server, float yaw, float pitch) {
        srv(server, p -> p.snapTo(p.getX(), p.getY(), p.getZ(), yaw, pitch));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
    }

    private static void hold(MinecraftServer server, String id) {
        srv(server, p -> p.setItemInHand(InteractionHand.MAIN_HAND, item(id)));
    }

    private static ItemStack item(String id) {
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(null);
        if (item == null) {
            fail("no item " + id);
            return new ItemStack(Items.STICK);
        }
        return new ItemStack(item);
    }

    /** The Psychedelic Moon's clock (seconds into its tour), or -1 for the real one. */
    private static void skyTime(float seconds) {
        try {
            Field f = FractalSkiesClient.class.getDeclaredField("timeOverride");
            f.setAccessible(true);
            f.setFloat(null, seconds);
        } catch (ReflectiveOperationException e) {
            fail("sky clock: " + e);
        }
    }

    private static void clickCorner(Minecraft mc) {
        if (mc.screen == null) {
            fail("no inventory screen");
            return;
        }
        for (GuiEventListener child : mc.screen.children()) {
            if (child instanceof AbstractWidget w && child.getClass().getSimpleName().equals("CornerButton")) {
                MouseButtonEvent press = new MouseButtonEvent(w.getX() + 5, w.getY() + 5, new MouseButtonInfo(0, 0));
                mc.screen.mouseClicked(press, false);
                mc.screen.mouseReleased(press);
                return;
            }
        }
        fail("no cosmetic button on " + mc.screen.getClass().getSimpleName());
    }

    /** Puts the mouse over a slot of the open container screen (gui coordinates to window pixels). */
    private static void hoverSlot(Minecraft mc, int slot) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            fail("no container screen for the tooltip");
            return;
        }
        try {
            Field left = AbstractContainerScreen.class.getDeclaredField("leftPos");
            Field top = AbstractContainerScreen.class.getDeclaredField("topPos");
            left.setAccessible(true);
            top.setAccessible(true);
            var s = screen.getMenu().slots.get(slot);
            int gx = left.getInt(screen) + s.x + 8;
            int gy = top.getInt(screen) + s.y + 8;
            double scale = mc.getWindow().getGuiScale();
            mouse(mc, gx * scale, gy * scale);
            say("hover slot " + slot + " (" + s.getItem() + ") at gui " + gx + "," + gy);
        } catch (ReflectiveOperationException e) {
            fail("hover: " + e);
        }
    }

    private static void mouse(Minecraft mc, double x, double y) {
        try {
            Field fx = MouseHandler.class.getDeclaredField("xpos");
            Field fy = MouseHandler.class.getDeclaredField("ypos");
            fx.setAccessible(true);
            fy.setAccessible(true);
            fx.setDouble(mc.mouseHandler, x);
            fy.setDouble(mc.mouseHandler, y);
        } catch (ReflectiveOperationException e) {
            fail("mouse: " + e);
        }
    }

    private static void srv(MinecraftServer server, Consumer<ServerPlayer> action) {
        server.execute(() -> action.accept(server.getPlayerList().getPlayers().get(0)));
    }

    private static void cmd(MinecraftServer server, String command) {
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
    }

    private static void shot(Minecraft mc, String name) {
        String file = "page-" + name + SUFFIX + ".png";
        Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), 1, msg -> say("saved " + file));
    }

    private static void fail(String why) {
        failed++;
        say("FAIL " + why);
    }

    private static void say(String line) {
        System.out.println("PAGESHOT " + line);
    }

    private static void create(Minecraft mc) {
        say("creating a normal world");
        mc.options.renderDistance().set(12);
        mc.options.simulationDistance().set(6);
        String name = "pageshot" + System.currentTimeMillis() / 1000;
        LevelSettings settings = new LevelSettings(name, GameType.CREATIVE,
            new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261106L, false, false),
            provider -> provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(),
            new TitleScreen());
    }
}
