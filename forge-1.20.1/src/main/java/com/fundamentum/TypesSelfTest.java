package com.fundamentum;

import com.fundamentum.compat.BetterCombat;
import com.fundamentum.gauntlet.Gauntlets;
import com.fundamentum.spear.Spears;
import com.fundamentum.spear.ThrownSpear;
import com.fundamentum.summon.Sentries;
import com.fundamentum.summon.Summons;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.common.ForgeMod;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The in-game test of the 1.27 weapon types, for development servers started with {@code gradlew runServer -Ptypestest}
 * (which also loads the test data in {@code src/typestest}): a fake player places sentries, punches with a gauntlet and
 * uses a spear high above spawn. Every check logs {@code TYPESTEST PASS} or {@code TYPESTEST FAIL}, then
 * {@code TYPESTEST DONE n/m}, and the server stops. Better Combat is not installed in the dev run; the checks that need
 * it pretend it is through {@link BetterCombat#override}.
 */
final class TypesSelfTest {
    private static final Logger LOG = LogUtils.getLogger();
    private static final BlockPos AT = new BlockPos(40, 210, 40);
    private static final ResourceLocation SNOWBALL = new ResourceLocation("snowball");
    private static final ResourceLocation SNOW_GOLEM = new ResourceLocation("snow_golem");
    private static final List<String> RESULTS = new ArrayList<>();
    private static final AtomicInteger FINISHERS = new AtomicInteger();

    private static int start = -1;
    private static @Nullable FakePlayer player;
    private static @Nullable FakePlayer leaver;
    private static final List<Mob> PLACED = new ArrayList<>();
    private static @Nullable LivingEntity target;
    private static final List<Integer> STEPS = new ArrayList<>();
    private static final List<Float> HITS = new ArrayList<>();

    private TypesSelfTest() {}

    static void init() {
        if (!Boolean.getBoolean("fundamentum.typestest")) {
            return;
        }
        // Code half of the test gauntlet: a hook and stats the data file overrides (finisher x1 here, x2 in the data).
        Gauntlets.register(Items.BRICK, new Gauntlets.GauntletDefinition(5, 20, 0, new Gauntlets.Finisher(1.0, null, 0, 0, 0), false)
            .withHook((p, t, g, d) -> FINISHERS.incrementAndGet()));
        MinecraftForge.EVENT_BUS.addListener(TypesSelfTest::tick);
    }

    private static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        int now = server.getTickCount();
        if (start < 0) {
            if (now < 40) {
                return;
            }
            start = now;
            setUp(level);
            sentriesPlace(level);
            return;
        }
        FakePlayer p = player;
        int t = now - start;
        switch (t) {
            case 60 -> sentriesShoot(level);
            case 61 -> p.getAttribute(Sentries.SENTRY_SLOTS.get()).setBaseValue(1);
            case 63 -> report("lowering sentry_slots sends the oldest sentry away", PLACED.get(1).isRemoved() && !PLACED.get(2).isRemoved()
                && Sentries.sentries(p).equals(List.of(PLACED.get(2))), "out " + Sentries.sentries(p).size());
            case 64 -> {
                Sentries.Placed s4 = Sentries.place(p, SNOWBALL, Sentries.SentryDefinition.builder(SNOW_GOLEM).lifetime(20).build(),
                    AT.offset(3, -1, 4), Direction.UP);
                report("a new sentry at the limit replaces the oldest", s4.ok() && PLACED.get(2).isRemoved()
                    && Sentries.sentries(p).equals(List.of(s4.sentry())), "failure " + s4.failure());
                if (s4.ok()) {
                    PLACED.add(s4.sentry());
                }
            }
            case 90 -> report("a sentry goes away when its lifetime ends", PLACED.size() > 3 && PLACED.get(3).isRemoved()
                && Sentries.sentries(p).isEmpty(), "out " + Sentries.sentries(p).size());
            case 91 -> {
                leaver = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("0d6f3c1a-7b2e-4c59-9a81-3e5f2b6c7d40"), "typestest2"));
                leaver.moveTo(AT.getX() + 0.5, AT.getY(), AT.getZ() + 0.5, 0.0F, 0.0F);
                Sentries.Placed s5 = Sentries.place(leaver, SNOWBALL, Sentries.SentryDefinition.builder(SNOW_GOLEM).build(),
                    AT.offset(-6, -1, 4), Direction.UP);
                if (s5.ok()) {
                    PLACED.add(s5.sentry());
                }
                leaver.discard();
            }
            case 93 -> report("sentries go away when their owner leaves", PLACED.size() > 4 && PLACED.get(4).isRemoved()
                && Sentries.sentries(leaver).isEmpty(), "placed " + PLACED.size());
            case 100 -> gauntletSetUp(level, p);
            case 101, 104, 107, 110, 135 -> {
                HITS.add(punch(p, target));
                STEPS.add(Gauntlets.step(p));
                if (t == 107) {
                    report("the third punch is the finisher: double damage, slowness and the hook", HITS.get(2) > HITS.get(0) * 1.9F
                        && target.hasEffect(MobEffects.MOVEMENT_SLOWDOWN) && FINISHERS.get() == 1,
                        "hits " + HITS + ", slowed " + target.hasEffect(MobEffects.MOVEMENT_SLOWDOWN) + ", hooks " + FINISHERS.get());
                }
            }
            case 136 -> report("the combo counts 1 2 3, starts over after the finisher and after a pause",
                STEPS.equals(List.of(1, 2, 3, 1, 1)), "steps " + STEPS);
            case 137 -> {
                BetterCombat.override(true);
                target.removeAllEffects();
                int finishersBefore = FINISHERS.get();
                float a = punch(p, target);
                float b = punch(p, target);
                float c = punch(p, target);
                report("with Better Combat the gauntlet counts no combo of its own", Gauntlets.step(p) == 0 && FINISHERS.get() == finishersBefore
                    && !target.hasEffect(MobEffects.MOVEMENT_SLOWDOWN) && Math.abs(c - a) < 0.01F && Math.abs(b - a) < 0.01F,
                    "step " + Gauntlets.step(p) + ", hits " + a + " " + b + " " + c);
                BetterCombat.override(null);
                target.discard();
            }
            case 150 -> spears(level, p);
            case 152 -> {
                long passed = RESULTS.stream().filter(r -> r.startsWith("PASS")).count();
                LOG.info("TYPESTEST DONE {}/{}", passed, RESULTS.size());
                forceArea(level, false);
                server.halt(false);
            }
            default -> { }
        }
    }

    /** Keeps every chunk of the test floor loaded and ticking (it crosses a chunk border, where arrows would freeze). */
    private static void forceArea(ServerLevel level, boolean force) {
        for (int cx = (AT.getX() - 8) >> 4; cx <= (AT.getX() + 8) >> 4; cx++) {
            for (int cz = (AT.getZ() - 4) >> 4; cz <= (AT.getZ() + 12) >> 4; cz++) {
                level.setChunkForced(cx, cz, force);
            }
        }
    }

    private static void setUp(ServerLevel level) {
        forceArea(level, true);
        for (int x = -8; x <= 8; x++) {
            for (int z = -4; z <= 12; z++) {
                level.setBlockAndUpdate(AT.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= 3; y++) {
                    level.setBlockAndUpdate(AT.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        // Leftovers from an earlier run of the kept dev world.
        level.getEntitiesOfClass(Mob.class, new AABB(AT).inflate(16)).forEach(Mob::discard);
        player = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("7c1e9a42-3d5b-4f60-8e27-1a9b0c4d5e6f"), "typestest"));
        player.moveTo(AT.getX() + 0.5, AT.getY(), AT.getZ() + 0.5, 0.0F, 0.0F);
        player.setYHeadRot(0.0F);
    }

    // ------------------------------------------------------------------------------------------------ sentries
    private static void sentriesPlace(ServerLevel level) {
        FakePlayer p = player;
        Sentries.SentryDefinition def = Sentries.def(new ItemStack(Items.SNOWBALL));
        report("the sentry data file loads", def != null && def.entity().equals(SNOW_GOLEM) && def.damage() == 3.0
            && new ResourceLocation("arrow").equals(def.projectile()), "def " + def);
        if (def == null) {
            def = Sentries.SentryDefinition.builder(SNOW_GOLEM).projectile(new ResourceLocation("arrow")).build();
        }
        p.getAttribute(Sentries.SENTRY_SLOTS.get()).setBaseValue(2);
        for (int x : new int[] {-6, -3, 0}) {
            Sentries.Placed placed = Sentries.place(p, SNOWBALL, def, AT.offset(x, -1, 4), Direction.UP);
            PLACED.add(placed.sentry());
            if (!placed.ok()) {
                report("a sentry is placed on the ground", false, "failure " + placed.failure());
                return;
            }
        }
        Mob s2 = PLACED.get(1);
        report("a third sentry over 2 slots sends the oldest away", PLACED.get(0).isRemoved() && !s2.isRemoved() && !PLACED.get(2).isRemoved()
            && Sentries.sentries(p).equals(List.of(s2, PLACED.get(2))) && Sentries.used(p) == 2.0, "used " + Sentries.used(p));
        report("a sentry is a marked minion of its owner", Sentries.isSentry(s2) && Summons.isMinion(s2) && s2.isNoAi(), "");
        report("on the ground it stands on the block it was placed on", Math.abs(s2.getY() - AT.getY()) < 0.01
            && s2.blockPosition().equals(AT.offset(-3, 0, 4)), "at " + s2.position());
        Sentries.Placed wall = Sentries.place(p, SNOWBALL, def, AT.offset(3, -1, 4), Direction.NORTH);
        report("a ground sentry can't go on a wall", "placement".equals(wall.failure()), "failure " + wall.failure());
        Sentries.Placed big = Sentries.place(p, SNOWBALL, Sentries.SentryDefinition.builder(SNOW_GOLEM).slots(3).build(),
            AT.offset(3, -1, 4), Direction.UP);
        report("a sentry costing more slots than the limit is refused", "no_slots".equals(big.failure()), "failure " + big.failure());
        level.setBlockAndUpdate(AT.offset(6, 0, 4), Blocks.STONE.defaultBlockState());
        Sentries.Placed blocked = Sentries.place(p, SNOWBALL, def, AT.offset(6, -1, 4), Direction.UP);
        report("a sentry with no room is refused", "blocked".equals(blocked.failure()), "failure " + blocked.failure());
        report("refused placements leave the roster alone", Sentries.sentries(p).size() == 2, "out " + Sentries.sentries(p).size());
        Mob zombie = EntityType.ZOMBIE.create(level);
        zombie.moveTo(AT.getX() - 1.5, AT.getY(), AT.getZ() + 10.5, 180.0F, 0.0F);
        zombie.setNoAi(true);
        zombie.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 1000, 0, false, false));
        level.addFreshEntity(zombie);
        target = zombie;
    }

    private static void sentriesShoot(ServerLevel level) {
        LivingEntity zombie = target;
        report("a sentry shoots a hostile creature in range", zombie.getHealth() < zombie.getMaxHealth(),
            "zombie health " + zombie.getHealth() + "/" + zombie.getMaxHealth());
        zombie.discard();
        level.getEntitiesOfClass(AbstractArrow.class, new AABB(AT).inflate(16)).forEach(AbstractArrow::discard);
    }

    // ------------------------------------------------------------------------------------------------ gauntlets
    private static void gauntletSetUp(ServerLevel level, FakePlayer p) {
        Gauntlets.GauntletDefinition def = Gauntlets.def(new ItemStack(Items.BRICK));
        report("the gauntlet data file overrides the code stats and keeps the hook", def != null && def.comboLength() == 3
            && def.finisher().damageMultiplier() == 2.0 && def.hook() != null, "def " + def);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BRICK));
        Mob cow = EntityType.COW.create(level);
        cow.moveTo(AT.getX() + 0.5, AT.getY(), AT.getZ() + 2.0, 180.0F, 0.0F);
        cow.setNoAi(true);
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
        cow.setHealth(100.0F);
        level.addFreshEntity(cow);
        target = cow;
    }

    /** One fully charged punch at {@code victim}; returns the health it took. */
    private static float punch(FakePlayer p, LivingEntity victim) {
        charge(p);
        victim.invulnerableTime = 0;
        float before = victim.getHealth();
        p.attack(victim);
        return before - victim.getHealth();
    }

    /** Fills the attack strength meter (a fake player never ticks it). */
    private static void charge(FakePlayer p) {
        for (Class<?> c = p.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("attackStrengthTicker");
                f.setAccessible(true);
                f.setInt(p, 1000);
                return;
            } catch (NoSuchFieldException e) {
                // look further up
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("no attackStrengthTicker field");
    }

    // ------------------------------------------------------------------------------------------------ spears
    private static void spears(ServerLevel level, FakePlayer p) {
        // The test data puts the trident in #fundamentum:spears (this version has no vanilla spears).
        ItemStack spear = new ItemStack(Items.TRIDENT);
        p.setItemInHand(InteractionHand.MAIN_HAND, spear);
        report("a spear in #fundamentum:spears is in #fundamentum:enchantable/spear", Spears.isSpear(spear)
            && spear.is(BetterCombat.tag("enchantable/spear")), "");
        double base = p.getAttributeValue(ForgeMod.ENTITY_REACH.get());
        java.util.UUID id = java.util.UUID.fromString("9e3d1b7a-2c4f-4a8e-b6d0-5f1e7c3a9b22");
        p.getAttribute(Spears.SPEAR_REACH.get()).addTransientModifier(new AttributeModifier(id, "fundamentum:typestest", 2.0, AttributeModifier.Operation.ADDITION));
        Spears.syncReach(p);
        double reached = p.getAttributeValue(ForgeMod.ENTITY_REACH.get());
        report("spear_reach +2 adds 2 blocks to the reach while a spear is held", Math.abs(reached - base - 2.0) < 1.0E-4, base + " -> " + reached);
        BetterCombat.override(true);
        Spears.syncReach(p);
        double bcReach = p.getAttributeValue(ForgeMod.ENTITY_REACH.get());
        BetterCombat.override(null);
        report("with Better Combat spear_reach still counts once", Math.abs(bcReach - reached) < 1.0E-4, base + " -> " + bcReach);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        Spears.syncReach(p);
        double sword = p.getAttributeValue(ForgeMod.ENTITY_REACH.get());
        report("spear_reach leaves other weapons alone", Math.abs(sword - base) < 1.0E-4 && !Spears.isSpear(p.getMainHandItem()), "sword " + sword);
        p.getAttribute(Spears.SPEAR_REACH.get()).removeModifier(id);
        p.setItemInHand(InteractionHand.MAIN_HAND, spear);

        Mob cow = EntityType.COW.create(level);
        cow.moveTo(AT.getX() + 0.5, AT.getY(), AT.getZ() + 2.0, 180.0F, 0.0F);
        cow.setNoAi(true);
        cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
        cow.setHealth(100.0F);
        level.addFreshEntity(cow);
        p.getAttribute(Spears.SPEAR_CHARGE_DAMAGE.get()).setBaseValue(3.0);
        float plain = hurt(level, p, cow);
        p.startUsingItem(InteractionHand.MAIN_HAND);
        boolean using = Spears.charging(p);
        float charged = hurt(level, p, cow);
        p.stopUsingItem();
        report("spear_charge_damage x3 triples the held charge's hits only", using && Math.abs(plain - 2.0F) < 0.01F
            && Math.abs(charged - 6.0F) < 0.01F, "charging " + using + ", plain " + plain + ", charged " + charged);
        p.getAttribute(Spears.SPEAR_CHARGE_DAMAGE.get()).setBaseValue(1.0);
        cow.discard();

        // Enchantability from the 1.21-style enchantable tags: a spear is in #minecraft:enchantable/weapon and durability.
        boolean anvil = Enchantments.SMITE.canEnchant(spear) && Enchantments.BANE_OF_ARTHROPODS.canEnchant(spear)
            && Enchantments.UNBREAKING.canEnchant(spear);
        boolean notOthers = !Enchantments.SHARPNESS.canEnchant(spear) && !Enchantments.KNOCKBACK.canEnchant(spear)
            && !Enchantments.SMITE.canApplyAtEnchantingTable(spear);
        report("a spear takes Smite, Bane and Unbreaking by book, not Sharpness or Knockback, and no Smite at the table",
            anvil && notOthers, "anvil " + anvil + ", others refused " + notOthers);
        report("vanilla enchanting stays as it was", Enchantments.SHARPNESS.canEnchant(new ItemStack(Items.IRON_SWORD))
            && Enchantments.SHARPNESS.canEnchant(new ItemStack(Items.IRON_AXE)) && !Enchantments.SMITE.canEnchant(new ItemStack(Items.BOW)), "");

        Spears.SpearThrow def = Spears.throwOf(spear);
        report("the spear throw data file loads", def != null && def.damage() == 8.0 && def.chargeTicks() == 10 && def.sneak(), "def " + def);
        if (def != null) {
            ThrownSpear thrown = Spears.throwSpear(p, spear, def, 20);
            report("a drawn spear is thrown as a spear entity and leaves the hand", thrown != null && thrown.isAlive()
                && thrown.getOwner() == p && thrown.getItem().is(Items.TRIDENT) && spear.isEmpty(),
                thrown == null ? "none" : "damage " + thrown.spearDamage() + ", hand " + spear.getCount());
            if (thrown != null) {
                thrown.discard();
            }
        }
    }

    private static float hurt(ServerLevel level, FakePlayer p, LivingEntity victim) {
        victim.invulnerableTime = 0;
        float before = victim.getHealth();
        victim.hurt(p.damageSources().playerAttack(p), 2.0F);
        return before - victim.getHealth();
    }

    private static void report(String name, boolean ok, String detail) {
        String line = (ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : " (" + detail + ")");
        RESULTS.add(line);
        LOG.info("TYPESTEST {}", line);
    }
}
