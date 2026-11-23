package com.fundamentum.client;

import java.util.List;

/**
 * When Fundamentum's tooltip lines fold behind "Hold [Shift] for details" (1.25.3). Pure, so it is unit tested
 * ({@code TooltipRuleTest}); {@link Tooltips} feeds it each stack's facts.
 * <ul>
 * <li>Tag {@code fundamentum:tooltip_always} shows everything, tag {@code fundamentum:tooltip_fold} folds (always wins
 * if an item has both).</li>
 * <li>Gear folds: weapons, tools, armour and anything equippable or worn in a Curios slot, whips, hooks, summons and
 * ammo. So does anything with stat lines.</li>
 * <li>Otherwise an item whose whole Fundamentum tooltip is one line of at most {@value #SHORT} characters stays
 * visible with no hint (a crafting part whose line is the whole item); longer text folds.</li>
 * </ul>
 */
public final class TooltipRule {
    /** The longest single line that stays visible. */
    public static final int SHORT = 60;

    /**
     * @param lines the plain text of the lines Fundamentum would add (descriptions, stats, other mods' details)
     * @param stats whether any of them is a stat line (whip, hook, element affinity)
     * @param gear whether the item is gear: weapon, tool, armour, equippable, curio, whip, hook, summon or ammo
     */
    public static boolean folds(List<String> lines, boolean stats, boolean gear, boolean tagAlways, boolean tagFold) {
        if (lines.isEmpty() || tagAlways) {
            return false;
        }
        if (tagFold || stats || gear) {
            return true;
        }
        return lines.size() > 1 || lines.get(0).length() > SHORT;
    }

    private TooltipRule() {}
}
