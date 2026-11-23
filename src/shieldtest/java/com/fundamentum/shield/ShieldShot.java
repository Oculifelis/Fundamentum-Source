package com.fundamentum.shield;

import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.Item;
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
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Dev client only: {@code gradlew runClient -Pshieldshot -Pshieldtest}. Muted and off screen, in a fresh flat world: the
 * example shields on armour stands beside the vanilla shield, a tower shield raised in first person, then from the side a
 * zombie hitting the raised tower shield (block), a buckler raised just in time (parry, melee and an arrow) and a buckler
 * bash on sneak. Pictures go to run/screenshots/shield-*.png; log lines start with SHIELDSHOT.
 */
@EventBusSubscriber(modid = "fundamentum", value = Dist.CLIENT)
public final class ShieldShot {
    static final boolean ON = Boolean.getBoolean("fundamentum.shieldshot");

    private static boolean hidden;
    private static boolean started;
    private static int ticks;
    private static boolean raise;
    private static boolean sneak;
    private static String burstName;
    private static int burstFrom;
    private static int burstTo;
    private static int burstEvery;
    private static int burstCount;

    // server side, touched only on the server thread
    private static Zombie zombie;
    private static ArmorStand camera;
    private static final java.util.List<ArmorStand> lineup = new java.util.ArrayList<>();
    private static boolean parryPending;
    private static boolean arrowPending;
    private static Arrow arrow;
    private static double zombieX;
    private static final float LINEUP_PITCH = 25.0F;

    private ShieldShot() {}

