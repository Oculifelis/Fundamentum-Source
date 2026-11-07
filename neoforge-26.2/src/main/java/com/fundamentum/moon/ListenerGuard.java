package com.fundamentum.moon;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Calls other mods' listeners one at a time, so one that throws neither crashes the server tick nor stops the rest.
 * Each failure is an ERROR naming the mod the listener belongs to, the event and the moon, with the full stack trace;
 * after {@link #LIMIT} failures the listener is taken off its list for the rest of the session, said once.
 */
final class ListenerGuard<T> {
    /** Failures from one listener before it is dropped. */
    static final int LIMIT = 3;

    /** Where a failure goes: an ERROR line in the log, unless a test listens instead. */
    interface Report {
        void error(String message, Throwable cause);
    }

    private final String kind;
    private final List<T> listeners;
    private final Function<Object, String> owner;
    private final Report report;
    private final Map<T, Integer> failures = new ConcurrentHashMap<>();

    /** {@code kind} names what the listeners hang off in the log, e.g. "moon" or "night event". */
    ListenerGuard(String kind, List<T> listeners, Function<Object, String> owner, Report report) {
        this.kind = kind;
        this.listeners = listeners;
        this.owner = owner;
        this.report = report;
    }

    /** Runs {@code call} on every listener; {@code event} and {@code id} (the moon) only name what was happening. */
    void fire(String event, String id, Consumer<T> call) {
        for (T li : listeners) {
            try {
                call.accept(li);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) {
                    throw e;
                }
                String mod = owner.apply(li);
                int n = failures.merge(li, 1, Integer::sum);
                report.error(failed(kind, mod, event, id, li, n), t);
                if (n >= LIMIT && listeners.remove(li)) {
                    failures.remove(li);
                    report.error(dropped(kind, mod, li), null);
                }
            }
        }
    }

    static String failed(String kind, String mod, String event, String id, Object li, int n) {
        return "Fundamentum: a " + kind + " " + event + " listener from mod '" + mod + "' threw on " + kind + " " + id
            + " (" + li.getClass().getName() + ", failure " + n + " of " + LIMIT + "); the other listeners still ran."
            + " This is a bug in '" + mod + "', not in Fundamentum";
    }

    static String dropped(String kind, String mod, Object li) {
        return "Fundamentum: dropped the " + kind + " listener from mod '" + mod + "' (" + li.getClass().getName() + ") after "
            + LIMIT + " failures; it is not called again until the game restarts";
    }
}
