package com.fundamentum.mixin;

import com.fundamentum.soulfire.SoulFireState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries "draw these flames as soul fire" from the entity to the flame renderer. */
@Mixin(EntityRenderState.class)
public abstract class SoulFireRenderStateMixin implements SoulFireState {
    @Unique
    private boolean fundamentum$soul;

    @Override
    public boolean fundamentum$soulFire() {
        return this.fundamentum$soul;
    }

    @Override
    public void fundamentum$setSoulFire(boolean soul) {
        this.fundamentum$soul = soul;
    }
}
