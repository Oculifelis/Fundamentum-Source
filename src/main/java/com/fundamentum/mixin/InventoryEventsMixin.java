package com.fundamentum.mixin;

import com.fundamentum.port.FLife;
import net.minecraft.advancements.triggers.InventoryChangeTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells {@link FLife#inventoryChanged} listeners whenever the game reports a change to a player's inventory. */
@Mixin(InventoryChangeTrigger.class)
public abstract class InventoryEventsMixin {
    @Inject(method = "trigger(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
    private void fundamentum$inventoryChanged(ServerPlayer player, Inventory inventory, ItemStack changed, CallbackInfo ci) {
        FLife.fireInventoryChanged(player);
    }
}
