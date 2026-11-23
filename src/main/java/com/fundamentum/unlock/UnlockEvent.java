package com.fundamentum.unlock;

import com.fundamentum.port.FId;
import net.minecraft.server.level.ServerPlayer;

/**
 * The unlock events, posted through {@link com.fundamentum.event.FEvents}. {@code holder} is {@code player:<uuid>} or
 * {@code team:<provider>/<team>}; {@code player} is the player who holds it, or for a team the player whose action
 * caused it (null when none, e.g. a command on a team). A grant also posts
 * {@link com.fundamentum.event.Events.UnlockGranted} when there is a player; cancelling either stops it.
 */
public final class UnlockEvent {
    private UnlockEvent() { }

    /** Before an unlock is granted or raised to a higher level ({@code fundamentum:unlock/pre}). Cancellable. */
    public record Pre(ServerPlayer player, String holder, FId unlock, int level) { }

    /** After an unlock was stored ({@code fundamentum:unlock/granted}). */
    public record Granted(ServerPlayer player, String holder, FId unlock, int level) { }

    /** After an unlock was taken away ({@code fundamentum:unlock/revoked}); {@code level} is the level it had. */
    public record Revoked(ServerPlayer player, String holder, FId unlock, int level) { }
}
