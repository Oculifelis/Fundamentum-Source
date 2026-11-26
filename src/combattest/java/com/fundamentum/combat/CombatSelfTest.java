package com.fundamentum.combat;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.compat.BetterCombat;
import com.fundamentum.compat.MeleeDriver;
import com.fundamentum.damage.MeleeKinds;
import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.fx.Shake;
import com.fundamentum.poise.Poise;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FReg;
import com.fundamentum.port.FVanilla;
import com.fundamentum.shield.Parry;
import com.fundamentum.stamina.Stamina;
import com.fundamentum.stamina.StaminaSource;
import com.fundamentum.weapon.WeaponTraits;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The combat test, only in {@code gradlew runServer -Pcombattest} (world under run-combattest). The same file on every
 * version. Checks attribute registration through FReg, poise and the staggered effect, the parry window and its event,
 * the stamina bridge, weapon traits, melee kinds, the melee driver order and the shake packet. Writes
 * combattest-result.txt and stops the server. Log lines start with COMBATTEST.
 */
public final class CombatSelfTest {
    private CombatSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final FReg.Attr TEST_ATTR = FReg.attribute(FId.of("fundamentum", "combattest_attr"), 7, 0, 100);
    private static final int Y = 200;
    private static boolean failed;
    private static int ticks;
    private static int staggers;
    private static int parries;
    private static boolean cancelStagger;
    private static boolean cancelStamina;
    private static final double[] POOL = {5};

