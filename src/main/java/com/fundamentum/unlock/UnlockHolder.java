package com.fundamentum.unlock;

import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who holds an unlock: one player, or a team. A team's key is {@code <provider>/<team name>}, e.g. {@code vanilla/red}
 * for the scoreboard team "red" (see {@link Unlocks.TeamProvider}). Two holders are equal when kind and key are.
 */
public record UnlockHolder(Kind kind, String key, ServerPlayer player) {
    public enum Kind { PLAYER, TEAM }

    public static UnlockHolder of(ServerPlayer player) {
        return new UnlockHolder(Kind.PLAYER, player.getUUID().toString(), player);
    }

    public static UnlockHolder team(String key) {
        return new UnlockHolder(Kind.TEAM, key, null);
    }

    public boolean isTeam() {
        return this.kind == Kind.TEAM;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UnlockHolder h && h.kind == this.kind && h.key.equals(this.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.kind, this.key);
    }

    /** {@code player:<uuid>} or {@code team:<key>}. */
    @Override
    public String toString() {
        return (this.kind == Kind.TEAM ? "team:" : "player:") + this.key;
    }
}
