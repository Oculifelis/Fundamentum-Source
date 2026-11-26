package com.fundamentum.transfer;

import com.fundamentum.port.FId;
import com.mojang.serialization.Codec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A mod's own essentia container: a total capacity shared by every aspect, an optional limit on how many aspects it holds
 * at once (1 for a jar), a filter, and insert/extract access for outsiders. The owner's {@link #add} and {@link #take}
 * ignore access. Expose it on a block with {@link Essentia#expose}. Save it with {@link #CODEC} on {@link #contents()}.
 */
public final class EssentiaBuffer implements EssentiaStore {
    /** Saves the contents: a map of aspect id to amount. */
    public static final Codec<Map<FId, Integer>> CODEC = Codec.unboundedMap(FId.CODEC, Codec.INT);

    private final int capacity;
    private final int maxAspects;
    private final LinkedHashMap<FId, Integer> held = new LinkedHashMap<>();
    private Predicate<FId> filter = a -> true;
    private boolean canInsert = true;
    private boolean canExtract = true;
    private Runnable onChange = () -> { };
    private long revision;

    /** Holds any number of aspects up to {@code capacity} in total. */
    public EssentiaBuffer(int capacity) {
        this(capacity, 0);
    }

    /** {@code maxAspects} 0 means no limit; 1 makes a jar that holds one aspect at a time. */
    public EssentiaBuffer(int capacity, int maxAspects) {
        if (capacity < 0 || maxAspects < 0) throw new IllegalArgumentException("negative capacity or aspect limit");
        this.capacity = capacity;
        this.maxAspects = maxAspects;
    }

    public EssentiaBuffer filter(Predicate<FId> filter) {
        this.filter = filter;
        return this;
    }

    /** What outsiders may do. The owner's add and take always work. */
    public EssentiaBuffer access(boolean insert, boolean extract) {
        this.canInsert = insert;
        this.canExtract = extract;
        return this;
    }

    /** Runs after every real change (not simulations). */
    public EssentiaBuffer onChange(Runnable listener) {
        this.onChange = listener;
        return this;
    }

    public int capacity() {
        return capacity;
    }

    public int maxAspects() {
        return maxAspects;
    }

    /** Goes up on every change to the contents, so readers can tell when to look again. */
    public long revision() {
        return revision;
    }

    public int space() {
        return Math.max(0, capacity - total());
    }

    @Override
    public Map<FId, Integer> contents() {
        return new LinkedHashMap<>(held);
    }

    @Override
    public int amount(FId aspect) {
        Integer n = held.get(aspect);
        return n == null ? 0 : n;
    }

    @Override
    public int total() {
        int t = 0;
        for (int n : held.values()) t += n;
        return t;
    }

    /** How much of an aspect would fit, ignoring access. */
    public int roomFor(FId aspect) {
        if (!filter.test(aspect)) return 0;
        if (maxAspects > 0 && !held.containsKey(aspect) && held.size() >= maxAspects) return 0;
        return space();
    }

    @Override
    public int insert(FId aspect, int amount, boolean simulate) {
        return canInsert ? add(aspect, amount, simulate) : 0;
    }

    @Override
    public int extract(FId aspect, int amount, boolean simulate) {
        return canExtract ? take(aspect, amount, simulate) : 0;
    }

    /** The owner's insert: obeys capacity, the aspect limit and the filter, not access. */
    public int add(FId aspect, int amount, boolean simulate) {
        if (amount <= 0) return 0;
        int n = Math.min(amount, roomFor(aspect));
        if (n > 0 && !simulate) {
            held.merge(aspect, n, Integer::sum);
            revision++;
            onChange.run();
        }
        return n;
    }

    /** The owner's extract: ignores access. */
    public int take(FId aspect, int amount, boolean simulate) {
        if (amount <= 0) return 0;
        int has = amount(aspect);
        int n = Math.min(amount, has);
        if (n > 0 && !simulate) {
            if (n == has) held.remove(aspect);
            else held.put(aspect, has - n);
            revision++;
            onChange.run();
        }
        return n;
    }

    /** Takes everything out and runs the listener if anything was held. */
    public void clear() {
        if (held.isEmpty()) return;
        held.clear();
        revision++;
        onChange.run();
    }

    /** Replaces the contents without running the listener: for loading a save or rolling back a transaction. */
    public void restore(Map<FId, Integer> contents) {
        held.clear();
        revision++;
        contents.forEach((a, n) -> {
            if (n != null && n > 0) held.put(a, n);
        });
    }

    /** Runs the change listener. For code that changed the contents with {@link #restore}. */
    public void changed() {
        onChange.run();
    }
}
