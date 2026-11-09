package com.fundamentum.shield;

import net.minecraftforge.fml.ModList;

/** Better Combat check for the shield (its swing and attack cooldown are left alone when it is loaded). */
final class ShieldCompat {
    private static Boolean betterCombat;

    static boolean betterCombat() {
        if (betterCombat == null) {
            betterCombat = ModList.get() != null && ModList.get().isLoaded("bettercombat");
        }
        return betterCombat;
    }

    private ShieldCompat() {}
}
