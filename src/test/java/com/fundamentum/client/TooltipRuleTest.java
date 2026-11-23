package com.fundamentum.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Which Fundamentum tooltips fold behind "Hold [Shift] for details" (1.25.3). */
class TooltipRuleTest {
    static final String SHORT = "Smith it onto a diamond pickaxe.";
    static final String LONG = "Every hit heals you half a heart, a tip hit a full heart, and then some more.";

    @Test
    void nothingToFold() {
        assertFalse(TooltipRule.folds(List.of(), true, true, false, true));
    }

    @Test
    void oneShortLineOnAPlainItemStays() {
        assertFalse(TooltipRule.folds(List.of(SHORT), false, false, false, false));
        assertFalse(TooltipRule.folds(List.of("x".repeat(TooltipRule.SHORT)), false, false, false, false));
    }

    @Test
    void longOrSeveralLinesFold() {
        assertTrue(TooltipRule.folds(List.of("x".repeat(TooltipRule.SHORT + 1)), false, false, false, false));
        assertTrue(TooltipRule.folds(List.of(LONG), false, false, false, false));
        assertTrue(TooltipRule.folds(List.of(SHORT, SHORT), false, false, false, false));
    }

    @Test
    void gearAndStatsAlwaysFold() {
        assertTrue(TooltipRule.folds(List.of(SHORT), false, true, false, false), "weapon, whip, hook, summon, ammo, curio");
        assertTrue(TooltipRule.folds(List.of(SHORT), true, false, false, false), "stat lines");
    }

    @Test
    void tagsOverride() {
        assertFalse(TooltipRule.folds(List.of(SHORT, LONG), true, true, true, false), "tooltip_always");
        assertTrue(TooltipRule.folds(List.of(SHORT), false, false, false, true), "tooltip_fold");
        assertFalse(TooltipRule.folds(List.of(SHORT), false, false, true, true), "always wins over fold");
    }
}
