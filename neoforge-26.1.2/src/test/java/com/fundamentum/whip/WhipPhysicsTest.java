package com.fundamentum.whip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shared cord physics, run headless: one swing of the default whip from a player standing at the origin and
 * looking along +z, with mob-sized boxes placed around them. The example whip ({@code fundamentum:example_whip}) is
 * this tuning; the in-game half (damage, aim assist on real entities) is {@code WhipSelfTest}.
 */
class WhipPhysicsTest {
    static final WhipVec HAND = new WhipVec(0.35, 1.1, 0.3);
    static final WhipVec LOOK = new WhipVec(0, 0, 1);

    /** A zombie-sized box standing with its feet at (x, 0, z). */
    static double[] mob(double x, double z) {
        return new double[] {x - 0.3, 0, z - 0.3, x + 0.3, 1.95, z + 0.3};
    }

    record Hit(double tick, CordHit.Contact contact, double tipSpeed) {}

    /** Runs a whole swing and returns the first hit on the box while hits count, or null. Fills tipSpeeds. */
    static Hit swing(WhipTuning tuning, double[] box, List<Double> tipSpeeds, List<WhipVec> tips) {
        WhipSwing swing = new WhipSwing(tuning, LOOK, 1);
        swing.start(HAND);
        Hit[] first = {null};
        while (swing.tick(HAND, HAND, (k, cord, tick, dt) -> {
            double speed = cord.tipSpeed(dt);
            tipSpeeds.add(speed);
            tips.add(cord.tip());
            if (first[0] == null && tuning.hitting(tick) && swing.forward(k, cord, tick)) {
                CordHit.Contact c = CordHit.test(cord, tuning.hitRadius(), box);
                if (c != null) {
                    first[0] = new Hit(tick, c, speed);
                }
            }
        })) {
            // keep swinging
        }
        return first[0];
    }

    @Test
    void cordKeepsItsLength() {
        WhipTuning t = WhipTuning.DEFAULT;
        WhipSwing swing = new WhipSwing(t, LOOK, 1);
        swing.start(HAND);
        double[] worst = {0};
        while (swing.tick(HAND, HAND, (k, cord, tick, dt) -> {
            double len = 0;
            for (int i = 0; i < cord.segments; i++) {
                len += cord.points[i].distanceTo(cord.points[i + 1]);
            }
            worst[0] = Math.max(worst[0], len);
        })) {
            // keep swinging
        }
        assertTrue(worst[0] <= t.length() * 1.021, "cord stretched to " + worst[0]);
    }

    @Test
    void tipCracksPastTheHandle() {
        List<Double> speeds = new ArrayList<>();
        swing(WhipTuning.DEFAULT, mob(0, 50), speeds, new ArrayList<>());
        double peak = speeds.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        System.out.println("peak tip speed " + peak);
        assertTrue(peak >= WhipTuning.DEFAULT.crackSpeed(), "tip never cracked: peak " + peak + " b/s");
    }

    @Test
    void reachesAMobAtFourBlocksWithTheTip() {
        Hit hit = swing(WhipTuning.DEFAULT, mob(0, 4.2), new ArrayList<>(), new ArrayList<>());
        assertNotNull(hit, "a mob 4 blocks ahead was not hit");
        System.out.println("hit at tick " + hit.tick + " along " + hit.contact.along() + " tip speed " + hit.tipSpeed);
        assertTrue(WhipTuning.DEFAULT.isTip(hit.contact.along()), "hit on the cord at " + hit.contact.along() + ", not the tip");
    }

    @Test
    void doesNotReachPastTheCord() {
        assertNull(swing(WhipTuning.DEFAULT, mob(0, 7.5), new ArrayList<>(), new ArrayList<>()), "hit a mob beyond the cord");
    }

    @Test
    void doesNotHitBehindThePlayer() {
        assertNull(swing(WhipTuning.DEFAULT, mob(0, -2.5), new ArrayList<>(), new ArrayList<>()), "hit a mob behind");
    }

