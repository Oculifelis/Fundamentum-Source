package com.fundamentum.shield;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.entity.player.Player;
import com.fundamentum.thrown.ThrownWeapon;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.slf4j.Logger;

/**
 * The in-game shield test, for development servers started with {@code gradlew runServer -Pshieldtest}, which also loads
 * the example shields' data in {@code src/shieldtest}. On a glass floor high above spawn, still husks raise the example
 * shields: the stats from data, a banner on a shield, a sneak-use throw, a block from the front and a hit from behind, a parry (staggered attacker), a
 * late raise and a re-raise that do not parry, a parried arrow flying back at its shooter, a bash (cone, knockback,
 * cooldown) and fire resistance while raised. Then it stops the server. Log lines start with SHIELDTEST.
 */
public final class ShieldSelfTest {
    private ShieldSelfTest() { }

    private static final Logger LOG = LogUtils.getLogger();
    private static final BlockPos BASE = new BlockPos(0, 240, -40);
    private static final String TAG = "fundamentum_shieldtest";
    private static final List<String> RESULTS = new ArrayList<>();
    private static int start = -1;
    private static LivingEntity blocker, front, back, parrier, parried, late, lateAttacker, rearmer, rearmAttacker,
        archer, shooter, basher, bashTarget, bashBehind, ember, emberControl, emberAttacker;
    private static Arrow arrow;
    private static float backHealth;
    private static boolean secondBash;
    private static FakePlayer thrower;
    private static ThrownWeapon thrownShield;

    public static void init() {
        if (!Boolean.getBoolean("fundamentum.shieldtest")) return;
        MinecraftForge.EVENT_BUS.addListener(ShieldSelfTest::tick);
    }

