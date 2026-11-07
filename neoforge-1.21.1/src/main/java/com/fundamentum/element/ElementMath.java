package com.fundamentum.element;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The game-free arithmetic of elemental interactions, kept apart so plain unit tests cover it: how an aura's strength
 * decays and stacks, how affinities add up to a damage multiplier, and the loop guard that stops reactions from
 * re-triggering themselves.
 */
public final class ElementMath {
    /** The strength an aura has {@code ticks} after it was last set, losing {@code decayPerSecond} each second. */
    public static float decayed(float strength, float decayPerSecond, long ticks) {
        return Math.max(0, strength - decayPerSecond * Math.max(0, ticks) / 20f);
    }

    /** Stacks after one more application, never above {@code maxStacks} (at least 1). */
    public static int stacked(int stacks, int maxStacks) {
        return Math.min(Math.max(1, maxStacks), stacks + 1);
    }

    /** Strength after one more application: the current strength plus {@code add}, capped at {@code add * maxStacks}. */
    public static float stackedStrength(float current, float add, int maxStacks) {
        return Math.min(current + add, add * Math.max(1, maxStacks));
    }

    /** Strength left when one of {@code stacks} stacks is consumed. */
    public static float afterConsumingOne(float strength, int stacks) {
        return stacks <= 1 ? 0 : strength * (stacks - 1) / stacks;
    }

    /**
     * What resistances that add up to {@code total} multiply a hit by: 0.5 halves it, -1 doubles it, 1 or more is
     * immunity (0).
     */
    public static float affinityMultiplier(float total) {
        return Math.max(0, 1 - Math.min(total, 1));
    }

    /** A reaction's damage: {@code amount * multiplier + bonus + perStack * stacks}, never below 0. */
    public static float reactionDamage(float amount, float multiplier, float bonus, float perStack, int stacks) {
        return Math.max(0, amount * multiplier + bonus + perStack * stacks);
    }

    /**
     * Stops reaction loops. A reaction can't fire while it is already running further up the chain (its burst hitting a
     * creature that carries the same aura), can't fire twice on the same target in the same tick, and a chain of
     * different reactions stops at {@link #MAX_DEPTH}. Not thread-safe: the server thread owns it.
     */
    public static final class LoopGuard<R, T> {
        public static final int MAX_DEPTH = 8;
        private final Deque<R> running = new ArrayDeque<>();
        private final Map<T, Set<R>> firedThisTick = new HashMap<>();
        private long tick = Long.MIN_VALUE;

        /** Whether {@code reaction} may fire on {@code target} in game tick {@code now}. */
        public boolean allows(R reaction, T target, long now) {
            roll(now);
            if (this.running.size() >= MAX_DEPTH || this.running.contains(reaction)) {
                return false;
            }
            Set<R> fired = this.firedThisTick.get(target);
            return fired == null || !fired.contains(reaction);
        }

        /** Marks {@code reaction} as running on {@code target}; pair with {@link #exit}. */
        public void enter(R reaction, T target, long now) {
            roll(now);
            this.running.push(reaction);
            this.firedThisTick.computeIfAbsent(target, k -> new HashSet<>()).add(reaction);
        }

        public void exit() {
            this.running.pop();
        }

        public int depth() {
            return this.running.size();
        }

        private void roll(long now) {
            if (now != this.tick) {
                this.tick = now;
                this.firedThisTick.clear();
            }
        }
    }

    private ElementMath() {}
}
