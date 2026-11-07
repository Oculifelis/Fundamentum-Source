package com.fundamentum.portal;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import org.slf4j.Logger;

/**
 * The in-game portal crafting test, for development servers started with {@code gradlew runServer -Pportaltest}, which
 * also loads the test recipes in {@code src/portaltest}. It builds a lit Nether portal high above spawn, drops items
 * into it and checks what comes back out, then stops the server. Log lines start with PORTALTEST.
 */
public final class PortalSelfTest {
    private PortalSelfTest() { }

    private static final Logger LOG = LogUtils.getLogger();
    private static final BlockPos BASE = new BlockPos(0, 250, 0);
    private static final List<String> RESULTS = new ArrayList<>();
    private static int start = -1;

    public static void init() {
        if (!Boolean.getBoolean("fundamentum.portaltest")) return;
        MinecraftForge.EVENT_BUS.addListener(PortalSelfTest::tick);
    }

    private static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        int now = server.getTickCount();
        if (start < 0) {
            if (now < 40) return;
            start = now;
            PortalCrafting.trace = line -> LOG.info("PORTALTEST trace {}", line);
            LOG.info("PORTALTEST {} portal recipes loaded", server.getRecipeManager().getAllRecipesFor(PortalCrafting.TYPE.get()).size());
            level.setChunkForced(0, 0, true);
            build(level);
            return;
        }
        if (now == start + 20) {
            // the dev world is kept between runs: clear what earlier runs left (their entities load a few ticks late)
            level.getEntitiesOfClass(ItemEntity.class, new AABB(BASE).inflate(8)).forEach(ItemEntity::discard);
            ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
            pick.setDamageValue(10);
            pick.setHoverName(Component.literal("Testpick"));
            drop(level, pick, 1.5);
            drop(level, new ItemStack(Items.COBBLESTONE, 40), 2.5);
            drop(level, new ItemStack(Items.DIRT), 1.5);
            drop(level, new ItemStack(Items.STICK), 2.5);
            return;
        }
        if (now != start + 120) return;
        List<ItemEntity> near = level.getEntitiesOfClass(ItemEntity.class, new AABB(BASE).inflate(6));
        List<ItemEntity> picks = of(near, Items.GOLDEN_PICKAXE);
        ItemStack pick = picks.isEmpty() ? ItemStack.EMPTY : picks.get(0).getItem();
        report("iron pickaxe became a golden one", picks.size() == 1, picks.size() + " golden pickaxes");
        report("it kept its damage and name", pick.getDamageValue() == 10 && "Testpick".equals(pick.getHoverName().getString()),
                "damage " + pick.getDamageValue() + " name " + pick.getHoverName().getString());
        report("it was pushed out of the portal", !picks.isEmpty() && !level.getBlockState(picks.get(0).blockPosition()).is(Blocks.NETHER_PORTAL),
                picks.isEmpty() ? "none" : "at " + picks.get(0).blockPosition());
        List<ItemEntity> gravel = of(near, Items.GRAVEL);
        int total = gravel.stream().mapToInt(e -> e.getItem().getCount()).sum();
        report("40 cobblestone became 80 gravel in full stacks", total == 80 && gravel.stream().allMatch(e -> e.getItem().getCount() <= 64),
                gravel.size() + " stacks, " + total + " gravel");
        report("no thrown item stayed as it was", of(near, Items.IRON_PICKAXE).isEmpty() && of(near, Items.COBBLESTONE).isEmpty(), "");
        // 1.20.1: an item only goes through a Nether portal when a portal already stands on the other side (only players
        // build one), so in the test world unmatched items stay here; what matters is that they weren't crafted
        report("dirt (recipe for End portals only) was not crafted at a Nether portal", of(near, Items.DIAMOND).isEmpty(),
                of(near, Items.DIRT).size() + " dirt left here");
        report("stick (no recipe) was left as it was", of(near, Items.STICK).size() <= 1, of(near, Items.STICK).size() + " sticks here");
        long passed = RESULTS.stream().filter(r -> r.startsWith("PASS")).count();
        LOG.info("PORTALTEST DONE {}/{}", passed, RESULTS.size());
        level.setChunkForced(0, 0, false);
        server.halt(false);
    }

    /** A 4 x 5 obsidian frame along X with its 2 x 3 inside lit, on a glass floor that catches what comes out. */
    private static void build(ServerLevel level) {
        for (int x = -4; x < 8; x++) {
            for (int z = -5; z < 6; z++) {
                level.setBlock(BASE.offset(x, -1, z), Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 5; y++) {
                boolean inside = x > 0 && x < 3 && y > 0 && y < 4;
                level.setBlock(BASE.offset(x, y, 0), inside
                        ? Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, Direction.Axis.X)
                        : Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }

    private static void drop(ServerLevel level, ItemStack stack, double x) {
        ItemEntity e = new ItemEntity(level, BASE.getX() + x, BASE.getY() + 1.1, BASE.getZ() + 0.5, stack, 0, 0, 0);
        level.addFreshEntity(e);
    }

    private static List<ItemEntity> of(List<ItemEntity> near, Item item) {
        return near.stream().filter(e -> e.isAlive() && e.getItem().is(item)).toList();
    }

    private static void report(String name, boolean ok, String detail) {
        String line = (ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : " (" + detail + ")");
        RESULTS.add(line);
        LOG.info("PORTALTEST {}", line);
    }
}
