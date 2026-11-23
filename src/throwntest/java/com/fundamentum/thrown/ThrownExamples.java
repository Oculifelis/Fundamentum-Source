package com.fundamentum.thrown;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The example boomerang and throwing knife of the thrown weapon test (-Pthrowntest only; this folder never goes into a
 * jar). Fundamentum itself registers no items: a mod registers its own and gives them stats with
 * {@link ThrownWeapons#register} or data.
 */
public final class ThrownExamples {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("fundamentum");

    /** Called by {@link ThrownWeapons#init} when this class is on the class path. */
    public static void init(IEventBus modBus) {
        ThrownWeapons.EXAMPLE_BOOMERANG = ITEMS.registerItem("example_boomerang", ThrownWeaponItem::new, new Item.Properties().stacksTo(1).durability(250));
        ThrownWeapons.EXAMPLE_KNIFE = ITEMS.registerItem("example_knife", ThrownWeaponItem::new, new Item.Properties().stacksTo(16));
        ITEMS.register(modBus);
        ThrownWeapons.register(ThrownWeapons.EXAMPLE_BOOMERANG.getId(), ThrownStats.BOOMERANG);
        ThrownWeapons.register(ThrownWeapons.EXAMPLE_KNIFE.getId(), new ThrownStats(1.6, 0.03, 0, 3, 1, true, false, true, 200, null));
    }

    private ThrownExamples() {}
}
