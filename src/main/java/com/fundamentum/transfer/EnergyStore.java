package com.fundamentum.transfer;

/**
 * Something that holds energy, seen the same way on every version and loader. Amounts are the loader's own energy unit
 * (FE); Fundamentum adds no unit of its own. {@link Transfer#energy} hands one out for any block that exposes the
 * loader's energy capability, from any mod; {@link EnergyBuffer} is the ready-made one to keep in a block entity.
 */
public interface EnergyStore {
    /** Energy held now. */
    int stored();

    /** Most energy this can hold. */
    int capacity();

    /** Adds up to {@code amount}; returns how much went in (or would have, when simulating). */
    int insert(int amount, boolean simulate);

    /** Takes up to {@code amount}; returns how much came out (or would have, when simulating). */
    int extract(int amount, boolean simulate);

    /** Room left. */
    default int space() {
        return Math.max(0, capacity() - stored());
    }
}
