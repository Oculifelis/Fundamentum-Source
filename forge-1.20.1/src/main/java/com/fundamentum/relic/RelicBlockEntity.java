package com.fundamentum.relic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * A relic's block entity: it saves nothing (the boss is the block's own); it carries the likeness's turn
 * ({@link RelicSpin}), ticked on both sides, so the renderer draws it and a signal eases it round without a jump.
 */
public class RelicBlockEntity extends BlockEntity {
    private final RelicSpin spin = new RelicSpin();

    public RelicBlockEntity(BlockPos pos, BlockState state) {
        super(((RelicBlock) state.getBlock()).set().entity().get(), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, RelicBlockEntity be) {
        if (state.getBlock() instanceof RelicBlock block) {
            be.spin.tick(state.getValue(RelicBlock.POWERED), state.getValue(RelicBlock.FACING).toYRot(), level.getGameTime(),
                Relics.def(block).turnTicks);
        }
    }

    public RelicSpin spin() {
        return this.spin;
    }

    /** The declaration it is drawn and dropped by, or null when the block is not a relic. */
    public @Nullable RelicDef def() {
        return this.getBlockState().getBlock() instanceof RelicBlock b ? Relics.def(b) : null;
    }

    /** The likeness rises well above the block, and a grand relic spreads a block and a half to each side. */
    @Override
    public AABB getRenderBoundingBox() {
        AABB box = new AABB(this.getBlockPos());
        return this.grand() ? box.inflate(1.5, 0.0, 1.5).expandTowards(0.0, 4.5, 0.0) : box.inflate(0.25, 0.0, 0.25).expandTowards(0.0, 2.0, 0.0);
    }

    public boolean grand() {
        return this.getBlockState().getBlock() instanceof RelicBlock b && b.grand();
    }
}
