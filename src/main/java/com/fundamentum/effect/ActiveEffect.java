package com.fundamentum.effect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * One status effect on one entity: its stacks and when it started. A stack with a {@code source} was granted by something
 * that keeps it up (an armour set tier, say): it never runs out, cures leave it alone, and only {@link Effects#revoke}
 * with the same source takes it away.
 */
public final class ActiveEffect {
    /** One stack; {@code expiresAt} is a game time, {@link Long#MAX_VALUE} for one that never runs out. */
    public record Stack(int amplifier, long expiresAt, @Nullable String source) {
        public boolean permanent() {
            return this.expiresAt == Long.MAX_VALUE;
        }
    }

    private final ResourceLocation id;
    private final List<Stack> stacks = new ArrayList<>();
    private long startedAt;
    int appliedModifiers;
    int appliedLevel;

    ActiveEffect(ResourceLocation id, long startedAt) {
        this.id = id;
        this.startedAt = startedAt;
    }

    public ResourceLocation id() {
        return this.id;
    }

    public long startedAt() {
        return this.startedAt;
    }

    public List<Stack> stacks() {
        return Collections.unmodifiableList(this.stacks);
    }

    List<Stack> mutableStacks() {
        return this.stacks;
    }

    /** The effect's level: the sum of (amplifier + 1) over stacks for independent stacking, otherwise the highest. */
    public int level(EffectDefinition definition) {
        int level = 0;
        for (Stack s : this.stacks) {
            level = definition.stacking() == EffectDefinition.Stacking.INDEPENDENT ? level + s.amplifier() + 1 : Math.max(level, s.amplifier() + 1);
        }
        return level;
    }

    /** The highest amplifier of any stack. */
    public int amplifier() {
        int amp = -1;
        for (Stack s : this.stacks) {
            amp = Math.max(amp, s.amplifier());
        }
        return amp;
    }

    /** Ticks until the last stack runs out, {@link Long#MAX_VALUE} if one never does. */
    public long remaining(long now) {
        long max = 0;
        for (Stack s : this.stacks) {
            max = s.permanent() ? Long.MAX_VALUE : Math.max(max, s.expiresAt() - now);
        }
        return max;
    }

    /** The number of stacks that were not granted by a source. */
    public int timedStacks() {
        int n = 0;
        for (Stack s : this.stacks) {
            if (s.source() == null) {
                n++;
            }
        }
        return n;
    }
}
