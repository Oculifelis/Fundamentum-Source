package com.fundamentum.dodge;

import com.fundamentum.attribute.StatAttributes;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FVanilla;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The dodge test, only in {@code gradlew runServer -Pdodgetest} (world under run-dodgetest). The same file on every
 * version. Checks that styles and gear load from data, that 0 charges means no dodge, that a dodge spends a charge,
 * moves the player and ignores hits (not hits that bypass invulnerability), that the charge comes back, and the style
 * rules (ground only, air only, rise). Writes dodgetest-result.txt and stops the server. Log lines start with DODGETEST.
 */
public final class DodgeSelfTest {
    private DodgeSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final FId ROLL = FId.of("fundamentum", "roll");
    private static final FId AIR = FId.of("dodgetest", "air_hop");
    private static final int Y = 200;
    private static boolean failed;
    private static int ticks;
    private static ServerPlayer mover;
    private static ServerPlayer target;
    private static double startX, startZ;

    public static void init() {
        if (System.getProperty("fundamentum.dodgetest") == null) return;
        FLife.serverTick(DodgeSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("DODGETEST {}", line);
        else LOG.error("DODGETEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks == 5) server.overworld().setChunkForced(0, 0, true);
        try {
            if (ticks == 40) start(server);
            else if (ticks == 52) moved();
            else if (ticks == 100) recharged(server);
            else if (ticks == 110) rules(server);
            else if (ticks == 130) rise();
        } catch (Throwable t) {
            LOG.error("DODGETEST threw", t);
            check(false, "threw " + t);
            ticks = 199;
        }
        if (ticks != 200) return;
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("DODGETEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("dodgetest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("DODGETEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static void charges(ServerPlayer p, double n) {
        attr(p, StatAttributes.DODGE_CHARGES, n);
    }

    private static void attr(ServerPlayer p, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> a, double v) {
        p.getAttribute(a).setBaseValue(v);
    }

    private static void start(MinecraftServer server) {
        ServerLevel level = server.overworld();
        check(Dodges.styles().keySet().containsAll(List.of(Dodges.DASH, ROLL, AIR)), "dash, roll and the test air style load " + Dodges.styles().keySet());
        DodgeStyle roll = Dodges.style(ROLL);
        check(roll != null && roll.low() && !roll.air() && roll.lockTicks() == 6, "roll reads its pose, ground rule and lock");

        // a fake player moves itself, so it shows the dodge's motion
        mover = FVanilla.fakePlayer(level, "dodgetest_mover");
        mover.setPos(0.5, Y, 0.5);
        mover.setYRot(0);
        check(Dodges.maxCharges(mover) == 0 && !Dodges.tryDodge(mover, 1, 0, false), "0 charges: no dodge");
        charges(mover, 2);
        check(Dodges.maxCharges(mover) == 2 && Dodges.charges(mover) == 2, "the attribute gives 2 charges");
        check(Dodges.styleOf(mover).id().equals(Dodges.DASH), "dash is the default style");
        mover.setItemSlot(EquipmentSlot.FEET, new ItemStack(FVanilla.item(FId.of("minecraft:leather_boots"))));
        check(Dodges.styleOf(mover).id().equals(ROLL), "gear data picks the style (boots give roll)");
        mover.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        startX = mover.getX();
        startZ = mover.getZ();
        mover.setOnGround(true);
        check(Dodges.tryDodge(mover, 1, 0, false), "a dodge starts");
        check(Dodges.charges(mover) == 1, "the dodge spends a charge (" + Dodges.charges(mover) + ")");
        check(Dodges.dodging(mover) && Dodges.invulnerable(mover), "the player is dodging and ignores hits");
        check(!Dodges.tryDodge(mover, 1, 0, false), "no second dodge while the first one moves");

        // a plain server player takes real damage through the loader hook
        target = FVanilla.testPlayer(level, "dodgetest_target");
        target.connection = FVanilla.fakePlayer(level, "fundamentum_test_connection").connection;
        try {
            target.connection.getClass().getMethod("markClientLoaded").invoke(target.connection);
        } catch (ReflectiveOperationException ignored) { }
        target.setPos(4.5, Y, 4.5);
        noSpawnShield(target);
        target.setHealth(20);
        FVanilla.hurt(target, level.damageSources().generic(), 1);
        check(target.getHealth() < 20, "a plain hit lands before the dodge (" + target.getHealth() + ")");
        FVanilla.clearHurtCooldown(target);
        target.setHealth(20);
        charges(target, 1);
        target.setOnGround(true);
        check(Dodges.tryDodge(target, 0, 1, false), "a second player dodges");
        FVanilla.clearHurtCooldown(target);
        FVanilla.hurt(target, level.damageSources().generic(), 5);
        check(target.getHealth() == 20, "a hit during the dodge misses (" + target.getHealth() + ")");
        FVanilla.clearHurtCooldown(target);
        FVanilla.hurt(target, level.damageSources().fellOutOfWorld(), 1);
        check(target.getHealth() < 20, "damage that bypasses invulnerability still lands (" + target.getHealth() + ")");
    }

    /** A new player ignores hits for its first 3 seconds; the test player is not ticked, so end that now. */
    private static void noSpawnShield(ServerPlayer p) {
        try {
            java.lang.reflect.Field f = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
            f.setAccessible(true);
            f.setInt(p, 0);
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }

    private static void moved() {
        double d = Math.hypot(mover.getX() - startX, mover.getZ() - startZ);
        check(d > 3 && d < 6, "the dodge carries the player about 4 blocks (" + String.format("%.2f", d) + ")");
        check(mover.getZ() - startZ > 3, "facing south, forward goes to +z");
        check(!Dodges.dodging(mover) && !Dodges.invulnerable(mover), "the dodge and its i-frames end");
        float h = target.getHealth();
        FVanilla.clearHurtCooldown(target);
        FVanilla.hurt(target, target.level().damageSources().generic(), 2);
        check(target.getHealth() < h, "hits land again after the i-frames");
    }

    private static void recharged(MinecraftServer server) {
        check(Dodges.charges(mover) == 2, "the spent charge comes back (" + Dodges.charges(mover) + ")");
        attr(mover, StatAttributes.DODGE_RECHARGE, 0);
        mover.setPos(0.5, Y, 0.5);
        mover.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        mover.setOnGround(true);
        check(Dodges.tryDodge(mover, 0, 0, false), "a dodge with no keys goes back");
    }

    private static void rules(MinecraftServer server) {
        check(Dodges.charges(mover) == 1, "with recharge 0 the charge stays spent (" + Dodges.charges(mover) + ")");
        Dodges.refill(mover, 99);
        check(Dodges.charges(mover) == 2, "refill gives the charges back");
        attr(mover, StatAttributes.DODGE_RECHARGE, 1);
        mover.setPos(0.5, Y, 0.5);
        mover.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        Dodges.setStyle(mover, ROLL);
        mover.setOnGround(false);
        check(!Dodges.tryDodge(mover, 1, 0, false), "roll does not work in the air");
        check(Dodges.styles().get(ROLL).exitSpeed() == 0.1 && Dodges.styles().get(ROLL).impactReach() == 0.4, "roll reads exit speed and impact reach from data");
        check(!Dodges.styles().containsKey(FId.of("fundamentum", "air_dash")), "no air dash ships");
        Dodges.setStyle(mover, AIR);
        mover.setOnGround(true);
        check(!Dodges.tryDodge(mover, 1, 0, false), "a ground:false style does not work on the ground");
        mover.setOnGround(false);
        startX = mover.getY();
        check(Dodges.tryDodge(mover, 0, 0, true), "an allow_up style with jump held rises");
    }

    private static void rise() {
        check(mover.getY() - startX > 2, "the rise carries the player up (" + String.format("%.2f", mover.getY() - startX) + ")");
        mover.setOnGround(false);
        check(!Dodges.tryDodge(mover, 1, 0, false), "only one air dodge per jump");
        Dodges.setStyle(mover, null);
        ticks = 199;
    }
}
