package com.fundamentum.mobgoal;

import com.fundamentum.mobgoal.goal.CallAlliesGoal;
import com.fundamentum.mobgoal.goal.ChargeGoal;
import com.fundamentum.mobgoal.goal.FactionTargetGoal;
import com.fundamentum.mobgoal.goal.FleeLowHealthGoal;
import com.fundamentum.mobgoal.goal.FollowLeaderGoal;
import com.fundamentum.mobgoal.goal.GuardGoal;
import com.fundamentum.mobgoal.goal.KiteGoal;
import com.fundamentum.mobgoal.goal.LeapGoal;
import com.fundamentum.mobgoal.goal.RangedAmmoGoal;
import com.fundamentum.mobgoal.goal.StrafeGoal;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FVanilla;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mob goal test, only in {@code gradlew runServer -Pgoaltest} (world under run-goaltest). The same file on every
 * version. Loads goal entries from the test data, spawns mobs on a stone platform high in the sky with their own
 * ticking turned off, then drives each goal by hand: which mobs get goals (brain mobs get none), kite, strafe, leap,
 * charge, flee, call allies, guard, follow leader, ranged with ammo, faction targets, boss phase gates, a goal type
 * added from code, reapplying, and the command. Writes goaltest-result.txt and stops the server. Log lines start with
 * GOALTEST.
 */
public final class MobGoalSelfTest {
    private MobGoalSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final int Y = 200;
    private static boolean failed;
    private static int ticks;
    private static Mob husk;

    /** The goal type the test adds from code. */
    public static final class MarkerGoal extends Goal {
        final int value;

        MarkerGoal(int value) {
            this.value = value;
        }

        @Override
        public boolean canUse() {
            return false;
        }
    }

