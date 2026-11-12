package com.fundamentum.mobgoal;

import com.fundamentum.boss.Boss;
import com.fundamentum.boss.Bosses;
import java.util.Set;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Wraps a goal so it only runs during some boss phases ({@code "phases": [...]} on a mob goal entry). A mob that is not
 * a boss right now counts as being in no phase, so the goal stays off. When the phase changes mid-run the goal stops.
 */
public final class PhaseGate extends Goal {
    private final Mob mob;
    private final Goal inner;
    private final Set<String> phases;

    public PhaseGate(Mob mob, Goal inner, Set<String> phases) {
        this.mob = mob;
        this.inner = inner;
        this.phases = Set.copyOf(phases);
        setFlags(inner.getFlags());
    }

    public Goal inner() {
        return inner;
    }

    public Set<String> phases() {
        return phases;
    }

    /** True when the mob is a boss in one of the listed phases. */
    public boolean open() {
        Boss b = Bosses.get(mob);
        return b != null && phases.contains(b.phase());
    }

    @Override
    public boolean canUse() {
        return open() && inner.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return open() && inner.canContinueToUse();
    }

    @Override
    public boolean isInterruptable() {
        return inner.isInterruptable();
    }

    @Override
    public void start() {
        inner.start();
    }

    @Override
    public void stop() {
        inner.stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return inner.requiresUpdateEveryTick();
    }

    @Override
    public void tick() {
        inner.tick();
    }
}
