package com.fundamentum.relic;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The anchor of a relic stand ({@link RelicBases}): one block per base and size, the same for every relic (which relic it
 * is, is its {@link RelicBlockEntity}'s data). A stand {@code n} blocks across covers an n x n footprint: odd sizes centre
 * on the anchor; for even sizes the anchor is the near-left cell of the middle 2 x 2 and the stand grows away from the
 * player and to their right. The other cells hold {@link RelicPartBlock} fillers, and the layer above the footprint must be
 * clear. Breaking any cell breaks the stand, which drops its relic once (nothing for an unknown relic). It faces the player
 * who placed it; a redstone signal into any cell stops the likeness turning ({@link #POWERED}, {@link RelicSpin}).
 */
public class RelicBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    /** The relic's light, set when it is placed. */
    public static final IntegerProperty LIGHT = IntegerProperty.create("light", 0, 15);

    private final FId base;
    private final int size;
    private @Nullable VoxelShape shape;

    public RelicBlock(Properties props, FId base, int size) {
        super(props);
        this.base = base;
        this.size = size;
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWERED, false).setValue(LIGHT, 0));
    }

    /** The base this anchor belongs to. */
    public FId base() {
        return this.base;
    }

    /** How many blocks across the stand is (1 to 5). */
    public int size() {
        return this.size;
    }

    /** How this base looks at this size, before a relic's own overrides ({@link RelicBases#look}). */
    public RelicBases.Look look() {
        RelicBases.Base b = RelicBases.get(this.base);
        return b != null ? b.look(this.size) : RelicBases.Look.block(8F);
    }

    public boolean grand() {
        return this.size > 1;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, POWERED, LIGHT);
    }

    /** The first footprint offset for {@code n} blocks across (the last is this plus n - 1). */
    public static int low(int n) {
        return -((n - 1) / 2);
    }

    /** Every cell of an {@code n}-wide stand anchored at {@code anchor} facing {@code facing}, the anchor first. */
    public static List<BlockPos> cells(BlockPos anchor, Direction facing, int n) {
        Direction look = facing.getOpposite();
        Direction right = look.getClockWise();
        List<BlockPos> out = new ArrayList<>();
        out.add(anchor);
        for (int i = low(n); i < low(n) + n; i++) {
            for (int j = low(n); j < low(n) + n; j++) {
                if (i != 0 || j != 0) {
                    out.add(anchor.relative(right, i).relative(look, j));
                }
            }
        }
        return out;
    }

    /** Where the stand's middle is, from the anchor block's centre, in blocks (x, z): zero for odd sizes. */
    public static double[] centre(Direction facing, int n) {
        if (n % 2 == 1) {
            return new double[] {0, 0};
        }
        Direction look = facing.getOpposite();
        Direction right = look.getClockWise();
        return new double[] {0.5 * (right.getStepX() + look.getStepX()), 0.5 * (right.getStepZ() + look.getStepZ())};
    }

    /** Whether a stand fits: every cell but the anchor and the layer above all of them can be replaced. */
    public static boolean fits(LevelReader level, BlockPos anchor, Direction facing, int n) {
        for (BlockPos c : cells(anchor, facing, n)) {
            if (!c.equals(anchor) && !level.getBlockState(c).canBeReplaced()) {
                return false;
            }
            if (!level.getBlockState(c.above()).canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    /** Whether a redstone signal reaches any cell of the stand. */
    public boolean signal(Level level, BlockPos pos, Direction facing) {
        for (BlockPos c : cells(pos, facing, this.size)) {
            if (level.hasNeighborSignal(c)) {
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
        boolean on = this.signal(level, pos, state.getValue(FACING));
        if (on != state.getValue(POWERED)) {
            level.setBlock(pos, state.setValue(POWERED, on), Block.UPDATE_CLIENTS);
        }
    }

    /** Placement for the relic in the player's hand: null (with a message) when it does not fit or a listener cancels it. */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        FId id = Relics.RELIC.has(ctx.getItemInHand()) ? Relics.RELIC.get(ctx.getItemInHand()) : null;
        RelicDef def = id == null ? null : Relics.def(id.toString());
        if (def == null) {
            return null;
        }
        Direction facing = ctx.getHorizontalDirection().getOpposite();
        Level level = ctx.getLevel();
        if (!fits(level, ctx.getClickedPos(), facing, this.size)) {
            if (ctx.getPlayer() != null && level.isClientSide()) {
                ctx.getPlayer().sendOverlayMessage(Component.translatable("message.fundamentum.relic_no_room", Relics.name(id), this.size, this.size));
            }
            return null;
        }
        if (!level.isClientSide() && ctx.getPlayer() instanceof ServerPlayer sp && FEvents.post(new Events.RelicPlaced(sp, id, ctx.getClickedPos()))) {
            return null;
        }
        return this.defaultBlockState().setValue(FACING, facing).setValue(LIGHT, Math.max(0, Math.min(15, def.light)))
            .setValue(POWERED, this.signal(level, ctx.getClickedPos(), facing));
    }

    /** Takes the relic from the stack and sets the fillers round it. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (level.getBlockEntity(pos) instanceof RelicBlockEntity be && Relics.RELIC.has(stack)) {
            be.setRelic(Relics.RELIC.get(stack));
        }
        if (level.isClientSide() || this.size == 1) {
            return;
        }
        RelicBases.Base b = RelicBases.get(this.base);
        if (b == null) {
            return;
        }
        for (BlockPos c : cells(pos, state.getValue(FACING), this.size)) {
            if (!c.equals(pos)) {
                level.setBlock(c, b.filler().at(pos.getX() - c.getX(), pos.getZ() - c.getZ()), Block.UPDATE_ALL);
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

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level.getBlockEntity(pos) instanceof RelicBlockEntity be && player instanceof ServerPlayer sp) {
            be.breaker(sp);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** The stand takes its fillers with it (they drop nothing; the anchor drops the relic once). */
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        if (this.size > 1) {
            for (BlockPos c : cells(pos, state.getValue(FACING), this.size)) {
                BlockState s = level.getBlockState(c);
                if (!c.equals(pos) && s.getBlock() instanceof RelicPartBlock && pos.equals(RelicPartBlock.anchor(c, s))) {
                    level.removeBlock(c, false);
                }
            }
        }
    }

    /** The relic it holds, or nothing for an unknown one. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        BlockEntity be = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        ItemStack stack = be instanceof RelicBlockEntity r ? Relics.stackOf(r) : ItemStack.EMPTY;
        return stack.isEmpty() ? List.of() : List.of(stack);
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        return level.getBlockEntity(pos) instanceof RelicBlockEntity r ? Relics.stackOf(r) : ItemStack.EMPTY;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return this.look().layer() == null ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    /** A one-block stand is the pedestal's own outline; wider ones a half-block floor (the figure floats above it). */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        if (this.shape == null) {
            this.shape = this.size == 1 ? Block.box(1, 0, 1, 15, Math.max(2, Math.min(16, this.look().top())), 15) : RelicPartBlock.FLOOR;
        }
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
        return createTickerHelper(type, Relics.ENTITY.get(), RelicBlockEntity::tick);
    }
}