    public static void init() {
        if (System.getProperty("fundamentum.goaltest") == null) return;
        MobGoals.register(FId.of("goaltest", "marker"), false, (mob, p) -> new MarkerGoal(p.i("value", 0)));
        FLife.serverTick(MobGoalSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("GOALTEST {}", line);
        else LOG.error("GOALTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks == 5) {
            // keep the test area loaded: there are no players, and newer versions have no spawn chunks
            for (int cx = -3; cx <= 2; cx++) {
                for (int cz = -2; cz <= 1; cz++) server.overworld().setChunkForced(cx, cz, true);
            }
        }
        if (ticks != 40 && ticks != 70) return;
        try {
            if (ticks == 40) {
                run(server);
                return;
            }
            phases(server);
        } catch (Throwable t) {
            LOG.error("GOALTEST threw", t);
            check(false, "threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("GOALTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("goaltest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("GOALTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static Mob spawn(ServerLevel level, String id, double x, double z) {
        Entity e = FVanilla.create(FId.of(id), level);
        e.setPos(x + 0.5, Y, z + 0.5);
        Mob m = (Mob) e;
        m.setNoAi(true);
        m.setPersistenceRequired();
        m.setOnGround(true);
        level.addFreshEntity(m);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static <G> G goal(Mob mob, Class<G> type) {
        for (MobGoals.Added a : MobGoals.added(mob)) {
            Goal g = a.goal();
            if (g instanceof PhaseGate p) g = p.inner();
            if (type.isInstance(g)) return (G) g;
        }
        return null;
    }

    private static int goalCount(Mob mob) {
        return mob.goalSelector.getAvailableGoals().size() + mob.targetSelector.getAvailableGoals().size();
    }

    private static void discard(Entity... es) {
        for (Entity e : es) e.discard();
    }

    private static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        for (int x = -40; x <= 40; x++) {
            for (int z = -24; z <= 24; z++) {
                level.setBlock(new BlockPos(x, Y - 1, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = Y; y < Y + 4; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        // mobs left in the saved world by an earlier run would join the packs
        for (Entity e : level.getEntities((Entity) null, new AABB(-60, Y - 40, -40, 60, Y + 40, 40), e -> !(e instanceof Player))) e.discard();

        // data and types
        check(MobGoals.types().size() == 11 && MobGoals.types().contains(FId.of("fundamentum", "faction_target"))
            && MobGoals.types().contains(FId.of("goaltest", "marker")), "ten built-in goal types and the test's own (" + MobGoals.types().size() + ")");
        check(MobGoals.entries().size() == 13, "13 entries load and the broken files are left out (" + MobGoals.entries().size() + ")");
        check(MobGoals.entries().stream().noneMatch(e -> e.file().path().startsWith("broken")), "no entry from a broken file survives");

        // which mobs get goals
        Mob zombie = spawn(level, "minecraft:zombie", 0, 0);
        check(!MobGoals.brainDriven(zombie), "a zombie runs on goals");
        check(MobGoals.added(zombie).size() == 9, "the zombie gets its nine goals (" + MobGoals.added(zombie).size() + ")");
        boolean inSelectors = true;
        for (MobGoals.Added a : MobGoals.added(zombie)) {
            var sel = a.target() ? zombie.targetSelector : zombie.goalSelector;
            if (sel.getAvailableGoals().stream().noneMatch(w -> w.getGoal() == a.goal())) inSelectors = false;
        }
        check(inSelectors, "every added goal sits in the right selector");
        check(MobGoals.added(zombie).stream().anyMatch(a -> a.target() && a.goal() instanceof FactionTargetGoal), "faction_target goes into the target selector");
        Mob villager = spawn(level, "minecraft:villager", 0, 20);
        check(MobGoals.brainDriven(villager) && MobGoals.added(villager).isEmpty(), "a villager runs on a brain and is left alone");
        Mob cow = spawn(level, "minecraft:cow", 4, 20);
        MarkerGoal marker = goal(cow, MarkerGoal.class);
        check(marker != null && marker.value == 7, "a goal type registered from code is used by data with its params");
        int before = goalCount(zombie);
        MobGoals.apply(zombie);
        check(goalCount(zombie) == before && MobGoals.added(zombie).size() == 9, "applying again swaps the goals instead of stacking them");
        MobGoals.remove(cow);
        check(MobGoals.added(cow).isEmpty() && goal(cow, MarkerGoal.class) == null
            && cow.goalSelector.getAvailableGoals().stream().noneMatch(w -> w.getGoal() == marker), "remove takes the added goals off");
        discard(villager, cow);

        // kite
        Mob pig = spawn(level, "minecraft:pig", 3, 0);
        zombie.setTarget(pig);
        KiteGoal kite = goal(zombie, KiteGoal.class);
        boolean grounded = zombie.onGround();
        zombie.setOnGround(true);
        // the away-point is a random pick that can miss; in game the goal is asked again every tick
        boolean kiteUse = false;
        for (int i = 0; i < 10 && !kiteUse; i++) kiteUse = kite.canUse();
        check(kiteUse && !zombie.getNavigation().isDone(), "kite backs off a close target (" + kiteUse + ", was on ground " + grounded + ")");
        kite.stop();
        pig.setPos(12.5, Y, 0.5);
        check(!kite.canUse(), "kite leaves a far target alone");

        // strafe
        pig.setPos(5.5, Y, 0.5);
        StrafeGoal strafe = goal(zombie, StrafeGoal.class);
        check(strafe.canUse(), "strafe starts near the target");
        strafe.start();
        strafe.tick();
        zombie.getMoveControl().tick();
        check(zombie.xxa != 0, "strafe moves sideways (" + zombie.xxa + ")");
        strafe.stop();
        pig.setPos(20.5, Y, 0.5);
        check(!strafe.canUse(), "strafe stops when the target is far");

        // leap
        pig.setPos(4.5, Y, 0.5);
        zombie.setOnGround(true);
        LeapGoal leap = goal(zombie, LeapGoal.class);
        check(leap.canUse(), "leap fires in range");
        leap.start();
        check(zombie.getDeltaMovement().y > 0.3 && zombie.getDeltaMovement().x > 0.2, "leap jumps up and at the target (" + zombie.getDeltaMovement() + ")");
        check(!leap.canUse(), "leap waits out its cooldown");
        zombie.setDeltaMovement(0, 0, 0);
        zombie.setPos(0.5, Y, 0.5);

        // charge
        pig.setPos(8.5, Y, 0.5);
        zombie.setOnGround(true);
        ChargeGoal charge = goal(zombie, ChargeGoal.class);
        check(charge.canUse(), "charge starts at range");
        charge.start();
        pig.setPos(1.2, Y, 0.5);
        float pigBefore = pig.getHealth();
        charge.tick();
        check(charge.landed() && pig.getHealth() < pigBefore, "charge hits on contact (" + pigBefore + " -> " + pig.getHealth() + ")");
        check(!charge.canContinueToUse(), "charge ends after its hit");
        charge.stop();
        check(!charge.canUse(), "charge waits out its cooldown");
        zombie.setDeltaMovement(0, 0, 0);
        zombie.setPos(0.5, Y, 0.5);

        // flee
        pig.setPos(3.5, Y, 0.5);
        FleeLowHealthGoal flee = goal(zombie, FleeLowHealthGoal.class);
        check(!flee.canUse(), "no fleeing at full health");
        zombie.setHealth(2);
        zombie.setOnGround(true);
        boolean fleeUse = false;
        // the escape spot is picked at random and can miss; a few tries keeps the test steady
        for (int i = 0; i < 5 && !fleeUse; i++) fleeUse = flee.canUse();
        check(fleeUse && !zombie.getNavigation().isDone(), "flees at low health (" + fleeUse + ", target " + zombie.getTarget() + ")");
        flee.stop();
        zombie.setHealth(zombie.getMaxHealth());

        // faction targets
        FactionTargetGoal faction = goal(zombie, FactionTargetGoal.class);
        zombie.setTarget(null);
        check(faction.scan() == null, "the default faction rule finds nobody hostile");
        MobGoals.setFactions(new FactionHook() {
            @Override
            public boolean hostile(Mob self, LivingEntity other) {
                return FVanilla.idOf(other.getType()).equals(FId.of("minecraft", "pig"));
            }
        });
        check(faction.canUse(), "a faction rule finds the hostile");
        faction.start();
        check(zombie.getTarget() == pig, "faction_target sets the target");
        MobGoals.setFactions(null);
        check(!faction.canContinueToUse(), "the target is dropped once the rule says friend");
        faction.stop();
        check(MobGoals.factions() == FactionHook.DEFAULT, "setFactions(null) puts the default back");

        // call allies
        Mob ally = spawn(level, "minecraft:zombie", 0, 4);
        CallAlliesGoal call = goal(zombie, CallAlliesGoal.class);
        zombie.setTarget(null);
        check(!call.canUse(), "no call before being hurt");
        zombie.setLastHurtByMob(pig);
        check(call.canUse(), "a hit makes it call");
        call.start();
        check(ally.getTarget() == pig && call.lastCalled() >= 1, "the nearby ally turns on the attacker");
        check(!call.canUse(), "one call per hit");
        discard(ally);

        // follow leader
        Mob follower = spawn(level, "minecraft:zombie", 0, 12);
        FollowLeaderGoal follow = goal(follower, FollowLeaderGoal.class);
        zombie.setTarget(null);
        follower.setTarget(null);
        check(follow.findLeader() == zombie, "the older zombie leads");
        check(goal(zombie, FollowLeaderGoal.class).findLeader() == null, "the leader follows nobody");
        check(follow.canUse(), "the follower heads for the leader when far");
        follow.stop();
        follower.setPos(0.5, Y, 2.5);
        check(!follow.canUse(), "the follower stays put when close");
        discard(follower);

        // guard
        GuardGoal guard = goal(zombie, GuardGoal.class);
        check(guard.point().equals(new BlockPos(0, Y, 0)), "the guard point is where the mob was added");
        zombie.setPos(14.5, Y, 0.5);
        zombie.setTarget(null);
        check(guard.canUse() && !zombie.getNavigation().isDone(), "a guard walks back when it strays");
        guard.stop();
        pig.setPos(30.5, Y, 0.5);
        zombie.setTarget(pig);
        guard.canUse();
        check(zombie.getTarget() == null, "a guard drops a target past its leash");
        discard(zombie);

        // ranged with ammo
        Mob skeleton = spawn(level, "minecraft:skeleton", -20, 0);
        RangedAmmoGoal ranged = goal(skeleton, RangedAmmoGoal.class);
        check(ranged != null, "the skeleton tag entry gives skeletons ranged_ammo");
        skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        skeleton.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, 3));
        pig.setPos(-12.5, Y, 0.5);
        skeleton.setTarget(pig);
        check(ranged.canUse(), "ranged runs with ammo and a target");
        boolean shot = ranged.shoot(pig);
        int arrows = level.getEntities((Entity) null, new AABB(-30, Y - 5, -10, -5, Y + 10, 10),
            e -> FVanilla.idOf(e.getType()).equals(FId.of("minecraft", "arrow"))).size();
        check(shot && skeleton.getOffhandItem().getCount() == 2 && ranged.shots() == 1, "a shot uses one arrow (" + skeleton.getOffhandItem().getCount() + ")");
        check(arrows >= 1, "the shot puts an arrow in the world (" + arrows + ")");
        skeleton.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        check(!ranged.hasAmmo() && !ranged.canUse() && !ranged.shoot(pig), "no ammo, no shooting");
        int[] custom = {0};
        MobGoals.addShooter((mob, target, ammo, power) -> {
            custom[0]++;
            return true;
        });
        skeleton.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ARROW, 1));
        check(ranged.shoot(pig) && custom[0] == 1 && skeleton.getOffhandItem().isEmpty(), "a shooter from code fires the shot");
        discard(skeleton);

        // boss phase gate (second half in phases())
        husk = spawn(level, "minecraft:husk", 20, 0);
        check(com.fundamentum.boss.Bosses.get(husk) != null, "the husk is a boss from the test data");
        Goal g = MobGoals.added(husk).isEmpty() ? null : MobGoals.added(husk).get(0).goal();
        check(g instanceof PhaseGate p && p.inner() instanceof LeapGoal && p.phases().contains("two"), "a phases entry wraps the goal in a gate");
        PhaseGate gate = (PhaseGate) g;
        check(gate != null && !gate.open(), "the gate is shut in the boss's first phase");
        husk.setHealth(husk.getMaxHealth() * 0.3f);
        discard(pig);

        // command
        try {
            int r = server.getCommands().getDispatcher().execute("fundamentum mobgoals", server.createCommandSourceStack().withSuppressedOutput());
            check(r == 13, "the mobgoals command counts the entries (" + r + ")");
        } catch (Exception e) {
            check(false, "the mobgoals command ran: " + e);
        }
    }

    private static void phases(MinecraftServer server) {
        PhaseGate gate = (PhaseGate) MobGoals.added(husk).get(0).goal();
        var boss = com.fundamentum.boss.Bosses.get(husk);
        check(gate.open(), "the gate opens in phase two (" + (boss == null ? "no boss" : boss.phase()) + ", health " + husk.getHealth() + ")");
        Mob pig = spawn(server.overworld(), "minecraft:pig", 24, 0);
        husk.setTarget(pig);
        husk.setOnGround(true);
        check(gate.canUse(), "the gated leap can run in phase two");
        discard(husk, pig);
    }
}
