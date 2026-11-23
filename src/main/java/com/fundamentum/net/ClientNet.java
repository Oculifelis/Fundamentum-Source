package com.fundamentum.net;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Client lookups for message handlers, kept in their own class so common classes that handle a message never name a
 * client-only class themselves (a dedicated server has none).
 */
public final class ClientNet {
    public static @Nullable Entity entity(int id) {
        var level = Minecraft.getInstance().level;
        return level == null ? null : level.getEntity(id);
    }

    public static @Nullable Player player() {
        return Minecraft.getInstance().player;
    }

    public static @Nullable Level level() {
        return Minecraft.getInstance().level;
    }

    private ClientNet() {}
}
