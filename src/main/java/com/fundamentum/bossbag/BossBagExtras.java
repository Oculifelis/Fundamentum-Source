package com.fundamentum.bossbag;

import java.util.List;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

/**
 * A boss whose fall adds to its bags beyond its loot table (a phase it died in, a part it lost). When its bags are left,
 * {@link #bagState()} is written on each bag; opening one asks a fresh body of the boss for {@link #bagExtras} with it.
 */
public interface BossBagExtras {
    /** How the boss fell, written on each of its bags. */
    int bagState();

    /** What a bag of this boss adds for {@code state}, besides its loot table roll. */
    List<ItemStack> bagExtras(RandomSource random, int state);
}
