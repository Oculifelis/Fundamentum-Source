package com.fundamentum.soulfire;

/** Added to entity render states by a mixin: whether the entity's flames are drawn as soul fire. */
public interface SoulFireState {
    boolean fundamentum$soulFire();

    void fundamentum$setSoulFire(boolean soul);
}