    @Test
    void closeMobIsHitByTheCordNotTheTip() {
        Hit hit = swing(WhipTuning.DEFAULT, mob(0.2, 1.4), new ArrayList<>(), new ArrayList<>());
        assertNotNull(hit, "a mob right in front was not hit");
        assertFalse(WhipTuning.DEFAULT.isTip(hit.contact.along()), "a mob at 1.4 blocks was hit by the tip");
    }

    @Test
    void longerWhipReachesFurther() {
        WhipTuning longer = WhipTuning.DEFAULT.toBuilder().length(7).build();
        assertNull(swing(WhipTuning.DEFAULT, mob(0, 6.5), new ArrayList<>(), new ArrayList<>()));
        assertNotNull(swing(longer, mob(0, 6.5), new ArrayList<>(), new ArrayList<>()), "a 7 block whip missed at 6.5");
    }

    @Test
    void strandsFanOut() {
        WhipTuning three = WhipTuning.DEFAULT.toBuilder().strands(3).strandSpread(25).build();
        WhipSwing swing = new WhipSwing(three, LOOK, 1);
        swing.start(HAND);
        while (swing.tick(HAND, HAND, (k, cord, tick, dt) -> {})) {
            // keep swinging
        }
        assertTrue(swing.cords[0].tip().distanceTo(swing.cords[2].tip()) > 0.5, "strands landed on each other");
    }

    @Test
    void sweptTestCatchesAFastTipBetweenSteps() {
        // A segment that jumps clean over a thin box in one step still touches it.
        double[] box = {-0.1, -0.1, -0.1, 0.1, 0.1, 0.1};
        assertNotNull(CordHit.sweep(new WhipVec(-1, 0, -2), new WhipVec(1, 0, -2), new WhipVec(-1, 0, 2), new WhipVec(1, 0, 2), 0.05, box));
        assertNull(CordHit.sweep(new WhipVec(-1, 1, -2), new WhipVec(1, 1, -2), new WhipVec(-1, 1, 2), new WhipVec(1, 1, 2), 0.05, box));
    }

    @Test
    void aimAssistCone() {
        WhipVec eye = new WhipVec(0, 1.6, 0);
        assertTrue(CordHit.nearCrosshair(eye, LOOK, new WhipVec(0.5, 1.6, 4), 15));
        assertFalse(CordHit.nearCrosshair(eye, LOOK, new WhipVec(3, 1.6, 4), 15));
    }

    @Test
    void damageNumbers() {
        WhipTuning t = WhipTuning.DEFAULT;
        assertEquals(t.rootDamage(), t.falloff(0), 1e-9);
        assertEquals(1.0, t.falloff(1), 1e-9);
        assertTrue(t.falloff(0.5) < t.falloff(0.9));
        assertEquals(1.0, t.multiHit(0), 1e-9);
        assertEquals(t.multiHitFloor(), t.multiHit(40), 1e-9);
        assertTrue(t.isTip(0.9) && !t.isTip(0.5));
    }

    /** Solid ground: every block with its top at y = 0 and below. */
    static final WhipCord.Solids GROUND = (x, y, z) -> y < 0 ? new double[] {x, y, z, x + 1, y + 1, z + 1} : null;

    @Test
    void cordLiesOnTheGroundInsteadOfPassingThrough() {
        WhipSwing swing = new WhipSwing(WhipTuning.DEFAULT, LOOK, 1);
        swing.start(HAND, GROUND);
        double[] lowest = {Double.MAX_VALUE};
        while (swing.tick(HAND, HAND, GROUND, (k, cord, tick, dt) -> {
            for (WhipVec p : cord.points) {
                lowest[0] = Math.min(lowest[0], p.y());
            }
        })) {
            // keep swinging
        }
        assertTrue(lowest[0] > -0.01, "the cord went " + lowest[0] + " into the ground");
        // Without the ground the follow-through goes well below the feet, so the test means something.
        double[] free = {Double.MAX_VALUE};
        WhipSwing open = new WhipSwing(WhipTuning.DEFAULT, LOOK, 1);
        open.start(HAND);
        while (open.tick(HAND, HAND, (k, cord, tick, dt) -> {
            for (WhipVec p : cord.points) {
                free[0] = Math.min(free[0], p.y());
            }
        })) {
            // keep swinging
        }
        assertTrue(free[0] < -0.5, "the free cord never went below the feet: " + free[0]);
    }

