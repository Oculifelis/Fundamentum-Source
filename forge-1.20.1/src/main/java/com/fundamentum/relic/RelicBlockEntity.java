package com.fundamentum.relic;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * The anchor of a placed relic. It holds which relic stands there ({@code relic}, a relic file's id), saved as it was
 * even when that relic is gone (a removed datapack or mod): the stand then draws plain and drops nothing, and the id comes
 * back when the relic does. It also carries the likeness's turn ({@link RelicSpin}), ticked on both sides.
 */
public class RelicBlockEntity extends BlockEntity {
    private final RelicSpin spin = new RelicSpin();
    private String relic = "";
    private @Nullable ServerPlayer breaker;

    public RelicBlockEntity(BlockPos pos, BlockState state) {
        super(Relics.ENTITY.get(), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, RelicBlockEntity be) {
        RelicDef def = be.def();
        if (state.hasProperty(RelicBlock.POWERED)) {
            be.spin.tick(state.getValue(RelicBlock.POWERED), state.getValue(RelicBlock.FACING).toYRot(), level.getGameTime(),
                def != null ? def.turnTicks : RelicSpin.TURN_TICKS);
        }
    }

    public RelicSpin spin() {
        return this.spin;
    }

    /** The relic's id as saved ("" when none was set). */
    public String relicId() {
        return this.relic;
    }

    /** Sets the relic and sends it to the players watching. */
    public void setRelic(FId id) {
        this.relic = id.toString();
        this.setChanged();
        if (this.level != null && !this.level.isClientSide()) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
        }
    }

    /** The relic standing here, or null when the id is unknown (each unknown id is logged once). */
    public @Nullable RelicDef def() {
        return Relics.def(this.relic);
    }

    /** Remembers who is breaking the stand, for {@link Events.RelicBroken}. */
    void breaker(@Nullable ServerPlayer player) {
        this.breaker = player;
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.relic = tag.getString("relic");
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("relic", this.relic);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return this.saveWithoutMetadata();
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Called by the anchor as the stand goes. */
    void broken(BlockPos pos) {
        FId id = FId.tryOf(this.relic);
        if (id != null && this.level != null && !this.level.isClientSide()) {
            FEvents.post(new Events.RelicBroken(this.breaker, id, pos));
        }
    }

    /** The likeness rises well above the stand, and a wide stand spreads round its true centre. */
    @Override
    public AABB getRenderBoundingBox() {
        BlockPos p = this.getBlockPos();
        int n = this.getBlockState().getBlock() instanceof RelicBlock r ? r.size() : 1;
        net.minecraft.core.Direction facing = this.getBlockState().hasProperty(RelicBlock.FACING) ? this.getBlockState().getValue(RelicBlock.FACING) : net.minecraft.core.Direction.NORTH;
        double[] c = RelicBlock.centre(facing, n);
        double x = p.getX() + 0.5 + c[0];
        double z = p.getZ() + 0.5 + c[1];
        double r = n / 2.0 + 0.5;
        return new AABB(x - r, p.getY(), z - r, x + r, p.getY() + 2.0 + 1.7 * n, z + r);
    }
}
