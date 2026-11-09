package com.fundamentum.summon;

import java.util.ArrayList;
import java.util.List;

/**
 * One player's placed sentries, oldest first, with what each costs in sentry slots. Placing a sentry past the
 * {@code fundamentum:sentry_slots} limit sends away the oldest ones until the new one fits; one that costs more than the
 * whole limit is not placed. Plain code with no game classes, so it is unit tested on its own.
 */
public final class SentryRoster<T> {
    private record Entry<T>(T sentry, double cost) {}

    private final List<Entry<T>> entries = new ArrayList<>();

    /** True when a sentry of this cost can be placed at all under this limit. */
    public static boolean fits(double cost, double limit) {
        return cost <= limit + 1.0E-6;
    }

    /**
     * Adds a sentry and returns the ones that must go (oldest first) so the total stays within the limit. A sentry that
     * costs more than the limit is not added, and the list then holds only that sentry.
     */
    public List<T> add(T sentry, double cost, double limit) {
        if (!fits(cost, limit)) {
            return List.of(sentry);
        }
        entries.add(new Entry<>(sentry, cost));
        return trim(limit);
    }

    /** Removes the oldest sentries until the total fits the limit (after the limit fell, say) and returns them. */
    public List<T> trim(double limit) {
        List<T> out = new ArrayList<>();
        while (!entries.isEmpty() && used() > limit + 1.0E-6) {
            out.add(entries.remove(0).sentry());
        }
        return out;
    }

    public boolean remove(T sentry) {
        return entries.removeIf(e -> e.sentry().equals(sentry));
    }

    /** Slots in use now. */
    public double used() {
        double sum = 0;
        for (Entry<T> e : entries) {
            sum += e.cost();
        }
        return sum;
    }

    /** The sentries, oldest first. */
    public List<T> sentries() {
        List<T> out = new ArrayList<>(entries.size());
        entries.forEach(e -> out.add(e.sentry()));
        return out;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Empties the roster and returns what was in it, oldest first. */
    public List<T> clear() {
        List<T> out = sentries();
        entries.clear();
        return out;
    }
}
