package com.fundamentum.config;

import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import java.util.List;
import java.util.function.Consumer;

/** Config events. {@link Changed} is the ecosystem event {@code fundamentum:config_changed}. */
public final class ConfigEvent {
    private ConfigEvent() { }

    /**
     * Some values of a config changed after it was first loaded: a file edit, {@code /reload}, an operator's edit or new
     * values from the server. {@code keys} are the entries whose value changed; {@code startup} entries never appear
     * (they wait for a restart).
     */
    public record Changed(FId id, List<String> keys) { }

    /** Calls the listener for every {@link Changed}; the same as {@code FEvents.listen(Changed.class, listener)}. */
    public static void listen(Consumer<Changed> listener) {
        FEvents.listen(Changed.class, listener);
    }

    static void fire(Changed event) {
        FEvents.post(event);
    }
}
