package com.fundamentum.transfer;

/**
 * A plain energy buffer to keep in a block entity and expose with {@link Transfer#exposeEnergy}. Outside callers
 * ({@link #insert}, {@link #extract}, other mods through the loader capability) are held to the per-call limits; the
 * owner's own {@link #add} and {@link #consume} ignore them. Save {@link #stored()} with the block entity and put it back
 * with {@link #set}. {@link #onChange} runs after every real change (mark the block entity dirty there).
 */
public class EnergyBuffer implements EnergyStore {
    private int energy;
    private int capacity;
    private int maxInsert;
    private int maxExtract;
    private Runnable listener = () -> { };

    public EnergyBuffer(int capacity) {
        this(capacity, capacity, capacity);
    }

    public EnergyBuffer(int capacity, int maxInsert, int maxExtract) {
        if (capacity < 0 || maxInsert < 0 || maxExtract < 0) throw new IllegalArgumentException("negative energy limit");
        this.capacity = capacity;
        this.maxInsert = maxInsert;
        this.maxExtract = maxExtract;
    }

    /** Runs after every real change. Replaces the previous listener. */
    public EnergyBuffer onChange(Runnable listener) {
        this.listener = listener == null ? () -> { } : listener;
        return this;
    }

    @Override
    public int stored() {
        return energy;
    }

    @Override
    public int capacity() {
        return capacity;
    }

    public int maxInsert() {
        return maxInsert;
    }

    public int maxExtract() {
        return maxExtract;
    }

    /** Changes the size; energy above the new size is lost. */
    public void setCapacity(int capacity) {
        this.capacity = Math.max(0, capacity);
        if (energy > this.capacity) set(this.capacity);
    }

    public void setLimits(int maxInsert, int maxExtract) {
        this.maxInsert = Math.max(0, maxInsert);
        this.maxExtract = Math.max(0, maxExtract);
    }

    /** Filled share, 0 to 1. */
    public float fraction() {
        return capacity <= 0 ? 0 : (float) energy / capacity;
    }

    @Override
    public int insert(int amount, boolean simulate) {
        return change(Math.min(Math.max(0, amount), maxInsert), simulate);
    }

    @Override
    public int extract(int amount, boolean simulate) {
        return -change(-Math.min(Math.max(0, amount), maxExtract), simulate);
    }

    /** The owner adding energy (a generator), with no per-call limit. Returns how much went in. */
    public int add(int amount) {
        return change(Math.max(0, amount), false);
    }

    /** The owner using energy (a machine), with no per-call limit. Returns how much was used. */
    public int consume(int amount) {
        return -change(-Math.max(0, amount), false);
    }

    /** Uses exactly {@code amount} if that much is stored, else nothing. */
    public boolean tryConsume(int amount) {
        if (amount < 0 || energy < amount) return false;
        consume(amount);
        return true;
    }

    /** Sets the stored energy, clamped to the capacity. */
    public void set(int amount) {
        int v = Math.max(0, Math.min(capacity, amount));
        if (v == energy) return;
        energy = v;
        changed();
    }

    /** Sets the stored energy without running the listener. For loader adapters that undo work; mods use {@link #set}. */
    public void restore(int amount) {
        energy = Math.max(0, Math.min(capacity, amount));
    }

    /** Runs the change listener. */
    public void changed() {
        listener.run();
    }

    private int change(int delta, boolean simulate) {
        int v = (int) Math.max(0, Math.min((long) capacity, (long) energy + delta));
        int moved = v - energy;
        if (!simulate && moved != 0) {
            energy = v;
            changed();
        }
        return moved;
    }
}
