package com.fundamentum.bossbagtest;

import com.fundamentum.bossbag.BagDef;
import com.fundamentum.bossbag.BossBagEvents;
import com.fundamentum.bossbag.BossBagItem;
import com.fundamentum.bossbag.BossBags;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * gradlew runServer -Pbossbagtest: a listed boss killed by a player leaves that player a bag and no loose loot; the bag
 * opens into its entry's loot table and "always" items; two takers get two bags; a bag is its owner's alone at first;
 * an unlisted mob drops as usual; tag entries match; the Leave event can cancel. Writes bossbagtest-result.txt and stops
 * the server. Log lines start with BOSSBAGTEST.
 */
@Mod.EventBusSubscriber(modid = "fundamentum")
public final class BossBagSelfTest {
    private BossBagSelfTest() { }

    private static final Logger LOG = LogUtils.getLogger();
    private static final List<String> LINES = new ArrayList<>();
    private static boolean failed;
    private static boolean cancelLeave;

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        LOG.info("BOSSBAGTEST {}", line);
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (System.getProperty("fundamentum.bossbagtest") == null) return;
        MinecraftServer server = event.getServer();
        MinecraftForge.EVENT_BUS.addListener((BossBagEvents.Leave e) -> { if (cancelLeave) e.setCanceled(true); });
        try {
            run(server);
        } catch (Throwable t) {
            check(false, "test threw " + t);
            LOG.error("BOSSBAGTEST threw", t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("BOSSBAGTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("bossbagtest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("BOSSBAGTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static ServerPlayer fake(ServerLevel level, String name) {
        return FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
    }

    private static List<ItemEntity> items(ServerLevel level, BlockPos at) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(16));
    }

    private static List<ItemEntity> bags(ServerLevel level, BlockPos at) {
        return items(level, at).stream().filter(e -> e.getItem().getItem() instanceof BossBagItem).toList();
    }

    private static void clear(ServerLevel level, BlockPos at) {
        for (ItemEntity e : items(level, at)) e.discard();
    }

    private static LivingEntity spawn(ServerLevel level, EntityType<? extends LivingEntity> type, BlockPos at) {
        LivingEntity mob = type.create(level);
        mob.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        level.addFreshEntity(mob);
        return mob;
    }

    private static LivingEntity kill(ServerLevel level, EntityType<? extends LivingEntity> type, BlockPos at, ServerPlayer p) {
        LivingEntity mob = spawn(level, type, at);
        mob.hurt(level.damageSources().playerAttack(p), 10000F);
        return mob;
    }

    private static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos at = new BlockPos(0, 220, 0);
        level.getChunk(at);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) level.setBlockAndUpdate(at.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
        }

        BagDef zdef = BossBags.def(EntityType.ZOMBIE);
        check(zdef != null && zdef.ownTicks() == 1200 && zdef.always().size() == 1, "zombie entry read: " + zdef);
        check(BossBags.def(EntityType.SKELETON) != null && BossBags.def(EntityType.STRAY) != null, "tag entry matches skeletons and strays");
        check(BossBags.def(EntityType.PIG) == null, "pig has no entry");

        ServerPlayer p = fake(level, "bag_one");
        ServerPlayer q = fake(level, "bag_two");
        p.setPos(at.getX() + 1.5, at.getY(), at.getZ() + 0.5);
        q.setPos(at.getX() - 1.5, at.getY(), at.getZ() + 0.5);