    @Test
    void wallStopsTheCord() {
        // A wall across the swing two blocks ahead: nothing gets past its far side.
        WhipCord.Solids wall = (x, y, z) -> z == 2 ? new double[] {x, y, z, x + 1, y + 1, z + 1} : null;
        WhipSwing swing = new WhipSwing(WhipTuning.DEFAULT, LOOK, 1);
        swing.start(HAND, wall);
        double[] furthest = {-Double.MAX_VALUE};
        while (swing.tick(HAND, HAND, wall, (k, cord, tick, dt) -> {
            for (WhipVec p : cord.points) {
                furthest[0] = Math.max(furthest[0], p.z());
            }
        })) {
            // keep swinging
        }
        assertTrue(furthest[0] < 3.0, "the cord got through the wall to z " + furthest[0]);
    }

    @Test
    void hangingCordRestsOnAFloor() {
        WhipCord cord = new WhipCord(8, 1.6);
        WhipVec hand = new WhipVec(0.5, 1.0, 0.5);
        WhipVec handle = new WhipVec(0.3, -1, 0).normalize();
        cord.layOut(hand, handle, GROUND);
        for (int i = 0; i < 200; i++) {
            cord.step(hand, handle, 0.0125, WhipCord.IDLE_GUIDE, 0.14, 0.3, 0.96, 20, GROUND);
        }
        for (WhipVec p : cord.points) {
            assertTrue(p.y() > -0.01, "a point sank to " + p.y());
        }
        // The end lies on the floor and spreads out, instead of standing on its end in a column under the hand.
        int flat = 0;
        double reach = 0;
        for (WhipVec p : cord.points) {
            flat += p.y() < 0.1 ? 1 : 0;
            reach = Math.max(reach, Math.abs(p.x() - hand.x()));
        }
        assertTrue(flat >= 3, flat + " points on the floor");
        assertTrue(reach > 0.3, "the cord spread only " + reach + " from the hand");
        // Lying still is not striking the floor.
        double rest = cord.tipImpact / 0.0125;
        assertTrue(rest < WhipTuning.DEFAULT.crackSpeed() * WhipCord.STRIKE_SPEED / 2, "a resting tip struck the floor at " + rest + " b/s");
    }

    @Test
    void tipStrikesTheWall() {
        // The wall from wallStopsTheCord: the tip snaps against it hard enough to count as a strike.
        WhipCord.Solids wall = (x, y, z) -> z == 2 ? new double[] {x, y, z, x + 1, y + 1, z + 1} : null;
        WhipSwing swing = new WhipSwing(WhipTuning.DEFAULT, LOOK, 1);
        swing.start(HAND, wall);
        double[] hardest = {0};
        int[][] where = {null};
        while (swing.tick(HAND, HAND, wall, (k, cord, tick, dt) -> {
            if (cord.tipBlock != null && cord.tipImpact / dt > hardest[0]) {
                hardest[0] = cord.tipImpact / dt;
                where[0] = cord.tipBlock;
            }
        })) {
            // keep swinging
        }
        System.out.println("hardest tip strike " + hardest[0] + " b/s");
        assertNotNull(where[0], "the tip never touched the wall");
        assertEquals(2, where[0][2], "the struck block is not the wall");
        assertTrue(hardest[0] >= WhipTuning.DEFAULT.crackSpeed() * WhipCord.STRIKE_SPEED, "the tip only brushed the wall: " + hardest[0] + " b/s");
        // In the open the tip strikes nothing.
        boolean[] touched = {false};
        WhipSwing open = new WhipSwing(WhipTuning.DEFAULT, LOOK, 1);
        open.start(HAND);
        while (open.tick(HAND, HAND, (k, cord, tick, dt) -> touched[0] |= cord.tipBlock != null)) {
            // keep swinging
        }
        assertFalse(touched[0]);
    }

    /** A floor under y = 0, as in game. */
    static final WhipCord.Solids FLOOR = (x, y, z) -> y < 0 ? new double[] {x, y, z, x + 1, y + 1, z + 1} : null;

