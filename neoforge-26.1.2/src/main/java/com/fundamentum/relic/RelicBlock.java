package com.fundamentum.relic;

import com.mojang.serialization.MapCodec;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * A boss relic ({@link Relics}): the base (the block model, or a model layer the renderer draws) with the boss's gold
 * likeness floating, bobbing and slowly turning over it. It faces the player who placed it; a redstone signal stops the
 * turn and eases the likeness round to face that way too ({@link #POWERED}, {@link RelicSpin}). A grand relic is this
 * block at the centre of a 3 x 3 with eight {@link RelicPartBlock}s round it: it is placed only where the whole 3 x 3 and
 * the layer above it are clear, and breaking any of the nine breaks all of them, dropping one relic (this block's loot
 * table, which the owning mod ships; the fillers have none). A signal into any of the nine counts.
 */
public class RelicBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    private final RelicDef def;
    private final @Nullable Supplier<? extends RelicPartBlock> part;
    private final RelicSet set;
    private final VoxelShape shape;
    private final MapCodec<RelicBlock> codec;

    public RelicBlock(Properties props, RelicDef def, @Nullable Supplier<? extends RelicPartBlock> part, RelicSet set) {
        super(props);
        this.def = def;
        this.part = part;
        this.set = set;
        this.shape = shape(def.shape);
        this.codec = simpleCodec(p -> new RelicBlock(p, def, part, set));
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWERED, false));
    }

    static VoxelShape shape(java.util.List<float[]> boxes) {
        VoxelShape s = Shapes.empty();
        for (float[] b : boxes) {
            s = Shapes.or(s, Block.box(b[0], b[1], b[2], b[3], b[4], b[5]));
        }
        return s.isEmpty() ? Shapes.block() : s;
    }

    /** The declaration this block was registered with (what {@link Relics#def} falls back on). */
    public RelicDef registered() {
        return this.def;
    }

    public RelicSet set() {
        return this.set;
    }

    /** Whether it is a grand relic, three blocks across. */
    public boolean grand() {
        return this.def.grand();
    }

    /** The boss this relic shows (an entity id). */
    public String boss() {
        return this.def.boss;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return this.codec;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, POWERED);
    }

    /** Whether a grand relic centred on {@code centre} fits: the eight blocks round it and the nine above all clear. */
    public static boolean fits(LevelReader level, BlockPos centre) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if ((dx != 0 || dz != 0 || dy != 0) && !level.getBlockState(centre.offset(dx, dy, dz)).canBeReplaced()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Whether a redstone signal reaches the relic (any of a grand relic's nine blocks). */
    public boolean signal(Level level, BlockPos pos) {
        if (!this.grand()) {
            return level.hasNeighborSignal(pos);
        }
        for (int i = 0; i < 9; i++) {
            if (level.hasNeighborSignal(pos.offset(RelicPartBlock.dx(i), 0, RelicPartBlock.dz(i)))) {
                return true;
            }
        }
        return false;
    }

    /** Sets {@link #POWERED} from the signal it gets now. */
    void updatePower(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide()) {
            return;
        }
        boolean on = this.signal(level, pos);
        if (on != state.getValue(POWERED)) {
            level.setBlock(pos, state.setValue(POWERED, on), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        if (this.grand() && !fits(ctx.getLevel(), ctx.getClickedPos())) {
            if (ctx.getPlayer() != null && ctx.getLevel().isClientSide()) {
                String own = this.getDescriptionId() + ".no_room";
                ctx.getPlayer().sendOverlayMessage(Language.getInstance().has(own) ? Component.translatable(own)
                    : Component.translatable("message.fundamentum.relic_no_room", Component.translatable(this.getDescriptionId())));
            }
            return null;
        }
        return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite())
            .setValue(POWERED, this.signal(ctx.getLevel(), ctx.getClickedPos()));
    }

    /** A grand relic sets its eight fillers round it. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (this.grand() && this.part != null && !level.isClientSide()) {
            for (int i = 0; i < 9; i++) {
                if (i != RelicPartBlock.CENTRE) {
                    level.setBlock(pos.offset(RelicPartBlock.dx(i), 0, RelicPartBlock.dz(i)),
                        this.part.get().defaultBlockState().setValue(RelicPartBlock.PART, i), Block.UPDATE_ALL);
                }
            }
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean movedByPiston) {
        super.onPlace(state, level, pos, old, movedByPiston);
        this.updatePower(level, pos, state);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block from, @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, from, orientation, movedByPiston);
        this.updatePower(level, pos, state);
    }

    /** A grand relic takes its fillers with it (they drop nothing; the relic drops itself once). */
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        if (this.grand()) {
            for (int i = 0; i < 9; i++) {
                BlockPos at = pos.offset(RelicPartBlock.dx(i), 0, RelicPartBlock.dz(i));
                if (i != RelicPartBlock.CENTRE && level.getBlockState(at).getBlock() instanceof RelicPartBlock
                    && pos.equals(RelicPartBlock.centre(level, at, level.getBlockState(at), pos))) {
                    level.removeBlock(at, false);
                }
            }
        }
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return this.def.base.layer == null ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return this.shape;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RelicBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, this.set.entity().get(), RelicBlockEntity::tick);
    }
}
