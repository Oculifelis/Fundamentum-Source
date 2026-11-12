package com.fundamentum.relic;

import com.fundamentum.port.FId;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * An invisible filler in a wide relic stand ({@link RelicBlock}): it holds the stand's floor so nothing else is placed
 * inside it. {@link #DX} and {@link #DZ} are the way to the anchor, plus 2. Breaking it breaks the stand (the anchor drops
 * the relic once; nothing in creative), and a signal into it reaches the stand.
 */
public class RelicPartBlock extends Block {
    public static final IntegerProperty DX = IntegerProperty.create("dx", 0, 4);
    public static final IntegerProperty DZ = IntegerProperty.create("dz", 0, 4);
    /** A half-block floor. */
    public static final VoxelShape FLOOR = Block.box(0, 0, 0, 16, 8, 16);

    private final FId base;
    private final MapCodec<RelicPartBlock> codec;

    public RelicPartBlock(Properties props, FId base) {
        super(props);
        this.base = base;
        this.codec = simpleCodec(p -> new RelicPartBlock(p, base));
        this.registerDefaultState(this.stateDefinition.any().setValue(DX, 2).setValue(DZ, 2));
    }

    public FId base() {
        return this.base;
    }

    /** This filler pointing at an anchor {@code dx}, {@code dz} blocks away (each -2 to 2). */
    public BlockState at(int dx, int dz) {
        return this.defaultBlockState().setValue(DX, dx + 2).setValue(DZ, dz + 2);
    }

    /** The anchor a filler state at {@code pos} points at. */
    public static BlockPos anchor(BlockPos pos, BlockState state) {
        return pos.offset(state.getValue(DX) - 2, 0, state.getValue(DZ) - 2);
    }

    private static @Nullable BlockPos anchorIn(BlockGetter level, BlockPos pos, BlockState state) {
        BlockPos a = anchor(pos, state);
        return level.getBlockState(a).getBlock() instanceof RelicBlock ? a : null;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return this.codec;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(DX, DZ);
    }

    /** In creative the stand goes without a drop. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        BlockPos a = anchorIn(level, pos, state);
        if (a != null && !level.isClientSide()) {
            if (level.getBlockEntity(a) instanceof RelicBlockEntity be && player instanceof ServerPlayer sp) {
                be.breaker(sp);
            }
            if (player.preventsBlockDrops()) {
                level.removeBlock(a, false);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** However it went (a pick, an explosion), the stand goes with it and drops its relic once. */
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        BlockPos a = anchorIn(level, pos, state);
        if (a != null) {
            level.destroyBlock(a, true);
        }
    }

    /** A signal into a filler reaches the stand. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block from, @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, from, orientation, movedByPiston);
        BlockPos a = anchorIn(level, pos, state);
        if (a != null && level.getBlockState(a).getBlock() instanceof RelicBlock r) {
            r.updatePower(level, a, level.getBlockState(a));
        }
    }

    /** Picking a filler gives the stand's relic. */
    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        BlockPos a = anchorIn(level, pos, state);
        return a != null && level.getBlockEntity(a) instanceof RelicBlockEntity r ? Relics.stackOf(r) : ItemStack.EMPTY;
    }

    /** A turned structure turns the way to the anchor with it. */
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        int x = state.getValue(DX) - 2;
        int z = state.getValue(DZ) - 2;
        for (int i = 0; i < rotation.ordinal(); i++) {
            int t = x;
            x = -z;
            z = t;
        }
        return state.setValue(DX, x + 2).setValue(DZ, z + 2);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return FLOOR;
    }
}
