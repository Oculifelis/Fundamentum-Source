package com.fundamentum.state;

import java.util.function.BiPredicate;
import java.util.function.IntConsumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import org.jetbrains.annotations.Nullable;

/**
 * One explicit state machine for a boss (or any entity, block entity or item that walks through states): one enum of
 * states, one way to change state ({@link #to}), a clock of ticks spent in the current state, saved by the state's
 * name (never its ordinal, so states can be added or reordered without breaking worlds) and synced to clients as a
 * single int, from which the client derives everything else.
 *
 * <pre>{@code
 * enum Phase { SPAWN, FIGHT, ROAR, DESPERATE, DYING }
 * static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(MyBoss.class, EntityDataSerializers.INT);
 * final BossStateMachine<Phase> phase = new BossStateMachine<>(Phase.class, Phase.SPAWN)
 *     .allow((from, to) -> from != Phase.DYING)          // nothing leaves DYING
 *     .onChange((from, to) -> { if (to == Phase.ROAR) roarTarget = null; });
 *
 * defineSynchedData:    builder.define(PHASE, 0); phase.syncTo(entityData, PHASE);
 * onSyncedDataUpdated:  if (PHASE.equals(key)) phase.onSynced(entityData.get(PHASE));
 * tick (both sides):    phase.tick(); if (phase.is(Phase.ROAR) && phase.ticks() >= 60) phase.to(Phase.FIGHT);
 * save / load:          phase.save(tag, "phase"); / phase.load(tag, "phase");
 * }</pre>
 *
 * Rules that go with it: data that belongs to one state (an attack's target, a lane angle) is reset in the
 * {@link #onChange} listener when that state is entered or left, and saved with it or not at all; the listener does not
 * run on load ({@link #restore}), so whatever it would set up must be saved or rebuilt from the state.
 */
public final class BossStateMachine<S extends Enum<S>> {
    /** Called after every change of state ({@link #to}, {@link #restart}), with the state left and the one entered. */
    @FunctionalInterface
    public interface Listener<S> {
        void changed(S from, S to);
    }

    private final Class<S> type;
    private final S initial;
    private final S[] values;
    private BiPredicate<S, S> allowed = (from, to) -> true;
    private Listener<S> listener = (from, to) -> {};
    private @Nullable IntConsumer sync;
    private S state;
    private int ticks;

    public BossStateMachine(Class<S> type, S initial) {
        this.type = type;
        this.initial = initial;
        this.values = type.getEnumConstants();
        this.state = initial;
    }

    /** Which changes are legal; {@link #to} refuses the rest (and returns false). Everything is allowed by default. */
    public BossStateMachine<S> allow(BiPredicate<S, S> rule) {
        this.allowed = rule;
        return this;
    }

    /** What happens on a change (see {@link Listener}); replaces any earlier listener. */
    public BossStateMachine<S> onChange(Listener<S> listener) {
        this.listener = listener;
        return this;
    }

    /** Server side: every change (and load) is written to {@code key}, an INT entity data value the client reads. */
    public BossStateMachine<S> syncTo(SynchedEntityData data, EntityDataAccessor<Integer> key) {
        return syncWith(id -> data.set(key, id));
    }

    /** Server side: every change (and load) is handed to {@code sink} as {@link #id}. */
    public BossStateMachine<S> syncWith(IntConsumer sink) {
        this.sync = sink;
        sink.accept(this.state.ordinal());
        return this;
    }

    public S state() {
        return this.state;
    }

    public boolean is(S s) {
        return this.state == s;
    }

    @SafeVarargs
    public final boolean isAny(S... any) {
        for (S s : any) {
            if (this.state == s) {
                return true;
            }
        }
        return false;
    }

    /** Ticks spent in the current state (0 on the tick it was entered, counted by {@link #tick}). */
    public int ticks() {
        return this.ticks;
    }

    /** Counts a tick in the current state. Call once per entity tick, on each side that reads {@link #ticks}. */
    public void tick() {
        this.ticks++;
    }

    /**
     * The one way to change state: to {@code next} if the {@link #allow} rule lets it, restarting the clock, syncing
     * and telling the listener. Returns false (and changes nothing) for an illegal change or when already in
     * {@code next}; use {@link #restart} to enter the same state again.
     */
    public boolean to(S next) {
        if (next == this.state || !this.allowed.test(this.state, next)) {
            return false;
        }
        S from = this.state;
        this.state = next;
        this.ticks = 0;
        if (this.sync != null) {
            this.sync.accept(next.ordinal());
        }
        this.listener.changed(from, next);
        return true;
    }

    /** Enters the current state again: the clock restarts and the listener hears {@code (state, state)}. */
    public void restart() {
        this.ticks = 0;
        this.listener.changed(this.state, this.state);
    }

    // ------------------------------------------------------------------------------------------------ save / sync
    /** The current state's stable name, as saved. */
    public String name() {
        return this.state.name();
    }

    /** The current state as synced: its ordinal, which only ever travels between a server and client of one build. */
    public int id() {
        return this.state.ordinal();
    }

    /**
     * Sets the state from saved data with no listener and no {@link #allow} check: an unknown or missing name falls back
     * to the initial state with the clock at 0, so a world from an older version starts that machine afresh.
     */
    public void restore(@Nullable String name, int ticks) {
        S s = parse(this.type, name, null);
        this.state = s == null ? this.initial : s;
        this.ticks = s == null ? 0 : Math.max(0, ticks);
        if (this.sync != null) {
            this.sync.accept(this.state.ordinal());
        }
    }

    /** Client side: the server's state arrived ({@link #id}); a new one restarts the local clock. */
    public void onSynced(int id) {
        S s = id >= 0 && id < this.values.length ? this.values[id] : this.initial;
        if (s != this.state) {
            this.state = s;
            this.ticks = 0;
        }
    }

    /** Saves the state's name under {@code key} and its clock under {@code key + "_ticks"}. */
    public void save(CompoundTag tag, String key) {
        tag.putString(key, name());
        tag.putInt(key + "_ticks", this.ticks);
    }

    /** Loads what {@link #save} wrote (see {@link #restore}). */
    public void load(CompoundTag tag, String key) {
        restore(tag.getString(key), tag.getInt(key + "_ticks"));
    }

    /** The constant of {@code type} named {@code name}, or {@code fallback} for null or an unknown name. */
    @Nullable
    public static <S extends Enum<S>> S parse(Class<S> type, @Nullable String name, @Nullable S fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
