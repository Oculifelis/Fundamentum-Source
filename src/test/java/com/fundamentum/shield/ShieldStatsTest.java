package com.fundamentum.shield;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The shield stats: data over code, vanilla's block sums, the parry window, the bash cone, elements, round trip. */
class ShieldStatsTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static final ShieldStats.TypeMatcher MOB = t -> t.equals("minecraft:mob_attack") || t.equals("#minecraft:is_melee");
    private static final ShieldStats.TypeMatcher ARROW = t -> t.equals("minecraft:arrow") || t.equals("#minecraft:is_projectile");

    @Test
    void vanillaShieldBlocksTheFrontInFull() {
        ShieldStats s = ShieldStats.VANILLA;
        assertEquals(5, s.blockDelayTicks());
        assertEquals(7.0, s.blockedDamage(7, Math.toRadians(30), MOB), 1e-9);
        assertEquals(7.0, s.blockedDamage(7, Math.toRadians(90), MOB), 1e-9);
        assertEquals(0.0, s.blockedDamage(7, Math.toRadians(91), MOB), 1e-9);
        assertEquals(0.0, s.blockedDamage(7, Math.PI, MOB), 1e-9);
        assertNull(s.parry());
        assertNull(s.bash());
        assertEquals(0.2, s.speedWhileBlocking());
    }

    @Test
    void itemDamageIsVanillas() {
        assertEquals(0, ShieldStats.ItemDamage.VANILLA.apply(2.9));
        assertEquals(4, ShieldStats.ItemDamage.VANILLA.apply(3));
        assertEquals(8, ShieldStats.ItemDamage.VANILLA.apply(7.5));
    }

    @Test
    void reductionsSumPerTypeAndCapAtTheHit() {
        ShieldStats s = ShieldStats.DEFAULT.withJson(json("""
            {"damage_reductions": [
              {"horizontal_blocking_angle": 60, "base": 1, "factor": 0.25},
              {"type": "#minecraft:is_projectile", "horizontal_blocking_angle": 120, "base": 0, "factor": 1}
            ]}"""));
        // melee from the front: only the first entry, 1 + 0.25 * 8
        assertEquals(3.0, s.blockedDamage(8, Math.toRadians(10), MOB), 1e-9);
        // melee at 100 degrees: outside the first
        assertEquals(0.0, s.blockedDamage(8, Math.toRadians(100), MOB), 1e-9);
        // arrow from the front: both, capped at the hit
        assertEquals(8.0, s.blockedDamage(8, Math.toRadians(10), ARROW), 1e-9);
        // arrow at 100 degrees: only the wide projectile entry
        assertEquals(8.0, s.blockedDamage(8, Math.toRadians(100), ARROW), 1e-9);
        assertEquals(120, s.maxAngle());
    }

    @Test
    void bypassTagAndSoundsCanBeSwitchedOff() {
        ShieldStats s = ShieldStats.DEFAULT.withJson(json("{\"bypassed_by\": false, \"block_sound\": \"minecraft:block.anvil.land\", \"disabled_sound\": false}"));
        assertNull(s.bypassedBy());
        assertFalse(s.bypassed(t -> true));
        assertEquals("minecraft:block.anvil.land", s.blockSound());
        assertNull(s.disabledSound());
        assertTrue(ShieldStats.DEFAULT.bypassed(t -> t.equals(ShieldStats.BYPASSES_SHIELD)));
    }

    @Test
    void stepsAndClamps() {
        ShieldStats s = ShieldStats.DEFAULT.withJson(json("{\"speed_while_blocking\": {\"multiply\": 2}, \"block_delay_seconds\": -3, \"disable_cooldown_scale\": 0}"));
        assertEquals(0.4, s.speedWhileBlocking(), 1e-9);
        assertEquals(0, s.blockDelayTicks());
        assertEquals(0, s.disableTicks(5));
        assertEquals(100, ShieldStats.DEFAULT.disableTicks(5));
        assertEquals(1.0, ShieldStats.DEFAULT.withJson(json("{\"speed_while_blocking\": 4}")).speedWhileBlocking());
    }

    @Test
    void parryWindowAndRearm() {
        ShieldStats.Parry p = ShieldStats.DEFAULT.withJson(json("{\"parry\": {\"window\": 3, \"rearm\": 10}}")).parry();
        assertTrue(p.inWindow(0));
        assertTrue(p.inWindow(3));
        assertFalse(p.inWindow(4));
        assertFalse(p.inWindow(-1));
        assertTrue(p.armed(Long.MIN_VALUE, 100));
        assertFalse(p.armed(95, 100));
        assertTrue(p.armed(90, 100));
        assertTrue(p.reflect());
        assertNull(ShieldStats.DEFAULT.withJson(json("{\"parry\": false}")).parry());
        assertNotNull(ShieldStats.VANILLA.withJson(json("{\"parry\": true}")).parry());
    }

    @Test
    void bashConeReachAndCooldown() {
        ShieldStats.Bash b = ShieldStats.Bash.DEFAULT; // 2.75 reach, 50 degrees
        assertTrue(b.inCone(2, 0, 0, 1, 0, 0));
        assertFalse(b.inCone(3, 0, 0, 1, 0, 0));
        assertTrue(b.inCone(1, 0, 1, 1, 0, 0)); // 45 degrees
        assertFalse(b.inCone(1, 0, 1.5, 1, 0, 0)); // 56 degrees
        assertFalse(b.inCone(-1, 0, 0, 1, 0, 0)); // behind
        assertTrue(b.ready(Long.MIN_VALUE, 0));
        assertFalse(b.ready(100, 120));
        assertTrue(b.ready(100, 130));
        assertEquals("sneak", b.trigger());
        assertEquals("sneak", ShieldStats.DEFAULT.withJson(json("{\"bash\": {\"trigger\": \"left_click\"}}")).bash().trigger());
        assertEquals("use", ShieldStats.DEFAULT.withJson(json("{\"bash\": {\"trigger\": \"use\", \"damage\": 6}}")).bash().trigger());
    }

    @Test
    void elementResistanceMultiplies() {
        ShieldStats s = ShieldStats.DEFAULT.withJson(json("{\"element_resistance\": {\"fundamentum:fire\": 0.5, \"fundamentum:ice\": 0.25, \"fundamentum:holy\": 3}}"));
        assertEquals(0.5, s.elementMultiplier(List.of("fundamentum:fire")), 1e-9);
        assertEquals(0.375, s.elementMultiplier(List.of("fundamentum:fire", "fundamentum:ice")), 1e-9);
        assertEquals(1.0, s.elementMultiplier(List.of("fundamentum:shadow")), 1e-9);
        assertEquals(0.0, s.elementMultiplier(List.of("fundamentum:holy")), 1e-9); // capped at full resistance
    }

    @Test
    void thrownLaysOverAndSwitchesOff() {
        ShieldStats s = ShieldStats.DEFAULT.withJson(json("{\"thrown\": {\"damage\": 6, \"returns\": {\"range\": 10}}}"));
        assertTrue(s.throwable());
        assertEquals(6, s.thrown().get("damage").getAsDouble());
        ShieldStats more = s.withJson(json("{\"thrown\": {\"throw_speed\": 2}}"));
        assertEquals(6, more.thrown().get("damage").getAsDouble());
        assertEquals(2, more.thrown().get("throw_speed").getAsDouble());
        assertFalse(s.withJson(json("{\"thrown\": false}")).throwable());
        assertTrue(ShieldStats.DEFAULT.withJson(json("{\"thrown\": true}")).throwable());
        assertFalse(ShieldStats.DEFAULT.throwable());
    }

    @Test
    void jsonRoundTrip() {
        ShieldStats s = ShieldStats.DEFAULT.withJson(json("""
            {"block_delay_seconds": 0.1, "damage_reductions": [{"type": ["minecraft:arrow"], "base": 2, "factor": 0.5}],
             "item_damage": {"threshold": 1}, "speed_while_blocking": 0.5, "can_sprint": true,
             "parry": {"window": 8}, "bash": false, "element_resistance": {"fundamentum:fire": 0.4}, "thrown": {"damage": 5}}"""));
        assertEquals(s, ShieldStats.VANILLA.withJson(s.toJson()));
        assertEquals(ShieldStats.DEFAULT, ShieldStats.VANILLA.withJson(ShieldStats.DEFAULT.toJson()));
        assertEquals(ShieldStats.VANILLA, ShieldStats.DEFAULT.withJson(ShieldStats.VANILLA.toJson()));
    }
}
