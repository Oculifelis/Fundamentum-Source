package com.fundamentum.transfer;

import com.fundamentum.port.FId;
import java.util.Map;

/**
 * Something that holds essentia: amounts of aspects, keyed by aspect id ({@code thaumaturge:ignis}). Fundamentum has no
 * Thaumaturge code in it; aspect ids are plain ids, so a store reads the same with or without Thaumaturge installed.
 * Blocks are found with {@link Essentia#find}. A mod's own containers use {@link EssentiaBuffer}.
 */
public interface EssentiaStore {
    /** Every aspect held, with its amount. A copy, in the order the store keeps them. */
    Map<FId, Integer> contents();

    default int amount(FId aspect) {
        Integer n = contents().get(aspect);
        return n == null ? 0 : n;
    }

    default int total() {
        int t = 0;
        for (int n : contents().values()) t += n;
        return t;
    }

    default boolean isEmpty() {
        return total() <= 0;
    }

    /** Puts up to {@code amount} of one aspect in and returns how much went in (or would, when simulating). */
    int insert(FId aspect, int amount, boolean simulate);

    /** Takes up to {@code amount} of one aspect out and returns how much came out (or would, when simulating). */
    int extract(FId aspect, int amount, boolean simulate);
}