        // a kill leaves its slayer one owned bag and nothing loose
        clear(level, at);
        LivingEntity z = kill(level, EntityType.ZOMBIE, at, p);
        check(z.isDeadOrDying(), "zombie died");
        check(BossBags.leftBags(z), "zombie left its bags");
        List<ItemEntity> after = items(level, at);
        List<ItemEntity> bags = bags(level, at);
        check(bags.size() == 1, "one bag for one taker: " + bags.size());
        check(after.size() == bags.size(), "no loose loot beside the bag: " + after.stream().map(e -> e.getItem().toString()).toList());
        if (!bags.isEmpty()) {
            ItemEntity b = bags.get(0);
            check(p.getUUID().equals(BossBags.owner(b)), "bag belongs to the slayer");
            check(BossBags.mayTake(b, p.getUUID()) && !BossBags.mayTake(b, q.getUUID()), "only the owner may take it at first");
            check(BossBags.boss(b.getItem()) == EntityType.ZOMBIE, "bag names its boss");
            check(b.getItem().is(BossBags.ITEM.get()), "generic bag item used");
            check(BossBags.xp(b.getItem()) > 0, "bag holds the zombie's xp: " + BossBags.xp(b.getItem()));
            check(b.getItem().getHoverName().getString().contains("Zombie"), "bag named for its boss: " + b.getItem().getHoverName().getString());

            // opening it rolls the entry's table and adds "always"
            p.getInventory().clearContent();
            p.setItemInHand(InteractionHand.MAIN_HAND, b.getItem().copy());
            boolean used = BossBags.use(level, p, p.getMainHandItem());
            int diamonds = p.getInventory().countItem(Items.DIAMOND);
            int gold = p.getInventory().countItem(Items.GOLD_INGOT);
            check(used && diamonds == 1 && gold == 1, "opened bag gave its diamond and gold: " + diamonds + " " + gold);
            check(p.getInventory().countItem(BossBags.ITEM.get()) == 0, "opening used up the bag");
        }

        // two takers, two bags
        clear(level, at);
        LivingEntity z2 = spawn(level, EntityType.ZOMBIE, at);
        boolean left = BossBags.leave(level, z2, List.of(p, q), 5);
        List<ItemEntity> two = bags(level, at);
        check(left && two.size() == 2, "leave gives two takers two bags: " + two.size());
        check(two.stream().map(BossBags::owner).distinct().count() == 2, "each bag has its own owner");
        check(two.stream().allMatch(e -> BossBags.xp(e.getItem()) == 5), "each bag carries the xp");
        z2.discard();

        // the takers rule
        LivingEntity z3 = spawn(level, EntityType.ZOMBIE, at);
        List<ServerPlayer> takers = BossBags.takers(level, z3, p);
        check(takers.size() == 1 && takers.get(0) == p, "killer is a taker: " + takers.size());
        check(BossBags.takers(level, z3, p, new BagDef.Participation(false, false, false, 0, 0), List.of()).isEmpty(),
            "participation all off leaves no takers");
        check(BossBags.takers(level, z3, null, BagDef.Participation.DEFAULT, List.of(q.getUUID())).isEmpty(), "offline fighters are skipped");
        z3.discard();

        // an unlisted mob drops as usual
        clear(level, at);
        LivingEntity pig = kill(level, EntityType.PIG, at, p);
        List<ItemEntity> pigDrops = items(level, at);
        check(!BossBags.leftBags(pig) && bags(level, at).isEmpty(), "pig leaves no bag");
        check(pigDrops.stream().anyMatch(e -> e.getItem().is(Items.PORKCHOP) || e.getItem().is(Items.COOKED_PORKCHOP)), "pig dropped its pork");

        // a cancelled Leave lets the loot fall loose
        clear(level, at);
        cancelLeave = true;
        LivingEntity z4 = kill(level, EntityType.ZOMBIE, at, p);
        cancelLeave = false;
        check(!BossBags.leftBags(z4) && bags(level, at).isEmpty(), "cancelled leave leaves no bag");

        // a skeleton (tag entry, arena off): the slayer still gets a bag of the skeleton
        clear(level, at);
        kill(level, EntityType.SKELETON, at, p);
        List<ItemEntity> sk = bags(level, at);
        check(sk.size() == 1 && BossBags.boss(sk.get(0).getItem()) == EntityType.SKELETON, "skeleton left a skeleton bag: " + sk.size());
        clear(level, at);
    }
}