    private static ItemStack stack(String name) {
        return new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation("fundamentum", name)));
    }

    private static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        int now = server.getTickCount();
        if (start < 0) {
            if (now < 40) return;
            start = now;
            // headless texture check: every example shield's item model is found and both its pictures ship
            ShieldTextures.Result textures = ShieldTextures.check();
            List<String> wanted = List.of("fundamentum:example_buckler", "fundamentum:example_tower_shield",
                "fundamentum:example_ember_shield", "fundamentum:example_round_shield");
            report("textures", textures.missing().isEmpty() && textures.checked().containsAll(wanted),
                "checked " + textures.checked() + ", missing " + textures.missing());
            force(level, true);
            for (int x = -4; x < 14; x++) {
                for (int z = -4; z < 50; z++) {
                    level.setBlock(BASE.offset(x, -1, z), Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            return;
        }
        int t = now - start;
        if (t == 20) {
            level.getEntitiesOfClass(Entity.class, new AABB(BASE).inflate(64),
                e -> e.getTags().contains(TAG) || e instanceof Arrow || e instanceof ThrownWeapon).forEach(Entity::discard);
            blocker = holding(level, 0, 0, "example_tower_shield");
            front = spawn(level, 2, 0);
            back = spawn(level, -2, 0);
            parrier = holding(level, 0, 6, "example_buckler");
            parried = spawn(level, 2, 6);
            late = holding(level, 0, 12, "example_buckler");
            lateAttacker = spawn(level, 2, 12);
            rearmer = holding(level, 0, 18, "example_buckler");
            rearmAttacker = spawn(level, 2, 18);
            archer = holding(level, 0, 24, "example_buckler");
            shooter = spawn(level, 9, 24);
            basher = holding(level, 0, 30, "example_tower_shield");
            bashTarget = spawn(level, 2, 30);
            bashBehind = spawn(level, -2, 30);
            ember = holding(level, 0, 36, "example_ember_shield");
            emberControl = holding(level, 0, 42, "example_ember_shield");
            emberAttacker = spawn(level, 2, 39);
            throwRound(level);
        } else if (t == 21) {
            checkStamps(level);
            blocker.startUsingItem(InteractionHand.MAIN_HAND);
            late.startUsingItem(InteractionHand.MAIN_HAND);
            ember.startUsingItem(InteractionHand.MAIN_HAND);
        } else if (t == 46) {
            rearmer.startUsingItem(InteractionHand.MAIN_HAND);
        } else if (t == 48) {
            rearmer.stopUsingItem();
        } else if (t == 50) {
            parrier.startUsingItem(InteractionHand.MAIN_HAND);
            rearmer.startUsingItem(InteractionHand.MAIN_HAND);
            archer.startUsingItem(InteractionHand.MAIN_HAND);
            secondBash = Shields.bash(basher, InteractionHand.MAIN_HAND);
        } else if (t == 51) {
            report("a bash cools down (the first went off, the second at once does not)",
                secondBash && !Shields.bash(basher, InteractionHand.MAIN_HAND), "");
        } else if (t == 53) {
            arrow = new Arrow(level, shooter.getX() - 0.6, shooter.getEyeY() - 0.3, shooter.getZ());
            arrow.setOwner(shooter);
            arrow.shoot(-1, 0, 0, 2.5F, 0);
            level.addFreshEntity(arrow);
        } else if (t == 54) {
            hit(level, parrier, parried, 6);
            hit(level, rearmer, rearmAttacker, 6);
        } else if (t == 60) {
            hit(level, blocker, front, 6);
            hit(level, late, lateAttacker, 6);
            DamageSource fire = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation("fundamentum", "fire"))), emberAttacker);
            ember.invulnerableTime = 0;
            emberControl.invulnerableTime = 0;
            ember.hurt(fire, 8);
            emberControl.hurt(fire, 8);
        } else if (t == 62) {
            blocker.invulnerableTime = 0;
            backHealth = blocker.getHealth();
            hit(level, blocker, back, 6);
        } else if (t == 70) {
            report("block: a hit from the front does nothing", backHealth == blocker.getMaxHealth(),
                backHealth + "");
            report("block: a hit from behind gets through", blocker.getHealth() < backHealth, blocker.getHealth() + "");
            report("block: no parry on a shield with parry off", !front.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "");
            report("parry: blocked in full", !hurt(parrier), parrier.getHealth() + "");
            report("parry: the attacker is staggered", parried.hasEffect(MobEffects.MOVEMENT_SLOWDOWN) && parried.hasEffect(MobEffects.WEAKNESS), "");
            report("late raise: blocked but no parry", !hurt(late) && !lateAttacker.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), late.getHealth() + "");
            report("quick re-raise: blocked but no parry (rearm)", !hurt(rearmer) && !rearmAttacker.hasEffect(MobEffects.MOVEMENT_SLOWDOWN),
                rearmer.getHealth() + "");
            report("arrow parry: the blocker is unhurt", !hurt(archer), archer.getHealth() + "");
            report("arrow parry: the arrow is the blocker's now and flew back into its shooter",
                arrow.getOwner() == archer && hurt(shooter), "owner " + (arrow.getOwner() == null ? "none" : arrow.getOwner().getStringUUID())
                    + ", shooter " + shooter.getHealth());
            report("bash: the creature in front is hit and staggered", hurt(bashTarget) && bashTarget.hasEffect(MobEffects.MOVEMENT_SLOWDOWN),
                bashTarget.getHealth() + "");
            report("bash: the creature behind is not", !hurt(bashBehind), bashBehind.getHealth() + "");
            float raised = ember.getMaxHealth() - ember.getHealth();
            float control = emberControl.getMaxHealth() - emberControl.getHealth();
            report("element resistance: fire on a raised ember shield is halved", control > 0 && Math.abs(raised * 2 - control) < 0.6,
                raised + " vs " + control);
            report("throw: the thrown round shield is still out, flying back or caught",
                thrownShield != null && (thrownShield.isAlive() || thrower.getMainHandItem().is(stack("example_round_shield").getItem())),
                thrownShield == null ? "none" : thrownShield.position() + " " + thrownShield.isAlive());
            long passed = RESULTS.stream().filter(r -> r.startsWith("PASS")).count();
            LOG.info("SHIELDTEST DONE {}/{} failed={}", passed, RESULTS.size(), RESULTS.size() - passed);
            level.getEntitiesOfClass(Entity.class, new AABB(BASE).inflate(64),
                e -> e.getTags().contains(TAG) || e instanceof Arrow || e instanceof ThrownWeapon).forEach(Entity::discard);
            force(level, false);
            server.halt(false);
        }
    }

    private static void checkStamps(ServerLevel level) {
        // 1.21.1 has no blocks_attacks or use_effects components: the stats are read straight from the data
        ShieldStats tower = Shields.stats(blocker.getMainHandItem());
        report("data: the tower shield's blocking stats come from its data", tower != null
                && Math.abs(tower.blockDelaySeconds() - 0.4) < 1e-4 && Math.abs(tower.disableCooldownScale() - 0.5) < 1e-4
                && tower.damageReductions().size() == 1 && tower.damageReductions().get(0).horizontalBlockingAngle() == 130,
            String.valueOf(tower));
        report("data: the tower shield slows to 0.1 while raised", tower != null && Math.abs(tower.speedWhileBlocking() - 0.1) < 1e-4,
            String.valueOf(tower == null ? null : tower.speedWhileBlocking()));
        ShieldStats buckler = Shields.stats(parrier.getMainHandItem());
        report("data: the buckler slows to 0.5 only", buckler != null && Math.abs(buckler.speedWhileBlocking() - 0.5) < 1e-4,
            String.valueOf(buckler == null ? null : buckler.speedWhileBlocking()));
        ShieldStats round = Shields.stats(stack("example_round_shield"));
        report("the round shield is throwable, with its own range",
            round != null && round.throwable() && Shields.thrownStats(round, null).damage() == 5
                && Shields.thrownStats(round, null).returns() != null, String.valueOf(round == null ? null : round.thrown()));
        report("vanilla's shield is left alone without data", Shields.stats(new ItemStack(Items.SHIELD)) == null, "");
        TransientCraftingContainer grid = new TransientCraftingContainer(new AbstractContainerMenu((MenuType<?>) null, -1) {
            @Override
            public ItemStack quickMoveStack(Player player, int slot) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean stillValid(Player player) {
                return true;
            }
        }, 2, 1);
        grid.setItem(0, stack("example_buckler"));
        grid.setItem(1, new ItemStack(Items.RED_BANNER));
        ItemStack decorated = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level)
            .map(r -> r.assemble(grid, level.registryAccess())).orElse(ItemStack.EMPTY);
        report("banner: a banner in the crafting grid decorates a Fundamentum shield",
            decorated.is(stack("example_buckler").getItem()) && ShieldItem.getColor(decorated) == DyeColor.RED, decorated + " " + decorated.getTag());
    }

    /** Sneak-use of a throwable shield: a fake player throws the round shield as a returning thrown weapon. */
    private static void throwRound(ServerLevel level) {
        thrower = FakePlayerFactory.getMinecraft(level);
        thrower.moveTo(BASE.getX() + 8.5, BASE.getY(), BASE.getZ() + 46.5, -90.0F, 0.0F);
        thrower.setItemInHand(InteractionHand.MAIN_HAND, stack("example_round_shield"));
        ShieldStats round = Shields.stats(thrower.getMainHandItem());
        if (round == null) {
            report("throw: the round shield has stats", false, "");
            return;
        }
        Shields.throwShield(level, thrower, InteractionHand.MAIN_HAND, round);
        List<ThrownWeapon> out = level.getEntitiesOfClass(ThrownWeapon.class, new AABB(BASE).inflate(64), w -> w.getOwner() == thrower);
        thrownShield = out.isEmpty() ? null : out.get(0);
        report("throw: sneak-use throws the shield (it leaves the hand and flies as a thrown weapon)",
            thrownShield != null && thrower.getMainHandItem().isEmpty() && thrownShield.getItem().is(stack("example_round_shield").getItem()),
            out.size() + " thrown, hand " + thrower.getMainHandItem());
    }

    private static void hit(ServerLevel level, LivingEntity target, LivingEntity attacker, float amount) {
        target.invulnerableTime = 0;
        target.hurt(level.damageSources().mobAttack(attacker), amount);
    }

    private static boolean hurt(LivingEntity e) {
        return e.getHealth() < e.getMaxHealth();
    }

    private static void force(ServerLevel level, boolean on) {
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -3; cz <= 1; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    private static LivingEntity holding(ServerLevel level, int x, int z, String shield) {
        LivingEntity e = spawn(level, x, z);
        ItemStack stack = stack(shield);
        e.setItemSlot(EquipmentSlot.MAINHAND, stack);
        return e;
    }

    private static LivingEntity spawn(ServerLevel level, int x, int z) {
        LivingEntity e = EntityType.HUSK.create(level);
        float yaw = x > 0 ? 90.0F : -90.0F; // the lone husks at x 0 look +X; the others face them
        e.moveTo(BASE.getX() + x + 0.5, BASE.getY(), BASE.getZ() + z + 0.5, yaw, 0.0F);
        e.setYHeadRot(yaw);
        e.addTag(TAG);
        if (e instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
            mob.setDropChance(EquipmentSlot.MAINHAND, 0);
        }
        e.setHealth(e.getMaxHealth());
        level.addFreshEntity(e);
        return e;
    }

    private static void report(String name, boolean ok, String detail) {
        String line = (ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : " (" + detail + ")");
        RESULTS.add(line);
        LOG.info("SHIELDTEST {}", line);
    }
}
