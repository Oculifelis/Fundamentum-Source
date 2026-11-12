package com.fundamentum.multiblock;

import com.fundamentum.event.Events;
import com.fundamentum.event.FEvents;
import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.recipeview.RecipeViews;
import com.fundamentum.recipeview.ViewCategory;
import com.fundamentum.recipeview.ViewRecipe;
import com.fundamentum.recipeview.ViewSlot;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The multiblock test, only in {@code gradlew runServer -Pmultiblocktest} (world under run-multiblocktest). The same file
 * on every version. Test patterns under data/multiblocktest: an asymmetric furnace (tag, state, air and any-block cells),
 * the same shape allowed mirrored, one that may not turn, and a broken file. Builds each in every facing and checks
 * matching against hand-worked positions, wrong blocks, the formed and unformed events and cancelling, missing cells for
 * the preview, the viewer page and the preview packet. Writes multiblocktest-result.txt and stops the server. Log lines
 * start with MULTIBLOCKTEST.
 */
public final class MultiblockSelfTest {
    private MultiblockSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final List<Events.MultiblockFormed> FORMED = new ArrayList<>();
    private static final List<Events.MultiblockUnformed> UNFORMED = new ArrayList<>();
    private static boolean failed;
    private static boolean cancelNext;
    private static int ticks;

    static final FId FURNACE = FId.of("multiblocktest", "furnace");
    static final FId MIRRORED = FId.of("multiblocktest", "mirrored");
    static final FId FIXED = FId.of("multiblocktest", "fixed");

