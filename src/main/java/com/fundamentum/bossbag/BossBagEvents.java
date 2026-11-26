package com.fundamentum.bossbag;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;

/** Boss bag events, posted on the game event bus (server side). KubeJS and other mods can listen to both. */
public final class BossBagEvents {
    private BossBagEvents() { }

    /**
     * A boss is about to leave its bags, one per player in {@link #takers()}. The list can be changed. Cancel it, or
     * empty the list, and the boss drops its loot loose as usual.
     */
    @Cancelable
    public static final class Leave extends Event {
        private final ServerLevel level;
        private final LivingEntity boss;
        private final BagDef def;
        private final List<ServerPlayer> takers;
        private int xp;

        public Leave(ServerLevel level, LivingEntity boss, BagDef def, List<ServerPlayer> takers, int xp) {
            this.level = level;
            this.boss = boss;
            this.def = def;
            this.takers = takers;
            this.xp = xp;
        }

        public ServerLevel level() {
            return this.level;
        }

        public LivingEntity boss() {
            return this.boss;
        }

        public BagDef def() {
            return this.def;
        }

        /** The players who get a bag; can be changed. */
        public List<ServerPlayer> takers() {
            return this.takers;
        }

        /** The XP each bag gives. */
        public int xp() {
            return this.xp;
        }

        public void setXp(int xp) {
            this.xp = Math.max(0, xp);
        }
    }

    /** A player opened a bag. {@link #items()} is what they get, and can be changed. */
    public static final class Open extends Event {
        private final ServerPlayer player;
        private final ItemStack bag;
        private final List<ItemStack> items;

        public Open(ServerPlayer player, ItemStack bag, List<ItemStack> items) {
            this.player = player;
            this.bag = bag;
            this.items = items;
        }

        public ServerPlayer player() {
            return this.player;
        }

        public ItemStack bag() {
            return this.bag;
        }

        public List<ItemStack> items() {
            return this.items;
        }
    }
}
