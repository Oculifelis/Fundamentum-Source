package com.fundamentum.thrown;

import com.fundamentum.damage.DamageClasses;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * The in-game thrown weapon test, for development servers started with {@code gradlew runServer -Pthrowntest}, which
 * also loads the test data in {@code src/throwntest} (stats for the example boomerang). On a glass floor high above
 * spawn, still husks throw the example boomerang and knife down lanes of still husks: it checks the data and attribute
 * stats, the tags, the thrower class, pierce, the turn home, the catch, ricochet, the knife's stop, fall and stick, a
 * breaking throw and the enchantments, then stops the server. Log lines start with THROWNTEST.
 */
public final class ThrownSelfTest {
    private ThrownSelfTest() { }

    private static final Logger LOG = LogUtils.getLogger();
    private static final BlockPos BASE = new BlockPos(0, 240, -40);
    private static final String TAG = "fundamentum_throwntest";
    private static final List<String> RESULTS = new ArrayList<>();
    private static int start = -1;
    private static LivingEntity thrower, thrower2, thrower3, thrower4, holder, near, mid, far, huskA, huskB, knifeTarget, knifeBehind, bottleTarget;
    private static ThrownWeapon first, second, knife, bottle;
    private static boolean firstTurnedEarly;

    public static void init() {
        if (!Boolean.getBoolean("fundamentum.throwntest")) return;
        NeoForge.EVENT_BUS.addListener(ThrownSelfTest::tick);
    }

    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        int now = server.getTickCount();
        if (start < 0) {
            if (now < 40) return;
            start = now;
            force(level, true);
            for (int x = -4; x < 24; x++) {
                for (int z = -4; z < 32; z++) {
                    level.setBlock(BASE.offset(x, -1, z), Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            return;
        }
        if (now == start + 20) {
            level.getEntitiesOfClass(Entity.class, new AABB(BASE).inflate(48),
                e -> e.entityTags().contains(TAG) || e instanceof ThrownWeapon || e instanceof ItemEntity).forEach(Entity::discard);
            thrower = spawn(level, 0, 0);
            near = spawn(level, 4, 0);
            mid = spawn(level, 7, 0);
            far = spawn(level, 10, 0);
            holder = spawn(level, 0, 6);
            thrower2 = spawn(level, 0, 12);
            huskA = spawn(level, 4, 12);
            huskB = spawn(level, 8, 16);
            thrower3 = spawn(level, 0, 22);
            knifeTarget = spawn(level, 4, 22);
            knifeBehind = spawn(level, 8, 22);
            thrower4 = spawn(level, 0, 28);
            bottleTarget = spawn(level, 4, 28);
            return;
        }
        if (now == start + 30) {
            ItemStack boomerang = new ItemStack(ThrownWeapons.EXAMPLE_BOOMERANG.get());
            ItemStack knifeStack = new ItemStack(ThrownWeapons.EXAMPLE_KNIFE.get());
            ThrownStats base = ThrownWeapons.base(Identifier.fromNamespaceAndPath("fundamentum", "example_boomerang"));
            report("data stats lay over the code stats", base.returning() && near(base.returns().range(), 12) && base.pierce() == 1
                && near(base.damage(), 3) && near(base.throwSpeed(), ThrownStats.BOOMERANG.throwSpeed()), base.toString());
            ThrownStats knifeBase = ThrownWeapons.stats(knifeStack, null);
            report("the knife is a consumable throw that does not return", knifeBase.consumable() && !knifeBase.returning(), knifeBase.toString());
            report("tags: the boomerang is thrown, a boomerang, and enchantable as both", boomerang.is(ThrownWeapons.THROWN)
                && boomerang.is(ThrownWeapons.BOOMERANGS) && boomerang.is(ThrownWeapons.ENCHANTABLE_THROWN) && boomerang.is(ThrownWeapons.ENCHANTABLE_BOOMERANG), "");
            report("tags: the knife is thrown but no boomerang", knifeStack.is(ThrownWeapons.THROWN) && knifeStack.is(ThrownWeapons.ENCHANTABLE_THROWN)
                && !knifeStack.is(ThrownWeapons.BOOMERANGS) && !knifeStack.is(ThrownWeapons.ENCHANTABLE_BOOMERANG), "");
            report("a stick is not thrown", !ThrownWeapons.is(new ItemStack(Items.STICK)), "");
            thrower.getAttribute(ThrownWeapons.RANGE).setBaseValue(1.5);
            report("boomerang_range 1.5 makes it fly 18", near(ThrownWeapons.stats(boomerang, thrower).returns().range(), 18),
                "" + ThrownWeapons.stats(boomerang, thrower).returns().range());
            thrower.getAttribute(ThrownWeapons.RANGE).setBaseValue(1);

            first = launch(level, thrower, boomerang, ThrownWeapons.stats(boomerang, thrower));
            DamageSource source = level.damageSources().thrown(first, thrower);
            report("thrower class: the thrown hit is a thrower hit, not melee or ranger", DamageClasses.is(source, DamageClasses.THROWER)
                && !DamageClasses.is(source, DamageClasses.MELEE) && !DamageClasses.is(source, DamageClasses.RANGER), "" + DamageClasses.of(source));
            report("thrower class: a snowball's damage type is thrower too",
                DamageClasses.is(level.damageSources().thrown(thrower, thrower), DamageClasses.THROWER), "");

            thrower2.getAttribute(ThrownWeapons.BOUNCES).setBaseValue(1);
            ThrownStats s2 = ThrownWeapons.stats(boomerang, thrower2);
            report("boomerang_bounces adds a ricochet", s2.returns().bounces() == 1, s2.toString());
            second = launch(level, thrower2, boomerang, s2.withJson(json("pierce", 0)));

            thrower3.getAttribute(DamageClasses.THROWER.damage()).setBaseValue(2);
            knife = launch(level, thrower3, knifeStack, ThrownWeapons.stats(knifeStack, thrower3));
            bottle = launch(level, thrower4, knifeStack, knifeBase.withJson(json("breaks_on_hit", true)));
            // A running thrower must not add their own speed to the throw.
            net.minecraft.world.phys.Vec3 run = thrower4.getDeltaMovement();
            thrower4.setDeltaMovement(3, 0, 0);
            ThrownWeapon probe = new ThrownWeapon(ThrownWeapons.THROWN_WEAPON.get(), level);
            probe.launch(thrower4, knifeStack.copy(), knifeBase, ThrownWeapons.NO_SLOT);
            thrower4.setDeltaMovement(run);
            net.minecraft.world.phys.Vec3 expect = thrower4.getViewVector(1.0F).normalize().scale(knifeBase.throwSpeed());
            report("a running thrower's own speed does not carry into the throw", probe.getDeltaMovement().distanceTo(expect) < 1.0E-3,
                probe.getDeltaMovement() + " vs " + expect);
            probe.discard();

            var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            boolean all = true;
            all &= enchant(enchantments.get(ThrownWeapons.HURLING).map(Holder::value).orElse(null), ThrownWeapons.HURLING, boomerang, knifeStack, true);
            all &= enchant(enchantments.get(ThrownWeapons.FARFLIGHT).map(Holder::value).orElse(null), ThrownWeapons.FARFLIGHT, boomerang, knifeStack, false);
            all &= enchant(enchantments.get(ThrownWeapons.RICOCHET).map(Holder::value).orElse(null), ThrownWeapons.RICOCHET, boomerang, knifeStack, false);
            if (all) {
                ItemStack held = boomerang.copy();
                held.enchant(enchantments.getOrThrow(ThrownWeapons.FARFLIGHT), 2);
                held.enchant(enchantments.getOrThrow(ThrownWeapons.HURLING), 1);
                holder.setItemSlot(EquipmentSlot.MAINHAND, held);
            }
            return;
        }
        if (first != null && !first.isRemoved() && first.returning() && first.flight().travelled() < 11.5 && !firstTurnedEarly) {
            firstTurnedEarly = true;
        }
        if (now == start + 36) {
            double range = holder.getAttributeValue(ThrownWeapons.RANGE);
            report("Farflight II held: boomerang_range 1.5", near(range, 1.5), "" + range);
            ItemStack held = holder.getMainHandItem();
            report("and a throw of it flies 18", near(ThrownWeapons.stats(held, holder).returns().range(), 18), "" + ThrownWeapons.stats(held, holder).returns().range());
            double speed = holder.getAttributeValue(ThrownWeapons.SPEED);
            report("Hurling I held: thrown_speed 1.15", near(speed, 1.15), "" + speed);
        }
        if (now == start + 90) {
            report("pierce 1: through the near husk, turned home at the second", firstTurnedEarly, "");
            report("the near husk was hit", hurt(near), near.getHealth() + "");
            report("the middle husk was hit", hurt(mid), mid.getHealth() + "");
            report("the far husk was not", !hurt(far), far.getHealth() + "");
            report("it came home and was caught", first.isRemoved(), "");
            report("ricochet: the husk in line was hit", hurt(huskA), huskA.getHealth() + "");
            report("and the throw flew on to the husk off the line", hurt(huskB), huskB.getHealth() + "");
            report("the ricochet came home too", second.isRemoved(), "");
            report("knife: hit its husk, doubled by thrower_damage 2", knifeTarget.getMaxHealth() - knifeTarget.getHealth() > 4,
                knifeTarget.getHealth() + "");
            report("knife: pierce 0 stopped it there", !hurt(knifeBehind), knifeBehind.getHealth() + "");
            report("knife: it fell and stuck in the floor", !knife.isRemoved() && knife.flight().stuck() && knife.isStuck(), knife.flight().phase() + "");
            report("breaking throw: hit its husk and broke", hurt(bottleTarget) && bottle.isRemoved(), bottleTarget.getHealth() + "");
            report("nothing dropped as an item", level.getEntitiesOfClass(ItemEntity.class, new AABB(BASE).inflate(48)).isEmpty(), "");
            long passed = RESULTS.stream().filter(r -> r.startsWith("PASS")).count();
            LOG.info("THROWNTEST DONE {}/{} failed={}", passed, RESULTS.size(), RESULTS.size() - passed);
            level.getEntitiesOfClass(Entity.class, new AABB(BASE).inflate(48),
                e -> e.entityTags().contains(TAG) || e instanceof ThrownWeapon).forEach(Entity::discard);
            force(level, false);
            server.halt(false);
        }
    }

    private static ThrownWeapon launch(ServerLevel level, LivingEntity by, ItemStack stack, ThrownStats stats) {
        ThrownWeapon w = new ThrownWeapon(ThrownWeapons.THROWN_WEAPON.get(), level);
        w.launch(by, stack.copy(), stats, ThrownWeapons.NO_SLOT);
        level.addFreshEntity(w);
        return w;
    }

    private static boolean enchant(Enchantment e, ResourceKey<Enchantment> key, ItemStack boomerang, ItemStack knife, boolean onKnife) {
        boolean ok = e != null && e.isSupportedItem(boomerang) && e.isSupportedItem(knife) == onKnife
            && !e.isSupportedItem(new ItemStack(Items.STICK)) && !e.isSupportedItem(new ItemStack(Items.DIAMOND_SWORD));
        report("enchantment " + key.identifier() + " loads and goes on " + (onKnife ? "all thrown weapons" : "boomerangs only"), ok, "");
        return ok;
    }

    private static JsonObject json(String key, Object value) {
        JsonObject o = new JsonObject();
        if (value instanceof Boolean b) {
            o.addProperty(key, b);
        } else {
            o.addProperty(key, (Number) value);
        }
        return o;
    }

    private static boolean hurt(LivingEntity e) {
        return e.getHealth() < e.getMaxHealth();
    }

    private static void force(ServerLevel level, boolean on) {
        for (int cx = -1; cx <= 2; cx++) {
            for (int cz = -3; cz <= 0; cz++) {
                level.setChunkForced(cx, cz, on);
            }
        }
    }

    private static LivingEntity spawn(ServerLevel level, int x, int z) {
        LivingEntity e = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
        e.snapTo(BASE.getX() + x + 0.5, BASE.getY(), BASE.getZ() + z + 0.5, -90.0F, 0.0F);
        e.setYHeadRot(-90.0F);
        e.addTag(TAG);
        if (e instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        e.setHealth(e.getMaxHealth());
        level.addFreshEntity(e);
        return e;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 0.01;
    }

    private static void report(String name, boolean ok, String detail) {
        String line = (ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : " (" + detail + ")");
        RESULTS.add(line);
        LOG.info("THROWNTEST {}", line);
    }
}