    /** Logs every raise the server sees, so a parry miss shows whether the raise started twice (which disarms the window). */
    @SubscribeEvent
    public static void useStart(LivingEntityUseItemEvent.Start event) {
        if (ON && event.getEntity() instanceof ServerPlayer p) {
            say("raise starts at game time " + p.level().getGameTime() + " with " + event.getItem().getItem());
        }
    }

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
            hideHud(mc, true);
        }
        if (mc.level == null || mc.player == null) {
            if (!started && mc.gui.screen() instanceof TitleScreen) {
                started = true;
                create(mc);
            }
            return;
        }
        if (mc.gui.screen() != null) {
            mc.gui.setScreen(null);
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        ticks++;
        // the use and sneak keys are held by this script, every tick, so an unfocused window never drops them
        mc.options.keyUse.setDown(raise);
        mc.options.keyShift.setDown(sneak);
        if (burstName != null && ticks >= burstFrom && ticks <= burstTo && (ticks - burstFrom) % burstEvery == 0) {
            grab(mc, String.format("%s-%02d", burstName, burstCount++));
        }
        if (raise) {
            server.execute(() -> serverTick(server));
        }
        switch (ticks) {
            case 100 -> {
                checkTextures();
                server.execute(() -> setUp(server));
                mc.player.setXRot(LINEUP_PITCH);
            }
            case 150 -> grab(mc, "shield-lineup");
            case 152 -> {
                server.execute(() -> {
                    lineup.forEach(Entity::discard);
                    spawnZombie(server);
                });
                mc.player.setXRot(0.0F);
                raise(mc);
            }
            case 185 -> {
                say("first person: using " + mc.player.isUsingItem() + ", blocking " + mc.player.isBlocking());
                grab(mc, "shield-firstperson-block");
            }
            case 190 -> {
                Entity cam = find(mc, "shieldcam");
                say("side camera " + (cam != null));
                if (cam != null) {
                    mc.setCameraEntity(cam);
                }
            }
            case 210 -> {
                grab(mc, "shield-block-raised");
                server.execute(() -> {
                    ServerPlayer p = player(server);
                    float before = p.getHealth();
                    zombie.swing(InteractionHand.MAIN_HAND, true);
                    p.hurtServer(p.level(), p.level().damageSources().mobAttack(zombie), 6.0F);
                    say("block: tower shield blocking " + p.isBlocking() + ", health " + before + " -> " + p.getHealth());
                });
                burst("shield-block", 211, 225, 2);
            }
            case 230 -> lower();
            case 232 -> server.execute(() -> {
                give(server, ShieldExamples.EXAMPLE_BUCKLER.get());
                resetZombie();
            });
            case 260 -> {
                server.execute(() -> parryPending = true);
                raise(mc);
                burst("shield-parry", 261, 291, 2);
            }
            case 300 -> {
                server.execute(() -> say("parry: zombie staggered " + zombie.hasEffect(MobEffects.SLOWNESS)
                    + ", pushed back " + String.format("%.2f", zombie.getX() - zombieX) + " blocks"));
                lower();
            }
            case 302 -> server.execute(ShieldShot::resetZombie);
            case 330 -> {
                server.execute(() -> arrowPending = true);
                raise(mc);
                burst("shield-arrow", 331, 346, 1);
            }
            case 352 -> {
                server.execute(() -> {
                    if (arrow == null) {
                        say("arrow: never fired");
                        return;
                    }
                    say("arrow: owner now " + (arrow.getOwner() == null ? "none" : arrow.getOwner().getName().getString())
                        + ", moving " + (arrow.getDeltaMovement().x > 0 ? "away from the player" : "towards the player")
                        + ", removed " + arrow.isRemoved());
                    arrow.discard();
                });
                lower();
            }
            case 354 -> server.execute(ShieldShot::resetZombie);
            case 380 -> raise(mc);
            case 412 -> {
                grab(mc, "shield-bash-raised");
                sneak = true;
                burst("shield-bash", 413, 433, 2);
            }
            case 440 -> {
                sneak = false;
                server.execute(() -> say("bash on sneak: zombie staggered " + zombie.hasEffect(MobEffects.SLOWNESS)
                    + ", health " + zombie.getHealth() + ", pushed back " + String.format("%.2f", zombie.getX() - zombieX) + " blocks"));
                lower();
            }
            case 470 -> {
                say("DONE");
                mc.stop();
            }
            default -> {}
        }
    }

    private static void serverTick(MinecraftServer server) {
        ServerPlayer p = player(server);
        if (parryPending && p.isUsingItem() && p.getTicksUsingItem() >= 4) {
            parryPending = false;
            zombie.swing(InteractionHand.MAIN_HAND, true);
            boolean timed = Shields.parryTiming(p, Shields.stats(p.getUseItem()));
            p.hurtServer(p.level(), p.level().damageSources().mobAttack(zombie), 6.0F);
            say("parry: hit at " + p.getTicksUsingItem() + " ticks raised (game time " + p.level().getGameTime() + "), in the window " + timed + ", health " + p.getHealth());
        }
        if (arrowPending && p.isUsingItem() && p.getTicksUsingItem() >= 1) {
            arrowPending = false;
            ServerLevel level = p.level();
            // from the front and a little to the side, so it passes the zombie
            arrow = new Arrow(level, p.getX() + 6, p.getEyeY() - 0.3, p.getZ() - 2.5, new ItemStack(Items.ARROW), null);
            arrow.shoot(-6, 0.25, 2.5, 1.6F, 0.0F);
            level.addFreshEntity(arrow);
            say("arrow: fired at " + p.getTicksUsingItem() + " ticks raised");
        }
    }

    private static void raise(Minecraft mc) {
        // a real right-click: one press, then the key held (calling useItem here as well raised the shield twice)
        KeyMapping.click(mc.options.keyUse.getKey());
        raise = true;
    }

    private static void lower() {
        raise = false;
    }

    private static void burst(String name, int from, int to, int every) {
        burstName = name;
        burstFrom = from;
        burstTo = to;
        burstEvery = every;
        burstCount = 0;
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static void give(MinecraftServer server, Item item) {
        ServerPlayer p = player(server);
        ItemStack stack = new ItemStack(item);
        Shields.stamp(stack, p.level());
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
    }

    private static void resetZombie() {
        zombie.removeAllEffects();
        zombie.setHealth(zombie.getMaxHealth());
        zombie.snapTo(zombieX, zombie.getY(), zombie.getZ(), 90.0F, 0.0F);
        zombie.setYHeadRot(90.0F);
        zombie.setYBodyRot(90.0F);
        zombie.setDeltaMovement(0, 0, 0);
        zombie.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 20000, 0, false, false));
        zombie.clearFire();
    }

    private static void setUp(MinecraftServer server) {
        ServerPlayer p = player(server);
        ServerLevel level = p.level();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20000, 4, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20000, 1, false, false));
        BlockPos base = p.blockPosition();
        double x = base.getX() + 0.5;
        double y = base.getY();
        double z = base.getZ() + 0.5;
        p.snapTo(x, y, z, -90.0F, 0.0F);
        Minecraft.getInstance().player.setYRot(-90.0F);
        Minecraft.getInstance().player.setXRot(LINEUP_PITCH);
        give(server, ShieldExamples.EXAMPLE_TOWER_SHIELD.get());

        // the lineup: the vanilla shield, then the four examples, held up by armour stands facing the player, close and
        // looked down on so the shields fill the frame; no creature is out yet
        Item[] shields = {Items.SHIELD, ShieldExamples.EXAMPLE_BUCKLER.get(), ShieldExamples.EXAMPLE_TOWER_SHIELD.get(),
            ShieldExamples.EXAMPLE_EMBER_SHIELD.get(), ShieldExamples.EXAMPLE_ROUND_SHIELD.get()};
        for (int i = 0; i < shields.length; i++) {
            ArmorStand stand = new ArmorStand(level, x + 2.0, y, z + 1.7 - i * 0.85);
            stand.setShowArms(true);
            stand.setNoBasePlate(true);
            stand.snapTo(stand.getX(), y, stand.getZ(), 90.0F, 0.0F);
            stand.setYHeadRot(90.0F);
            stand.setYBodyRot(90.0F);
            ItemStack held = new ItemStack(shields[i]);
            Shields.stamp(held, level);
            stand.setItemSlot(EquipmentSlot.OFFHAND, held);
            level.addFreshEntity(stand);
            lineup.add(stand);
        }

        camera = new ArmorStand(level, x + 0.85, y + 0.1, z + 3.6);
        camera.setInvisible(true);
        camera.setNoGravity(true);
        camera.setNoBasePlate(true);
        camera.snapTo(camera.getX(), camera.getY(), camera.getZ(), 180.0F, 8.0F);
        camera.setYHeadRot(180.0F);
        camera.setYBodyRot(180.0F);
        camera.setCustomName(Component.literal("shieldcam"));
        camera.setCustomNameVisible(false);
        level.addFreshEntity(camera);
        say("set up at " + base.toShortString());
    }

    /** The test zombie, after the lineup shot; an iron helmet keeps it from burning in the noon sun. */
    private static void spawnZombie(MinecraftServer server) {
        ServerPlayer p = player(server);
        ServerLevel level = p.level();
        zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        zombieX = p.getX() + 1.7;
        zombie.snapTo(zombieX, p.getY(), p.getZ(), 90.0F, 0.0F);
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        zombie.setDropChance(EquipmentSlot.HEAD, 0.0F);
        zombie.goalSelector.removeAllGoals(g -> true);
        zombie.targetSelector.removeAllGoals(g -> true);
        zombie.setPersistenceRequired();
        zombie.setCustomName(Component.literal("shieldzombie"));
        zombie.setCustomNameVisible(false);
        level.addFreshEntity(zombie);
        resetZombie();
    }

    /** Fails the run when an example shield's item model did not load or a texture is missing from the shield atlas. */
    private static void checkTextures() {
        List<Identifier> wanted = List.of(Identifier.fromNamespaceAndPath("fundamentum", "example_buckler"),
            Identifier.fromNamespaceAndPath("fundamentum", "example_tower_shield"),
            Identifier.fromNamespaceAndPath("fundamentum", "example_ember_shield"),
            Identifier.fromNamespaceAndPath("fundamentum", "example_round_shield"));
        List<Identifier> unloaded = wanted.stream().filter(t -> !ShieldsClient.BAKED.contains(t)).toList();
        if (unloaded.isEmpty() && ShieldsClient.MISSING.isEmpty()) {
            say("CHECK textures: all " + wanted.size() + " example shields load with both pictures in the shield atlas");
        } else {
            say("CHECK FAIL textures: item model not loaded " + unloaded + ", picture missing " + ShieldsClient.MISSING);
        }
    }

    private static Entity find(Minecraft mc, String name) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getCustomName() != null && e.getCustomName().getString().equals(name)) {
                return e;
            }
        }
        return null;
    }

    private static void grab(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1, msg -> say("saved " + name + ".png"));
    }

    /** options.hideGui is gone on 26.2+: the HUD is hidden with gui.hud.toggle(). */
    private static void hideHud(Minecraft mc, boolean hidden) {
        if (mc.gui.hud.isHidden() != hidden) {
            mc.gui.hud.toggle();
        }
    }

    private static void say(String line) {
        System.out.println("SHIELDSHOT " + line);
    }

    private static void create(Minecraft mc) {
        say("creating a flat world");
        mc.options.renderDistance().set(6);
        mc.options.simulationDistance().set(5);
        String name = "shieldshot" + System.currentTimeMillis() / 1000;
        LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL,
            new LevelSettings.DifficultySettings(Difficulty.EASY, false, false), true, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(20261109L, false, false),
            provider -> provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
            new TitleScreen());
    }
}
