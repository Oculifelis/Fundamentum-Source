package com.fundamentum.net;

import net.minecraft.world.entity.player.Player;

/**
 * What a message handler may ask of the message it handles, standing in for NeoForge's {@code IPayloadContext} so the
 * handlers read the same on 1.20.1: {@link #player()} is the sender on the server and this client's player on a client.
 */
@FunctionalInterface
public interface IPayloadContext {
    Player player();
}
