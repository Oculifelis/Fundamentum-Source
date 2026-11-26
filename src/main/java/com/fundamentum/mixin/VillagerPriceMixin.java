package com.fundamentum.mixin;

import com.fundamentum.faction.Factions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Faction rank prices: after vanilla sets a villager's special prices for a player, {@link Factions#applyPrices} adds its own. */
@Mixin(Villager.class)
public abstract class VillagerPriceMixin {
    @Inject(method = "updateSpecialPrices", at = @At("TAIL"))
    private void fundamentum$factionPrices(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer p) Factions.applyPrices((Villager) (Object) this, p);
    }
}
