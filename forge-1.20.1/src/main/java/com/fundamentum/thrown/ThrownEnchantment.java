package com.fundamentum.thrown;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

/**
 * Hurling, Farflight and Ricochet: enchantments with the costs, weight and levels of their 1.21 data files. 1.20.1
 * enchantments are code, so these are classes; what they do is attribute modifiers on the held item, added by
 * {@link ThrownWeapons} through Forge's item attribute event (1.21 writes the same modifiers in the enchantment file).
 */
public class ThrownEnchantment extends Enchantment {
    private final int maxLevel;
    private final int minBase;
    private final int minPerLevel;
    private final int maxBase;
    private final int maxPerLevel;

    public ThrownEnchantment(Rarity rarity, EnchantmentCategory category, int maxLevel, int minBase, int minPerLevel, int maxBase, int maxPerLevel) {
        super(rarity, category, new EquipmentSlot[] {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND});
        this.maxLevel = maxLevel;
        this.minBase = minBase;
        this.minPerLevel = minPerLevel;
        this.maxBase = maxBase;
        this.maxPerLevel = maxPerLevel;
    }

    @Override
    public int getMaxLevel() {
        return maxLevel;
    }

    @Override
    public int getMinCost(int level) {
        return minBase + (level - 1) * minPerLevel;
    }

    @Override
    public int getMaxCost(int level) {
        return maxBase + (level - 1) * maxPerLevel;
    }
}