    public static void init() {
        if (System.getProperty("fundamentum.multiblocktest") == null) return;
        FEvents.listen(Events.MultiblockFormed.class, (Events.MultiblockFormed e, FEvents.Call call) -> {
            FORMED.add(e);
            if (cancelNext) call.cancel();
        });
        FEvents.listen(Events.MultiblockUnformed.class, (Events.MultiblockUnformed e) -> UNFORMED.add(e));
        FLife.serverTick(MultiblockSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("MULTIBLOCKTEST {}", line);
        else LOG.error("MULTIBLOCKTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        if (++ticks != 40) return;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("MULTIBLOCKTEST threw", t);
            check(false, "threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("MULTIBLOCKTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("multiblocktest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("MULTIBLOCKTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static void clear(ServerLevel level, BlockPos base) {
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-4, -1, -4), base.offset(4, 3, 4))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static boolean throwsOn(String json) {
        try {
            MultiblockPattern.parse(JsonParser.parseString(json).getAsJsonObject());
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos base = new BlockPos(8, 120, 8);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) level.getChunk((base.getX() >> 4) + cx, (base.getZ() >> 4) + cz);

        // loading
        Map<FId, MultiblockPattern> all = Multiblocks.patterns();
        check(all.containsKey(FURNACE) && all.containsKey(MIRRORED) && all.containsKey(FIXED), "the test patterns load (" + all.keySet() + ")");
        check(!all.containsKey(FId.of("multiblocktest", "bad")), "a broken pattern file is left out");
        MultiblockPattern fp = all.get(FURNACE);
        check(fp.width() == 3 && fp.height() == 2 && fp.depth() == 3, "size is width, layers, rows");
        check(fp.counts().get('I') == 6 && fp.counts().get('L') == 5 && fp.counts().get('C') == 1, "part counts " + fp.counts());
        check(fp.rotates() && !fp.mirrors() && all.get(MIRRORED).mirrors() && !all.get(FIXED).rotates(), "rotate and mirror flags");
        check(throwsOn("{\"layers\":[[\"CC\"]],\"key\":{\"C\":\"minecraft:stone\"},\"controller\":\"C\"}"), "two controllers are refused");
        check(throwsOn("{\"layers\":[[\"CX\"]],\"key\":{\"C\":\"minecraft:stone\"},\"controller\":\"C\"}"), "a character missing from the key is refused");
        check(throwsOn("{\"layers\":[[\"C\",\"AA\"]],\"key\":{\"C\":\"minecraft:stone\",\"A\":\"minecraft:dirt\"},\"controller\":\"C\"}"), "ragged rows are refused");

        // every facing, against positions worked out by hand
        int[][] gold = {{1, 2}, {-2, 1}, {-1, -2}, {2, -1}};
        int[][] furnace = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};
        Direction[] facings = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int i = 0; i < 4; i++) {
            Direction facing = facings[i];
            clear(level, base);
            int placed = Multiblocks.build(level, FURNACE, base, facing, false);
            Multiblocks.Match m = Multiblocks.match(level, base);
            check(placed == 15 && m != null && m.id().equals(FURNACE) && m.facing() == facing && !m.mirrored(),
                "built facing " + facing + " matches as " + (m == null ? "nothing" : m.id() + " " + m.facing() + " mirrored=" + m.mirrored()));
            check(level.getBlockState(base.offset(gold[i][0], 0, gold[i][1])).is(Blocks.GOLD_BLOCK), "the gold corner sits where expected facing " + facing);
            BlockState f = level.getBlockState(base.offset(furnace[i][0], 1, furnace[i][1]));
            check(f.is(Blocks.FURNACE) && f.getValue(BlockStateProperties.HORIZONTAL_FACING) == facing, "the furnace turns with the pattern facing " + facing);
            check(m != null && m.positions().size() == 15 && m.contains(base.offset(gold[i][0], 0, gold[i][1])), "positions cover the structure facing " + facing);
        }

        // wrong blocks
        clear(level, base);
        Multiblocks.build(level, FURNACE, base, Direction.NORTH, false);
        check(Multiblocks.match(level, base, FURNACE) != null, "match by id");
        check(Multiblocks.match(level, base, MIRRORED) == null, "another pattern by id does not match");
        level.setBlock(base.offset(0, 1, 1), Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST), 3);
        check(Multiblocks.match(level, base) == null, "a furnace facing the wrong way breaks the match");
        clear(level, base);
        Multiblocks.build(level, FURNACE, base, Direction.NORTH, false);
        level.setBlock(base.offset(-1, 1, 2), Blocks.BIRCH_LOG.defaultBlockState(), 3);
        level.setBlock(base.offset(1, 1, 2), Blocks.SPRUCE_LOG.defaultBlockState(), 3);
        check(Multiblocks.match(level, base) != null, "any block in a tag counts");
        level.setBlock(base.offset(0, 1, 0), Blocks.STONE.defaultBlockState(), 3);
        check(Multiblocks.match(level, base) != null, "a space takes any block");
        level.setBlock(base.offset(-1, 1, 2), Blocks.STONE.defaultBlockState(), 3);
        check(Multiblocks.match(level, base) == null, "a block outside the tag breaks the match");
        clear(level, base);
        Multiblocks.build(level, FURNACE, base, Direction.NORTH, false);
        level.setBlock(base.offset(0, 0, 1), Blocks.STONE.defaultBlockState(), 3);
        check(Multiblocks.match(level, base) == null, "a block where air belongs breaks the match");

        // mirroring and fixed patterns
        clear(level, base);
        Multiblocks.build(level, MIRRORED, base, Direction.NORTH, true);
        Multiblocks.Match mm = Multiblocks.match(level, base);
        check(mm != null && mm.id().equals(MIRRORED) && mm.mirrored() && level.getBlockState(base.offset(-1, 0, 2)).is(Blocks.GOLD_BLOCK),
            "a mirrored build matches a pattern that allows it, flipped east to west");
        clear(level, base);
        Multiblocks.build(level, MIRRORED, base, Direction.SOUTH, false);
        mm = Multiblocks.match(level, base);
        check(mm != null && !mm.mirrored() && mm.facing() == Direction.SOUTH, "the mirroring pattern still matches unflipped");
        clear(level, base);
        Multiblocks.build(level, FURNACE, base, Direction.EAST, true);
        check(Multiblocks.match(level, base) == null, "a pattern without mirror refuses a flipped build");
        clear(level, base);
        Multiblocks.build(level, FIXED, base, Direction.NORTH, false);
        Multiblocks.Match fx = Multiblocks.match(level, base);
        check(fx != null && fx.id().equals(FIXED) && fx.facing() == Direction.NORTH, "a fixed pattern matches facing north");
        clear(level, base);
        Multiblocks.build(level, FIXED, base, Direction.EAST, false);
        check(Multiblocks.match(level, base) == null, "a fixed pattern refuses a turned build");

        // missing cells, for the preview
        clear(level, base);
        List<Multiblocks.Missing> missing = Multiblocks.missing(level, FURNACE, base, Direction.EAST, false);
        BlockState wantFurnace = missing.stream().filter(x -> x.pos().equals(base.offset(-1, 1, 0))).map(Multiblocks.Missing::expected).findFirst().orElse(null);
        check(missing.size() == 14, "an empty site misses every cell but the air (" + missing.size() + ")");
        check(wantFurnace != null && wantFurnace.is(Blocks.FURNACE) && wantFurnace.getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.EAST,
            "the preview shows the furnace turned to the build's facing");
        Multiblocks.build(level, FURNACE, base, Direction.EAST, false);
        check(Multiblocks.missing(level, FURNACE, base, Direction.EAST, false).isEmpty(), "a finished build misses nothing");

        // forming
        clear(level, base);
        Multiblocks.build(level, FURNACE, base, Direction.NORTH, false);
        int f0 = FORMED.size(), u0 = UNFORMED.size();
        Multiblocks.Match formed = Multiblocks.check(level, base);
        Events.MultiblockFormed fe = FORMED.size() == f0 + 1 ? FORMED.get(f0) : null;
        check(formed != null && fe != null && fe.structure().equals(FURNACE) && fe.controller().equals(base) && fe.facing() == Direction.NORTH
            && !fe.mirrored() && fe.level() == level, "check forms the structure and posts multiblock_formed");
        check(Multiblocks.check(level, base) != null && FORMED.size() == f0 + 1, "checking again posts nothing new");
        check(Multiblocks.formed(level, base) != null && Multiblocks.formed(level).size() == 1, "formed structures are remembered");
        Multiblocks.blockChanged(level, base.offset(5, 0, 5));
        check(Multiblocks.formed(level, base) != null, "a change outside the structure leaves it formed");
        level.setBlock(base.offset(-1, 0, 0), Blocks.AIR.defaultBlockState(), 3);
        Multiblocks.recheck(level);
        Events.MultiblockUnformed ue = UNFORMED.size() == u0 + 1 ? UNFORMED.get(u0) : null;
        check(ue != null && ue.structure().equals(FURNACE) && ue.controller().equals(base) && Multiblocks.formed(level, base) == null,
            "breaking a block unforms it on the next recheck and posts multiblock_unformed");
        Multiblocks.build(level, FURNACE, base, Direction.NORTH, false);
        check(Multiblocks.check(level, base) != null, "rebuilt, it forms again");
        level.setBlock(base.offset(1, 1, 2), Blocks.AIR.defaultBlockState(), 3);
        Multiblocks.blockChanged(level, base.offset(1, 1, 2));
        check(Multiblocks.formed(level, base) == null && UNFORMED.size() == u0 + 2, "blockChanged unforms at once");
        Multiblocks.build(level, FURNACE, base, Direction.NORTH, false);
        cancelNext = true;
        int f1 = FORMED.size();
        check(Multiblocks.check(level, base) == null && Multiblocks.formed(level, base) == null && FORMED.size() == f1 + 1, "a cancelled multiblock_formed leaves it unformed");
        cancelNext = false;
        check(Multiblocks.check(level, base) != null, "formed once nothing cancels");
        clear(level, base);
        Multiblocks.build(level, FURNACE, base, Direction.WEST, false);
        int u1 = UNFORMED.size();
        Multiblocks.Match turned = Multiblocks.check(level, base);
        check(turned != null && turned.facing() == Direction.WEST && UNFORMED.size() == u1 + 1 && Multiblocks.formed(level, base).facing() == Direction.WEST,
            "rebuilt facing another way, the old structure unforms and the new one forms");
        clear(level, base);
        check(Multiblocks.check(level, base) == null && Multiblocks.formed(level).isEmpty(), "checking a cleared site unforms it");

        // viewer page
        ViewCategory<?> cat = RecipeViews.find(MultiblockViews.CATEGORY);
        check(cat != null, "the viewer page is registered");
        if (cat != null) {
            List<ViewRecipe> built = cat.build();
            ViewRecipe page = built.stream().filter(r -> r.id().equals(MultiblockViews.CATEGORY + "/multiblocktest/furnace")).findFirst().orElse(null);
            int out = cat.slots().stream().filter(s -> s.role() == ViewSlot.Role.OUTPUT).mapToInt(ViewSlot::index).findFirst().orElse(-1);
            check(built.size() == 3 && page != null, "one viewer entry per pattern (" + built.size() + ")");
            check(page != null && out >= 0 && page.items(out).stream().anyMatch(s -> s.is(Items.CRAFTING_TABLE)), "the controller shows as the output");
            boolean iron = false, logs = false;
            if (page != null) {
                for (ViewSlot s : cat.slots()) {
                    if (s.role() != ViewSlot.Role.INPUT) continue;
                    List<ItemStack> st = page.items(s.index());
                    if (st.size() == 1 && st.get(0).is(Items.IRON_BLOCK) && st.get(0).getCount() == 6) iron = true;
                    if (st.size() > 1 && st.stream().anyMatch(x -> x.is(Items.OAK_LOG)) && st.get(0).getCount() == 5) logs = true;
                }
            }
            check(iron && logs, "parts show with their counts, a tag as every block in it");
            check(page != null && page.blocks().size() == 1 && page.blocks().get(0).pattern().equals(FURNACE), "the entry draws the structure as blocks");
            check(MultiblockViews.title(FURNACE, fp).getString().equals("Furnace"), "a pattern without a name is titled from its file");
        }

        // preview packet
        Multiblocks.Preview pv = new Multiblocks.Preview(FURNACE.toString(), base, Direction.EAST, true);
        check(pv.equals(FNet.roundTrip(pv, server.registryAccess())), "the preview packet survives the network codec");
        Multiblocks.project(new Multiblocks.Projection(FURNACE, base, Direction.SOUTH, false));
        check(Multiblocks.projection() != null && Multiblocks.projection().facing() == Direction.SOUTH, "a projection can be set");
        Multiblocks.project(null);
        check(Multiblocks.projection() == null, "and cleared");
    }
}
