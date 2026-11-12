package com.fundamentum.mixin;

import com.fundamentum.multiblock.client.MultiblockProjector;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the multiblock ghost preview when a block changes on the client, so it redraws only then. */
@Mixin(ClientLevel.class)
public abstract class GhostBlockMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void fundamentum$ghostBlockChanged(BlockPos pos, BlockState old, BlockState now, int flags, CallbackInfo ci) {
        MultiblockProjector.blockChanged(pos);
    }
}
