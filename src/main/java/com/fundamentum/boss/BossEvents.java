package com.fundamentum.boss;

import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.Event;

/** The boss events, posted on the game event bus (server side). */
public final class BossEvents {
    private BossEvents() { }

    /** Base: the boss and its runtime. */
    public abstract static class Base extends Event {
        private final Boss boss;

        protected Base(Boss boss) {
            this.boss = boss;
        }

        public Boss boss() {
            return this.boss;
        }

        public LivingEntity entity() {
            return this.boss.entity();
        }
    }

    /** The boss left phase {@code from} for phase {@code to} ("base" is the phase with none holding). */
    public static final class Phase extends Base {
        private final String from;
        private final String to;

        public Phase(Boss boss, String from, String to) {
            super(boss);
            this.from = from;
            this.to = to;
        }

        public String from() {
            return this.from;
        }

        public String to() {
            return this.to;
        }
    }

    /** Every player left the arena long enough: the boss was healed and set back to its base phase. */
    public static final class Reset extends Base {
        public Reset(Boss boss) {
            super(boss);
        }
    }

    /**
     * The boss died. {@code fighters} are the players who fought it (inside the arena or range while it was engaged, or
     * who hurt it); {@code firstKill} are those of them who had never defeated it before; {@code firstInWorld} is true when
     * nobody in this world had. Records are already saved when this fires.
     */
    public static final class Defeated extends Base {
        private final Set<UUID> fighters;
        private final Set<UUID> firstKill;
        private final boolean firstInWorld;

        public Defeated(Boss boss, Set<UUID> fighters, Set<UUID> firstKill, boolean firstInWorld) {
            super(boss);
            this.fighters = Set.copyOf(fighters);
            this.firstKill = Set.copyOf(firstKill);
            this.firstInWorld = firstInWorld;
        }

        public Set<UUID> fighters() {
            return this.fighters;
        }

        public Set<UUID> firstKill() {
            return this.firstKill;
        }

        public boolean firstInWorld() {
            return this.firstInWorld;
        }
    }
}
