package com.fundamentum.event;

import com.fundamentum.port.FId;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * An ecosystem event on the loader's event bus, for mods that prefer {@code @SubscribeEvent}. Listen to this class
 * for every event, or to {@link Cancellable} for the ones that can be stopped. {@link #as} gives the record.
 * Fired after the {@link FEvents#listen} listeners and only when none of them cancelled it.
 */
public class FundamentumEvent extends Event {
    private final FEvents.Type type;
    private final Record event;

    public FundamentumEvent(FEvents.Type type, Record event) {
        this.type = type;
        this.event = event;
    }

    public FId id() {
        return this.type.id();
    }

    public Record event() {
        return this.event;
    }

    /** The record when it is of this class, else null. */
    public <E extends Record> E as(Class<E> type) {
        return type.isInstance(this.event) ? type.cast(this.event) : null;
    }

    /** An event that can be cancelled: {@code setCanceled(true)} stops what it announces. */
    public static class Cancellable extends FundamentumEvent implements ICancellableEvent {
        public Cancellable(FEvents.Type type, Record event) {
            super(type, event);
        }
    }

    /** Hears every ecosystem event on the loader bus (the same as a {@code @SubscribeEvent} method for this class). */
    public static void listen(java.util.function.Consumer<FundamentumEvent> listener) {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(FundamentumEvent.class, listener);
    }

    /** Called once while Fundamentum loads: posts every ecosystem event on the loader bus too. */
    static void init() {
        FEvents.bridge((type, event, cancelled) -> {
            FundamentumEvent e = type.cancellable() ? new Cancellable(type, event) : new FundamentumEvent(type, event);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(e);
            return e instanceof Cancellable c && c.isCanceled();
        });
    }
}
