package com.fundamentum.relic;

import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.recipeview.RecipeViews;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The relic test, only in {@code gradlew runServer -Prelictest} (world under run-relictest). Three relic files in the test
 * datapack (3x3, 2x2 with a base tint, 1x1 with a tint) make three relics with no code. Checks: no blank relic and no base
 * block in any creative tab, the relic tab lists exactly the relic files, a blank {@code /give} is refused, the footprints and
 * fillers of 3x3 and 2x2 stands, a blocked placement fails, breaking a filler breaks the stand, a stand whose relic is gone
 * keeps its id, drops nothing and gets it back, names, recipe viewer hiding and subtypes, and the deprecated register's
 * mapping. Writes relictest-result.txt and stops the server. Log lines start with RELICTEST.
 */
public final class RelicSelfTest {
    private RelicSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final FId BIG = FId.of("relictest", "big");
    private static final FId PAIR = FId.of("relictest", "pair");
    private static final FId SMALL = FId.of("relictest", "small_thing");
    private static final FId GONE = FId.of("relictest", "gone");
    private static boolean failed;
    private static int ticks;
    private static RelicSet set;

    public static void init() {
        if (System.getProperty("fundamentum.relictest") == null) return;
        // the deprecated registration, as a mod written before relics were data calls it while it is built
        set = Relics.register(RelicSelfTest.class, "relictest", DeferredRegister.create(ForgeRegistries.BLOCKS, "relictest"), DeferredRegister.create(ForgeRegistries.ITEMS, "relictest"), "big", "small_thing");
        FLife.serverTick(RelicSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("RELICTEST {}", line);
        else LOG.error("RELICTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        if (++ticks != 40) return;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("RELICTEST threw", t);
            check(false, "threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("RELICTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("relictest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("RELICTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static boolean isBase(ItemStack s) {
        return s.getItem() instanceof BlockItem bi && (bi.getBlock() instanceof RelicBlock || bi.getBlock() instanceof RelicPartBlock)
            && !s.is(Relics.ITEM.get());
    }

    private static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();

        // the files alone made the relics
        check(Relics.TABLE.get(BIG.vanilla()) != null && Relics.TABLE.get(PAIR.vanilla()) != null && Relics.TABLE.get(SMALL.vanilla()) != null,
            "the three relic files loaded");
        check(Relics.TABLE.get(BIG.vanilla()).span == 3 && Relics.TABLE.get(PAIR.vanilla()).span == 2 && Relics.TABLE.get(SMALL.vanilla()).span == 1,
            "sizes 3, 2 and 1");
        check(Relics.TABLE.get(SMALL.vanilla()).tinted && Relics.TABLE.get(PAIR.vanilla()).base.tint != null
            && !Relics.TABLE.get(BIG.vanilla()).tinted && Relics.TABLE.get(BIG.vanilla()).base.tint == null, "tints: figure, base, and blank means gold");
        check(RelicBases.anchorFor(Relics.TABLE.get(BIG.vanilla())) == Relics.STAND.anchor(3), "a relic with no base stands on fundamentum:relic_stand");

        // creative tabs: no blank relic, no base block; the relic tab lists the files
        CreativeModeTab.ItemDisplayParameters params = new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(), true, server.registryAccess());
        int blanks = 0;
        int bases = 0;
        for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            try {
                tab.buildContents(params);
            } catch (RuntimeException e) {
                continue;
            }
            for (ItemStack s : tab.getDisplayItems()) {
                if (s.is(Relics.ITEM.get()) && !Relics.RELIC.has(s)) blanks++;
                if (isBase(s)) bases++;
            }
        }
        check(blanks == 0, "no blank relic in any creative tab (" + blanks + ")");
        check(bases == 0, "no base block in any creative tab (" + bases + ")");
        Relics.TAB.get().buildContents(params);
        long listed = Relics.TAB.get().getDisplayItems().stream().filter(s -> Relics.RELIC.has(s)).count();
        check(listed == Relics.TABLE.entries().size() && listed >= 3, "the relic tab lists every relic file (" + listed + "/" + Relics.TABLE.entries().size() + ")");
        check(Relics.STAND.anchor(3).asItem() == Items.AIR && Relics.STAND.filler().asItem() == Items.AIR, "base blocks have no item of their own");

        // a blank /give is refused; a give with data is not
        CommandSourceStack src = server.createCommandSourceStack();
        var dispatcher = server.getCommands().getDispatcher();
        check(Relics.blankGive(dispatcher.parse("give @s fundamentum:relic", src)), "blank /give is caught");
        check(Relics.blankGive(dispatcher.parse("execute run give @s fundamentum:relic 3", src)), "blank /give after execute is caught");
        check(!Relics.blankGive(dispatcher.parse("give @s fundamentum:relic[fundamentum:relic=\"relictest:big\"]", src)), "/give with relic data is let through");
        check(!Relics.blankGive(dispatcher.parse("give @s minecraft:stone", src)), "other /give is let through");
        CommandEvent ev = new CommandEvent(dispatcher.parse("give @s fundamentum:relic", src));
        MinecraftForge.EVENT_BUS.post(ev);
        check(ev.isCanceled(), "the blank /give command is cancelled");

        // names
        check("Big Test Relic".equals(Relics.name(BIG).getString()), "name from the file's name field: " + Relics.name(BIG).getString());
        check("Small Thing".equals(Relics.name(SMALL).getString()), "name from the id: " + Relics.name(SMALL).getString());
        check(Relics.ITEM.get().getName(new ItemStack(Relics.ITEM.get())).equals(Component.translatable("item.fundamentum.relic.unknown")), "a blank stack is an Unknown relic");
        check(Relics.stack(BIG).getHoverName().getString().equals("Big Test Relic"), "a relic stack takes its relic's name");

        // recipe viewers
        check(RecipeViews.hidden(new ItemStack(Relics.ITEM.get())), "the blank relic is hidden in recipe viewers");
        check(!RecipeViews.hidden(Relics.stack(BIG)), "a relic is shown in recipe viewers");
        check("relictest:big".equals(RecipeViews.subtypeKey(Relics.stack(BIG))), "relics are told apart by id in recipe viewers");

        // placing: a platform high up, clear above
        BlockPos o = new BlockPos(0, 200, 0);
        level.getChunkAt(o);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                level.setBlock(o.offset(x, -1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                for (int y = 0; y < 6; y++) level.setBlock(o.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }

        // a blank stack places nothing
        InteractionResult blank = place(level, new ItemStack(Relics.ITEM.get()), o);
        check(!blank.consumesAction() && level.getBlockState(o).isAir(), "a blank relic places nothing");

        // 3x3: the anchor in the middle, eight fillers round it, all pointing at it
        BlockPos a3 = o.offset(-4, 0, -4);
        check(place(level, Relics.stack(BIG), a3).consumesAction(), "a 3x3 relic places");
        footprint(level, a3, 3, BIG);

        // 2x2: grows away from the placer (north, the default with no player) and to their right
        BlockPos a2 = o.offset(4, 0, 4);
        check(place(level, Relics.stack(PAIR), a2).consumesAction(), "a 2x2 relic places");
        footprint(level, a2, 2, PAIR);

        // blocked: a block in the footprint, or above it, and nothing is placed
        BlockPos b = o.offset(4, 0, -4);
        level.setBlock(b.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        check(!place(level, Relics.stack(BIG), b).consumesAction() && level.getBlockState(b).isAir(), "a blocked 3x3 placement fails");
        level.setBlock(b.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(b.west().above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        check(!place(level, Relics.stack(BIG), b).consumesAction() && level.getBlockState(b).isAir(), "a 3x3 placement with no room above fails");
        level.setBlock(b.west().above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);

        // drops: the stand's relic, once
        BlockState s3 = level.getBlockState(a3);
        List<ItemStack> drops = Block.getDrops(s3, level, a3, level.getBlockEntity(a3));
        check(drops.size() == 1 && Relics.RELIC.has(drops.get(0)) && BIG.equals(Relics.RELIC.get(drops.get(0))), "a stand drops its relic: " + drops);
        check(Relics.stackOf((RelicBlockEntity) level.getBlockEntity(a3)).getCount() == 1, "pick block gives the relic");

        // breaking a filler breaks the whole stand
        BlockPos filler = a3.east();
        level.destroyBlock(filler, true);
        boolean gone = true;
        for (BlockPos c : RelicBlock.cells(a3, s3.getValue(RelicBlock.FACING), 3)) {
            if (!level.getBlockState(c).isAir()) gone = false;
        }
        check(gone, "breaking a filler breaks the whole 3x3 stand");

        // a stand whose relic is gone: keeps its id, drops nothing, comes back with its relic
        RelicBlockEntity be2 = (RelicBlockEntity) level.getBlockEntity(a2);
        be2.setRelic(GONE);
        check(Relics.def(GONE.toString()) == null && be2.def() == null, "an unknown relic has no file");
        check(Block.getDrops(level.getBlockState(a2), level, a2, be2).isEmpty(), "a stand with an unknown relic drops nothing");
        check(Relics.stackOf(be2).isEmpty(), "a stand with an unknown relic picks nothing");
        CompoundTag tag = be2.saveWithFullMetadata();
        BlockEntity loaded = BlockEntity.loadStatic(a2, level.getBlockState(a2), tag);
        check(loaded instanceof RelicBlockEntity r && GONE.toString().equals(r.relicId()), "a stand keeps an unknown relic's id when saved and loaded");
        be2.setRelic(PAIR);
        check(be2.def() == Relics.TABLE.get(PAIR.vanilla()), "the relic comes back when its id is known");
        level.destroyBlock(a2, false);
        boolean gone2 = true;
        for (BlockPos c : RelicBlock.cells(a2, Direction.SOUTH, 2)) {
            if (!level.getBlockState(c).isAir()) gone2 = false;
        }
        check(gone2, "breaking the anchor breaks the whole 2x2 stand");

        // the deprecated register maps a mod's relics onto the shared blocks and item
        check(set.block("big").get() == Relics.STAND.anchor(3), "deprecated register: block is the 3x3 stand");
        check(set.part("big").get() == Relics.STAND.filler(), "deprecated register: part is the stand's filler");
        check(set.part("small_thing") == null && set.block("small_thing").get() == Relics.STAND.anchor(1), "deprecated register: a 1x1 relic has no part");
        check(set.item("big").get() == Relics.ITEM.get(), "deprecated register: item is fundamentum:relic");
        check(BIG.equals(Relics.RELIC.get(set.stack("big"))), "deprecated register: stack(name) is that relic");
        check(set.entity().get() == Relics.ENTITY.get(), "deprecated register: the block entity is fundamentum:relic");
    }

    private static InteractionResult place(ServerLevel level, ItemStack stack, BlockPos at) {
        BlockPlaceContext ctx = new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND, stack,
            new BlockHitResult(Vec3.atCenterOf(at.below()).add(0, 0.5, 0), Direction.UP, at.below(), false));
        return ((BlockItem) Relics.ITEM.get()).place(ctx);
    }

    private static void footprint(ServerLevel level, BlockPos anchor, int n, FId relic) {
        BlockState st = level.getBlockState(anchor);
        check(st.getBlock() == Relics.STAND.anchor(n), n + "x" + n + ": the anchor is relic_stand_" + n);
        check(level.getBlockEntity(anchor) instanceof RelicBlockEntity be && relic.toString().equals(be.relicId()), n + "x" + n + ": the anchor holds its relic");
        Direction facing = st.getValue(RelicBlock.FACING);
        check(facing == Direction.SOUTH, n + "x" + n + ": faces the placer (south, with no player) " + facing);
        int fillers = 0;
        for (BlockPos c : RelicBlock.cells(anchor, facing, n)) {
            if (c.equals(anchor)) continue;
            BlockState fs = level.getBlockState(c);
            if (fs.getBlock() == Relics.STAND.filler() && RelicPartBlock.anchor(c, fs).equals(anchor)) fillers++;
        }
        check(fillers == n * n - 1, n + "x" + n + ": " + fillers + " fillers point at the anchor");
        if (n == 2) {
            // the anchor is one cell of the middle 2x2; the others go north (away) and east (right of a north-looking placer)
            boolean away = level.getBlockState(anchor.north()).getBlock() == Relics.STAND.filler();
            check(away, "2x2: grows away from the placer");
        }
    }
}