    /** Where each strand-0 crack is heard in a whole swing (WhipSwing.crack), or null. */
    static WhipVec crackAt(WhipTuning tuning, WhipVec look, int side) {
        WhipSwing swing = new WhipSwing(tuning, look, side);
        swing.start(HAND, FLOOR);
        WhipVec[] at = {null};
        while (swing.tick(HAND, HAND, FLOOR, (k, cord, tick, dt) -> {
            if (swing.crack(k, cord, tick, dt) && k == 0) {
                at[0] = cord.tip();
            }
        })) {
            // keep swinging
        }
        return at[0];
    }

    @Test
    void crackIsOutInFrontAtAboutHandHeight() {
        // 1.22.1 heard the crack on the backswing's whip-back: 3.2 blocks up, 0.5 back from the stroke's reach.
        for (double length : new double[] {4.0, 4.5, 5.5, 6.5}) {
            WhipTuning t = WhipTuning.DEFAULT.toBuilder().length(length).build();
            for (int side : new int[] {1, -1}) {
                WhipVec at = crackAt(t, LOOK, side);
                assertNotNull(at, "no crack at length " + length + " side " + side);
                assertTrue(at.z() >= length * 0.8, "crack only " + at.z() + " out at length " + length + " side " + side);
                assertTrue(Math.abs(at.y() - HAND.y()) <= 1.25, "crack " + (at.y() - HAND.y()) + " off hand height at length " + length);
            }
        }
    }

    @Test
    void everyTuningInUseStillCracks() {
        // The consumers' odd tunings (Ostentum's Breeze, Golden Heavy and Royal Slime whips, UR's loose cords).
        List<WhipTuning> tunings = List.of(
            WhipTuning.DEFAULT.toBuilder().length(5.0).windupTicks(2).strokeTicks(3).recoverTicks(4).guide(0.95).build(),
            WhipTuning.DEFAULT.toBuilder().length(4.0).guide(0.7).windupTicks(6).strokeTicks(6).recoverTicks(10).build(),
            WhipTuning.DEFAULT.toBuilder().length(5.0).guide(0.6).retention(0.9).build(),
            WhipTuning.DEFAULT.toBuilder().stiffness(0.18).gravity(26).retention(0.97).build(),
            WhipTuning.DEFAULT.toBuilder().length(5.5).strands(3).strandSpread(10).build());
        for (WhipTuning t : tunings) {
            WhipVec at = crackAt(t, LOOK, 1);
            assertNotNull(at, "no crack with " + t);
            assertTrue(at.z() >= 2.5 && at.y() <= HAND.y() + 1.6, "crack in the air at " + at + " with " + t);
        }
    }

    @Test
    void backswingWhipBackIsNotAHit() {
        // A bat up where the tip whips back after the windup (over the swing side). 1.22.1 hit it then, on the way back,
        // with a crit; now it counts only when the cord comes down on it, forward.
        double[] bat = {-2.6, 3.4, 2.6, -1.4, 4.7, 3.8};
        WhipSwing swing = new WhipSwing(WhipTuning.DEFAULT, LOOK, 1);
        swing.start(HAND);
        double[] touched = {-1}, counted = {-1};
        while (swing.tick(HAND, HAND, (k, cord, tick, dt) -> {
            if (WhipTuning.DEFAULT.hitting(tick) && CordHit.test(cord, WhipTuning.DEFAULT.hitRadius(), bat) != null) {
                if (touched[0] < 0) {
                    touched[0] = tick;
                }
                if (counted[0] < 0 && swing.forward(k, cord, tick)) {
                    counted[0] = tick;
                    WhipVec v = cord.points[cord.segments].sub(cord.before[cord.segments]);
                    assertTrue(v.dot(LOOK) > 0, "counted while the tip went back");
                }
            }
        })) {
            // keep swinging
        }
        assertTrue(touched[0] >= 0, "the test box is not in the backswing's path any more; move it");
        assertTrue(counted[0] > touched[0], "the whip-back counted: touched " + touched[0] + ", counted " + counted[0]);
    }

