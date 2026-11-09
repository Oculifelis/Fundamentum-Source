package com.fundamentum.shield;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The four example shields of the shield test (-Pshieldtest only; this folder never goes into a jar). Fundamentum
 * itself registers no items: a mod makes its shields with {@link ShieldTypeItem} and its own registry.
 */
public final class ShieldExamples {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("fundamentum");
    public static final DeferredItem<Item> EXAMPLE_BUCKLER = example("example_buckler", 180);
    public static final DeferredItem<Item> EXAMPLE_TOWER_SHIELD = example("example_tower_shield", 520);
    public static final DeferredItem<Item> EXAMPLE_EMBER_SHIELD = example("example_ember_shield", 400);
    public static final DeferredItem<Item> EXAMPLE_ROUND_SHIELD = example("example_round_shield", 300);

    private static DeferredItem<Item> example(String name, int durability) {
        return ITEMS.registerItem(name, ShieldTypeItem::new, p -> ShieldTypeItem.properties(p).durability(durability));
    }

    /** Called by {@link Shields#init} when this class is on the class path. */
    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        ShieldSelfTest.init();
    }

    private ShieldExamples() {}
}
