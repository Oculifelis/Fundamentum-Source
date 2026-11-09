package com.fundamentum.relic;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * One of the eight invisible fillers round a grand relic's centre block ({@link RelicBlock}): it holds the base's floor so
 * nothing else is placed inside the relic. {@link #PART} is its place in the 3 x 3 (0 to 8, row by row from the north-west
 * corner; 4 is the centre). Breaking it breaks the relic: the centre drops the one relic item (none in creative), and the
 * centre takes the other fillers with it. A signal into it reaches the relic. Fillers saved before they knew their place
 * (all {@code part=0}) find their centre by looking round them.
 */
public class RelicPartBlock extends Block {
    public static final MapCodec<RelicPartBlock> CODEC = simpleCodec(RelicPartBlock::new);
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 8);
    public static final int CENTRE = 4;

    private final VoxelShape shape;

    public RelicPartBlock(Properties props) {
        this(props, RelicBlock.shape(java.util.List.of(new float[] {0, 0, 0, 16, 4.5F, 16})));
    }

    public RelicPartBlock(Properties props, VoxelShape shape) {
        super(props);
        this.shape = shape;
        this.registerDefaultState(this.stateDefinition.any().setValue(PART, 0));
    }

    public static int dx(int part) {
        return part % 3 - 1;
    }

    public static int dz(int part) {
        return part / 3 - 1;
    }

    private static boolean isRelic(BlockState s) {
        return s.getBlock() instanceof RelicBlock r && r.grand();
    }

    /**
     * The grand relic's centre for a filler at {@code pos}: where {@link #PART} says, else (an old filler) the grand relic
     * within one block; {@code fallback} when there is none.
     */
    public static @Nullable BlockPos centre(BlockGetter level, BlockPos pos, BlockState state, @Nullable BlockPos fallback) {
        int i = state.getValue(PART);
        BlockPos c = pos.offset(-dx(i), 0, -dz(i));
        if (isRelic(level.getBlockState(c))) {
            return c;
        }
        for (int j = 0; j < 9; j++) {
            BlockPos p = pos.offset(dx(j), 0, dz(j));
            if (j != CENTRE && isRelic(level.getBlockState(p))) {
                return p;
            }
        }
        return fallback;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(PART);
    }

    /** In creative the relic goes without a drop. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        BlockPos c = centre(level, pos, state, null);
        if (c != null && !level.isClientSide() && player.preventsBlockDrops()) {
            level.removeBlock(c, false);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** However it went (a pick, an explosion), the relic goes with it and drops itself once. */
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        BlockPos c = centre(level, pos, state, null);
        if (c != null) {
            level.destroyBlock(c, true);
        }
    }

    /** A signal into a filler reaches the relic. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block from, @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, from, orientation, movedByPiston);
        BlockPos c = centre(level, pos, state, null);
        if (c != null && level.getBlockState(c).getBlock() instanceof RelicBlock r) {
            r.updatePower(level, c, level.getBlockState(c));
        }
    }

    /** Picking a filler gives the relic it belongs to. */
    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        BlockPos c = centre(level, pos, state, null);
        return c == null ? ItemStack.EMPTY : new ItemStack(level.getBlockState(c).getBlock());
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return this.shape;
    }
}