    public static void init() {
        if (System.getProperty("fundamentum.combattest") == null) return;
        FLife.serverTick(CombatSelfTest::tick);
        FEvents.listen(Events.Staggered.class, (FEvents.Listener<Events.Staggered>) (e, call) -> {
            staggers++;
            if (cancelStagger) call.cancel();
        });
        FEvents.listen(Events.ShieldBlocked.class, (FEvents.Listener<Events.ShieldBlocked>) (e, call) -> {
            if (e.parried()) parries++;
        });
        FEvents.listen(Events.StaminaSpent.class, (FEvents.Listener<Events.StaminaSpent>) (e, call) -> {
            if (cancelStamina) call.cancel();
        });
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("COMBATTEST {}", line);
        else LOG.error("COMBATTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks == 5) server.overworld().setChunkForced(0, 0, true);
        try {
            if (ticks == 40) run(server);
        } catch (Throwable t) {
            LOG.error("COMBATTEST threw", t);
            check(false, "threw " + t);
        }
        if (ticks != 60) return;
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("COMBATTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("combattest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("COMBATTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static LivingEntity zombie(ServerLevel level, double x) {
        LivingEntity z = (LivingEntity) FVanilla.create(FId.of("minecraft:zombie"), level);
        z.setPos(x, Y, 0.5);
        z.setNoGravity(true);
        level.addFreshEntity(z);
        return z;
    }

    private static ItemStack stack(String id) {
        return new ItemStack(FVanilla.item(FId.of(id)));
    }

    private static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        LivingEntity a = zombie(level, 0.5);
        LivingEntity b = zombie(level, 3.5);

        // gap 14: an attribute registered through FReg reaches every living entity
        check(TEST_ATTR.bound() && a.getAttribute(TEST_ATTR.holder()) != null, "FReg.attribute is on every entity type");
        check(TEST_ATTR.value(a) == 7, "FReg.attribute default value (" + TEST_ATTR.value(a) + ")");

        // gap 3: poise and stagger
        check(Poise.current(a) == 0 && !Poise.damage(a, b, 100), "poise 0 never staggers");
        a.getAttribute(StatAttributes.POISE).setBaseValue(10);
        check(Poise.current(a) == 10, "poise reads the attribute");
        check(!Poise.damage(a, b, 6) && Poise.current(a) == 4, "poise damage adds up (" + Poise.current(a) + ")");
        int before = staggers;
        check(Poise.damage(a, b, 5) && Poise.staggered(a), "broken poise staggers");
        check(staggers == before + 1, "the staggered event fired");
        check(a.getAttributeValue(Attributes.MOVEMENT_SPEED) < a.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue(),
            "staggered slows");
        cancelStagger = true;
        check(!Poise.stagger(b, a, 20) && !Poise.staggered(b), "a cancelled staggered event keeps it standing");
        cancelStagger = false;

        // gap 4: parry window
        a.removeAllEffects();
        b.setHealth(b.getMaxHealth());
        Parry.open(b, stack("minecraft:iron_sword"), 10);
        check(Parry.isOpen(b), "the parry window opens");
        int p0 = parries;
        float hp = b.getHealth();
        FVanilla.hurt(b, level.damageSources().mobAttack(a), 4);
        check(b.getHealth() == hp, "a parried hit does no damage");
        check(parries == p0 + 1 && !Parry.isOpen(b), "parry fires shield_blocked (parried) and closes");
        check(Poise.staggered(a), "the attacker is staggered by the parry");
        b.setInvulnerableTime(0);
        FVanilla.hurt(b, level.damageSources().mobAttack(a), 4);
        check(b.getHealth() < hp, "the next hit lands");

        // gap 6: stamina bridge
        check(Stamina.spend(a, 5) && Stamina.fraction(a) == 1.0 && !Stamina.has(a), "no source: spend succeeds, no limit");
        FId src = FId.of("fundamentum", "combattest_pool");
        Stamina.register(new StaminaSource() {
            @Override public FId id() { return src; }
            @Override public boolean handles(LivingEntity e) { return e == a; }
            @Override public double get(LivingEntity e) { return POOL[0]; }
            @Override public double max(LivingEntity e) { return 10; }
            @Override public boolean spend(LivingEntity e, double n) { POOL[0] -= n; return true; }
            @Override public double restore(LivingEntity e, double n) {
                double r = Math.min(n, 10 - POOL[0]);
                POOL[0] += r;
                return r;
            }
        });
        check(Stamina.has(a) && Stamina.get(a) == 5 && Stamina.fraction(a) == 0.5, "a source gives the pool");
        check(Stamina.spend(a, 3) && Stamina.get(a) == 2, "spend takes stamina");
        check(!Stamina.spend(a, 3) && Stamina.get(a) == 2, "spend is all or nothing");
        cancelStamina = true;
        check(!Stamina.spend(a, 1) && Stamina.get(a) == 2, "a cancelled stamina_spent refuses");
        cancelStamina = false;
        check(Stamina.restore(a, 20) == 8, "restore caps at max");
        Stamina.unregister(src);

        // gaps 5 and 7: traits and kinds from tags
        ItemStack sword = stack("minecraft:iron_sword");
        ItemStack mace = stack("minecraft:mace");
        check(WeaponTraits.has(sword, "sword") && WeaponTraits.has(sword, "sweeping"), "iron sword has sword and sweeping " + WeaponTraits.of(sword));
        check(WeaponTraits.has(mace, "heavy"), "mace is heavy " + WeaponTraits.of(mace));
        check(MeleeKinds.SLASH.equals(MeleeKinds.of(sword)), "a sword slashes");
        check(MeleeKinds.BLUNT.equals(MeleeKinds.of(mace)), "a mace is blunt");
        check(MeleeKinds.is(level.damageSources().source(DamageTypes.ARROW, a), MeleeKinds.PIERCE), "arrow damage pierces");
        check(MeleeKinds.of(level.damageSources().mobAttack(a)) == null, "an unarmed zombie hit has no kind");

        // gap 9: driver order
        FId fc = FId.of("fundamentum", "combattest_driver");
        check(MeleeDriver.fallback(sword), "no driver: the type fallback runs");
        MeleeDriver.register(fc, 10, s -> WeaponTraits.has(s, "sword"));
        check(MeleeDriver.owns(sword, fc), "a registered driver takes swords");
        BetterCombat.register("combattest_sword", WeaponTraits.tag("sword"),
            Identifier.fromNamespaceAndPath("fundamentum", "combattest_sword"), BetterCombat.LeftClick.BETTER_COMBAT);
        BetterCombat.override(true);
        check(MeleeDriver.BETTER_COMBAT.equals(MeleeDriver.driverOf(sword)), "Better Combat comes first when loaded");
        BetterCombat.override(null);
        check(MeleeDriver.owns(sword, fc), "without Better Combat the driver plays");
        MeleeDriver.unregister(fc);

        // gap 2: shake packet
        check(FNet.registered(Shake.ID), "the shake packet is registered");
        a.discard();
        b.discard();
    }
}
