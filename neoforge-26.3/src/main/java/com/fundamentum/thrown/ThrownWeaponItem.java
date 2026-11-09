package com.fundamentum.thrown;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * A thrown weapon item: use throws it as the generic {@link ThrownWeapons#THROWN_WEAPON}, which flies by the item's
 * {@link ThrownStats}. Tag it {@code #fundamentum:thrown} (a returning one {@code #fundamentum:boomerangs}) so it is in
 * the thrower class and takes the thrown enchantments. For your own effects, override {@link #use} and call
 * {@link ThrownWeapons#throwFrom} with your own {@link ThrownWeapon} subclass.
 */
public class ThrownWeaponItem extends Item {
    public ThrownWeaponItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        return ThrownWeapons.throwFrom(level, player, hand, (l, stack) -> new ThrownWeapon(ThrownWeapons.THROWN_WEAPON.get(), l));
    }
}
