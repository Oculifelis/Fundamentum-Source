package com.fundamentum.data;

import com.mojang.logging.LogUtils;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * Logs, once per id, data that names something not registered (a mistyped entity or effect), which would otherwise
 * just do nothing every time it is used.
 */
public final class Unknown {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    /** {@code what} is the kind ("entity", "effect"), {@code id} the unknown id, {@code where} the data that names it. */
    public static void warn(String what, Object id, Object where) {
        if (SEEN.add(what + " " + id + " " + where)) {
            LOG.warn("Fundamentum: unknown {} {} in {}; it is skipped", what, id, where);
        }
    }

    private Unknown() {}
}
