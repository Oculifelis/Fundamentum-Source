package com.fundamentum;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Tick cost run, for development servers started with {@code gradlew runServer -Pticktest} (which also starts a JFR
 * recording at 1 ms sampling, see build.gradle): 20 mobs and 2 fake players stand in a walled pen above spawn, the server
 * runs {@link #TICKS} ticks, logs {@code TICKTEST WINDOW start end} (epoch ms) and stops. tools/tickcost.py reads the
 * recording and prints the cost of every Fundamentum tick listener per tick. Checks nothing by itself.
 */
final class TickCostTest {
    private static final Logger LOG = LogUtils.getLogger();
    private static final BlockPos AT = new BlockPos(64, 200, 64);
    private static final int WARMUP = 200;
    private static final int TICKS = 1200;
    private static int start = -1;
    private static long windowStart;
    private static final List<Object> KEEP = new ArrayList<>();

    private TickCostTest() {}

    static void init() {
        if (Boolean.getBoolean("fundamentum.ticktest")) {
            NeoForge.EVENT_BUS.addListener(TickCostTest::tick);
        }
    }

    private static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        int now = server.getTickCount();
        if (start < 0) {
            start = now;
            setUp(level);
            return;
        }
        int t = now - start;
        // fake players are not in the level's player list, so post their tick events by hand like Player.tick does
        for (Object o : KEEP) {
            if (o instanceof FakePlayer p) {
                NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Pre(p));
                NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(p));
            }
        }
        if (t == WARMUP) {
            windowStart = System.currentTimeMillis();
            LOG.info("TICKTEST START {} entities", KEEP.size());
        } else if (t == WARMUP + TICKS) {
            LOG.info("TICKTEST WINDOW {} {} ticks={}", windowStart, System.currentTimeMillis(), TICKS);
            LOG.info("TICKTEST DONE");
            server.halt(false);
        }
    }

    private static void setUp(ServerLevel level) {
        // a 12x12 stone floor with 3-high walls so nothing wanders off or falls
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                level.setBlockAndUpdate(AT.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                if (Math.abs(x) == 6 || Math.abs(z) == 6) {
                    for (int y = 0; y < 3; y++) {
                        level.setBlockAndUpdate(AT.offset(x, y, z), Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
        level.setChunkForced(AT.getX() >> 4, AT.getZ() >> 4, true);
        List<EntityType<? extends Mob>> kinds = List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.COW, EntityType.SHEEP, EntityType.SPIDER);
        for (int i = 0; i < 20; i++) {
            Mob mob = kinds.get(i % kinds.size()).create(level, EntitySpawnReason.COMMAND);
            if (mob == null) continue;
            mob.setPos(AT.getX() - 4.5 + (i % 5) * 2, AT.getY(), AT.getZ() - 3.5 + (i / 5) * 2);
            mob.setPersistenceRequired();
            mob.setInvulnerable(true);
            level.addFreshEntity(mob);
            KEEP.add(mob);
        }
        for (int i = 0; i < 2; i++) {
            FakePlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(("ticktest" + i).getBytes()), "ticktest" + i));
            p.setPos(AT.getX() + 0.5 + i * 2, AT.getY(), AT.getZ() + 4.5);
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(i == 0 ? Items.IRON_SWORD : Items.BOW));
            KEEP.add(p);
        }
    }
}
