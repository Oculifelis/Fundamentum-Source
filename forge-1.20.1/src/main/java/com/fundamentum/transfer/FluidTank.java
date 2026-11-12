package com.fundamentum.transfer;

import com.fundamentum.port.FId;
import com.fundamentum.port.FStreamCodec;
import com.fundamentum.port.FTransfer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.function.Predicate;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * One fluid tank: a fluid, an amount in millibuckets and a capacity, with an optional filter and switches for whether
 * outside callers may fill or drain it. The owner's {@link #fill} and {@link #drain} ignore the switches. Save
 * {@link #contents()} with {@link Contents#CODEC} and put it back with {@link #load}. Expose it with
 * {@link Transfer#exposeTank}, show it on clients with {@link Transfer#track} and the client tank widget.
 */
public class FluidTank implements FluidStore {
    /** A tank's saved state. {@code fluid} is the fluid's id ({@code minecraft:empty} when empty). */
    public record Contents(FId fluid, int amount) {
        public static final FId EMPTY_ID = FId.of("minecraft", "empty");
        public static final Contents EMPTY = new Contents(EMPTY_ID, 0);
        public static final Codec<Contents> CODEC = RecordCodecBuilder.create(i -> i.group(
            FId.CODEC.fieldOf("fluid").forGetter(Contents::fluid),
            Codec.INT.optionalFieldOf("amount", 0).forGetter(Contents::amount)
        ).apply(i, Contents::new));
        public static final FStreamCodec<Contents> STREAM_CODEC = FStreamCodec.composite(
            FStreamCodec.ID, Contents::fluid, FStreamCodec.INT, Contents::amount, Contents::new);

        public static Contents of(Fluid fluid, int amount) {
            return fluid == null || fluid == Fluids.EMPTY || amount <= 0 ? EMPTY : new Contents(FTransfer.idOf(fluid), amount);
        }

        /** The fluid, {@link Fluids#EMPTY} when the id is unknown. */
        public Fluid toFluid() {
            return FTransfer.fluid(fluid);
        }
    }

    private Fluid fluid = Fluids.EMPTY;
    private int amount;
    private int capacity;
    private Predicate<Fluid> filter = f -> true;
    private boolean canFill = true;
    private boolean canDrain = true;
    private Runnable listener = () -> { };
    /** Set by {@link Transfer#track} so a change marks the tank for sending to clients. */
    volatile Runnable syncListener;

    public FluidTank(int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("negative tank capacity");
        this.capacity = capacity;
    }

    /** Only fluids passing this go in. */
    public FluidTank filter(Predicate<Fluid> filter) {
        this.filter = filter == null ? f -> true : filter;
        return this;
    }

    /** Whether outside callers may fill and drain it (an input tank: true, false; an output tank: false, true). */
    public FluidTank access(boolean canFill, boolean canDrain) {
        this.canFill = canFill;
        this.canDrain = canDrain;
        return this;
    }

    /** Runs after every real change. Replaces the previous listener. */
    public FluidTank onChange(Runnable listener) {
        this.listener = listener == null ? () -> { } : listener;
        return this;
    }

    public Fluid fluid() {
        return fluid;
    }

    public int amount() {
        return amount;
    }

    public int capacity() {
        return capacity;
    }

    public int space() {
        return Math.max(0, capacity - amount);
    }

    public boolean empty() {
        return amount <= 0 || fluid == Fluids.EMPTY;
    }

    public boolean canFill() {
        return canFill;
    }

    public boolean canDrain() {
        return canDrain;
    }

    /** Filled share, 0 to 1. */
    public float fraction() {
        return capacity <= 0 ? 0 : (float) amount / capacity;
    }

    /** True when this fluid may go in: it passes the filter and the tank is empty or holds the same fluid. */
    public boolean accepts(Fluid f) {
        return f != null && f != Fluids.EMPTY && filter.test(f) && (empty() || fluid == f);
    }

    /** Changes the size; fluid above the new size is lost. */
    public void setCapacity(int capacity) {
        int before = this.capacity;
        this.capacity = Math.max(0, capacity);
        if (amount > this.capacity) set(fluid, this.capacity);
        else if (before != this.capacity && syncListener != null) syncListener.run();
    }

    @Override
    public int tanks() {
        return 1;
    }

    @Override
    public Fluid fluid(int tank) {
        return tank == 0 ? fluid : Fluids.EMPTY;
    }

    @Override
    public int amount(int tank) {
        return tank == 0 ? amount : 0;
    }

    @Override
    public int capacity(int tank) {
        return tank == 0 ? capacity : 0;
    }

    @Override
    public int insert(Fluid f, int amt, boolean simulate) {
        return canFill ? fill(f, amt, simulate) : 0;
    }

    @Override
    public int extract(Fluid f, int amt, boolean simulate) {
        return canDrain && f != null ? drain(f, amt, simulate) : 0;
    }

    /** The owner filling it, ignoring {@link #access}. Returns how much went in. */
    public int fill(Fluid f, int amt, boolean simulate) {
        if (amt <= 0 || !accepts(f)) return 0;
        int n = Math.min(amt, space());
        if (n > 0 && !simulate) set(f, amount + n);
        return n;
    }

    /** The owner draining it, ignoring {@link #access}. {@code null} drains whatever it holds. Returns how much came out. */
    public int drain(Fluid f, int amt, boolean simulate) {
        if (amt <= 0 || empty() || (f != null && f != fluid)) return 0;
        int n = Math.min(amt, amount);
        if (n > 0 && !simulate) set(fluid, amount - n);
        return n;
    }

    /** Sets fluid and amount (clamped to the capacity). An amount of 0 empties it. */
    public void set(Fluid f, int amt) {
        Fluid oldFluid = fluid;
        int oldAmount = amount;
        restore(f, amt);
        if (fluid != oldFluid || amount != oldAmount) changed();
    }

    public Contents contents() {
        return Contents.of(fluid, amount);
    }

    /** Puts saved contents back (an unknown fluid empties it). Runs the listener when something changed. */
    public void load(Contents c) {
        set(c == null ? Fluids.EMPTY : c.toFluid(), c == null ? 0 : c.amount());
    }

    /** Sets the contents without running the listener. For loader adapters that undo work; mods use {@link #set}. */
    public void restore(Fluid f, int amt) {
        fluid = f == null || amt <= 0 ? Fluids.EMPTY : f;
        amount = fluid == Fluids.EMPTY ? 0 : Math.min(capacity, amt);
    }

    /** Runs the change listener. */
    public void changed() {
        listener.run();
        Runnable sync = syncListener;
        if (sync != null) sync.run();
    }
}
