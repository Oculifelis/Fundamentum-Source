package com.fundamentum.mixin;

import com.fundamentum.glow.Glows;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Glowing outline in the colour {@link Glows} set for the entity, while it has one (client side). Named twice (dev name and SRG name, unremapped) so Fundamentum needs no refmap. */
@Mixin(Entity.class)
public abstract class EntityGlowMixin {
    @Inject(method = {"getTeamColor", "m_19876_"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void fundamentum$glowColour(CallbackInfoReturnable<Integer> cir) {
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide()) {
            int c = Glows.colour(self);
            if (c >= 0) {
                cir.setReturnValue(c);
            }
        }
    }
}