    @Test
    void shortMobDeadAheadIsHitOnBothSides() {
        // Aimed from the hand at the crosshair point (Whips.crosshairAim): a chicken 3 blocks ahead, looked at.
        WhipVec eye = new WhipVec(0, 1.62, 0);
        double[] chicken = {-0.2, 0, 2.8, 0.2, 0.7, 3.2};
        WhipVec look = new WhipVec(0, 0.35, 3).sub(eye).normalize();
        double toFace = (2.8 - eye.z()) / look.z();
        WhipVec aim = eye.addScaled(look, toFace).sub(HAND).normalize();
        for (int side : new int[] {1, -1}) {
            WhipSwing swing = new WhipSwing(WhipTuning.DEFAULT, aim, side);
            swing.start(HAND, FLOOR);
            boolean[] hit = {false};
            while (swing.tick(HAND, HAND, FLOOR, (k, cord, tick, dt) -> {
                if (WhipTuning.DEFAULT.hitting(tick) && swing.forward(k, cord, tick)) {
                    CordHit.Contact c = CordHit.test(cord, WhipTuning.DEFAULT.hitRadius(), chicken);
                    hit[0] |= c != null && CordHit.distance(c.point(), chicken) <= WhipTuning.DEFAULT.hitRadius();
                }
            })) {
                // keep swinging
            }
            assertTrue(hit[0], "a chicken dead ahead was missed on side " + side);
        }
    }

    /**
     * After a swing the held whip's cord carries on from where the swing left it and draws in to the idle length over
     * ten ticks (WhipsClient's Idle), instead of jumping to a fresh cord hanging straight down. The tip still swings
     * down fast (a long cord falling), but its speed changes from tick to tick by less than half what the jump did, and
     * the cord ends at the idle length.
     */
    @Test
    void cordDrawsBackInAfterASwing() {
        WhipTuning t = WhipTuning.DEFAULT;
        double idleLength = 0.35;
        WhipSwing swing = new WhipSwing(t, LOOK, 1);
        swing.start(HAND);
        WhipVec[] tip = {swing.cords[0].tip()};
        double[] lastMove = {0};
        while (swing.tick(HAND, HAND, (k, cord, tick, dt) -> {})) {
            lastMove[0] = swing.cords[0].tip().distanceTo(tip[0]);
            tip[0] = swing.cords[0].tip();
        }
        WhipCord cord = swing.cords[0];
        double jump = Math.abs(cord.tip().distanceTo(HAND.add(new WhipVec(0, -t.length() * idleLength, 0))) - lastMove[0]);
        WhipVec reach = cord.points[0].sub(HAND);
        WhipVec reachDir = cord.points[1].sub(cord.points[0]).normalize();
        WhipVec hang = new WhipVec(0, -1, 0);
        double fullRest = cord.rest, idleRest = cord.rest * idleLength;
        double worst = 0, move = lastMove[0];
        int n = 4, ticks = 10;
        for (int tick = 0; tick < ticks + 20; tick++) {
            WhipVec tipBefore = cord.tip();
            for (int i = 1; i <= n; i++) {
                double f = Math.min(1.0, (tick + (double) i / n) / ticks);
                f = f * f * (3.0 - 2.0 * f);
                cord.rest = fullRest + (idleRest - fullRest) * f;
                cord.step(HAND.addScaled(reach, 1.0 - f), reachDir.lerp(hang, f).normalize(hang), 0.05 / n, WhipCord.IDLE_GUIDE,
                    t.lag(), t.stiffness(), Math.pow(0.86, 1.0 / n), t.gravity(), null);
            }
            double now = cord.tip().distanceTo(tipBefore);
            worst = Math.max(worst, Math.abs(now - move));
            move = now;
        }
        double len = 0;
        for (int i = 0; i < cord.segments; i++) {
            len += cord.points[i].distanceTo(cord.points[i + 1]);
        }
        System.out.println("return: old jump " + jump + ", worst change " + worst + ", length " + len);
        assertTrue(worst < jump * 0.5, "the tip's speed changed by " + worst + " in a tick; the old jump changed it by " + jump);
        assertEquals(t.length() * idleLength, len, 0.05);
    }
}
