package com.fundamentum.transfer;

import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FTransfer;
import com.fundamentum.recipeview.RecipeViews;
import com.fundamentum.recipeview.ViewCategory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The transfer test, only in {@code gradlew runServer -Ptransfertest} (world under run-transfertest). The same file on
 * every version. Exposes buffers and tanks on vanilla barrels, then checks reads through the loader capability, limits,
 * simulation, sides, pushing to neighbours, tank filters and access, change listeners, the save and sync codecs, the
 * client tank map, item-to-fluid declarations (data, code, overrides, bad files), the recipe view category and the
 * command. Writes transfertest-result.txt and stops the server. Log lines start with TRANSFERTEST.
 */
public final class TransferSelfTest {
    private TransferSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final Map<BlockPos, EnergyBuffer> ENERGY = new HashMap<>();
    private static final Map<BlockPos, FluidTanks> FLUIDS = new HashMap<>();
    private static final BlockPos A = new BlockPos(0, 200, 0);
    private static final BlockPos B = A.east();
    private static boolean failed;
    private static int ticks;

    public static void init() {
        if (System.getProperty("fundamentum.transfertest") == null) return;
        FId barrel = FId.of("minecraft", "barrel");
        Transfer.exposeEnergy(barrel, (be, side) -> {
            if (be.getBlockPos().equals(B) && side == Direction.UP) return null;
            return ENERGY.get(be.getBlockPos());
        });
        Transfer.exposeFluids(barrel, (be, side) -> FLUIDS.get(be.getBlockPos()));
        ItemFluids.add(FId.of("transfertest", "code_water"), FId.of("minecraft", "water_bucket"), FId.of("minecraft", "water"), 1000,
            FId.of("minecraft", "bucket"));
        ItemFluids.add(FId.of("transfertest", "override"), FId.of("minecraft", "honey_bottle"), FId.of("minecraft", "water"), 1, null);
        FLife.serverTick(TransferSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("TRANSFERTEST {}", line);
        else LOG.error("TRANSFERTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        if (++ticks != 40) return;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("TRANSFERTEST threw", t);
            check(false, "threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("TRANSFERTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("transfertest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("TRANSFERTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static void run(MinecraftServer server) throws Exception {
        ServerLevel level = server.overworld();
        int[] energyChanges = {0};
        EnergyBuffer bufA = new EnergyBuffer(1000, 100, 100).onChange(() -> energyChanges[0]++);
        EnergyBuffer bufB = new EnergyBuffer(500, 50, 1000);
        ENERGY.put(A, bufA);
        ENERGY.put(B, bufB);
        int[] tankChanges = {0};
        FluidTank tankA = new FluidTank(4000).onChange(() -> tankChanges[0]++);
        FluidTank inB = new FluidTank(2000).filter(f -> f == Fluids.WATER).access(true, false);
        FluidTank outB = new FluidTank(2000).access(false, true);
        FLUIDS.put(A, FluidTanks.of(tankA));
        FLUIDS.put(B, FluidTanks.of(inB, outB));
        BlockPos stone = A.above(3);
        level.setBlock(A, Blocks.BARREL.defaultBlockState(), 2);
        level.setBlock(B, Blocks.BARREL.defaultBlockState(), 2);
        level.setBlock(stone, Blocks.STONE.defaultBlockState(), 2);
        for (Direction d : Direction.values()) {
            BlockPos n = A.relative(d);
            if (!n.equals(B)) level.setBlock(n, Blocks.AIR.defaultBlockState(), 2);
        }

        // energy through the loader capability
        EnergyStore ea = Transfer.energy(level, A, null);
        check(ea != null && FTransfer.isLoaderView(ea), "an exposed buffer is found through the loader capability");
        check(Transfer.energy(level, stone, null) == null && Transfer.energy(level, A.above(), null) == null,
            "blocks without energy give null");
        check(ea.insert(150, false) == 100 && bufA.stored() == 100, "insert through the capability is held to the buffer's limit");
        check(energyChanges[0] == 1, "a real insert runs the change listener once (" + energyChanges[0] + ")");
        check(ea.insert(10, true) == 10 && bufA.stored() == 100 && energyChanges[0] == 1, "a simulated insert changes nothing");
        check(ea.extract(30, true) == 30 && bufA.stored() == 100, "a simulated extract changes nothing");
        check(ea.stored() == 100 && ea.capacity() == 1000, "stored and capacity read through the capability");
        check(Transfer.energy(level, B, Direction.UP) == null && Transfer.energy(level, B, Direction.DOWN) != null,
            "a provider can refuse one side");
        check(bufA.add(5000) == 900 && bufA.stored() == 1000, "the owner's add ignores the per-call limit but not the capacity");
        check(bufA.consume(950) == 950 && bufA.stored() == 50, "the owner's consume ignores the per-call limit");
        check(!bufA.tryConsume(51) && bufA.tryConsume(50) && bufA.stored() == 0, "tryConsume takes all or nothing");
        bufA.set(1000);
        int pushed = Transfer.pushEnergy(level, A, bufA, 1000);
        check(pushed == 50 && bufB.stored() == 50 && bufA.stored() == 950, "pushEnergy fills the neighbour up to its insert limit (" + pushed + ")");
        bufB.set(500);
        int pulled = Transfer.pullEnergy(level, A, bufA, 1000);
        check(pulled == 50 && bufA.stored() == 1000 && bufB.stored() == 450, "pullEnergy stops when the buffer is full (" + pulled + ")");
        check(Transfer.moveEnergy(bufB, ea, 10) == 0, "moving into a full store moves nothing");

        // fluids through the loader capability
        FluidStore fa = Transfer.fluids(level, A, null);
        check(fa != null && FTransfer.isLoaderView(fa) && fa.tanks() == 1, "an exposed tank is found through the loader capability");
        check(Transfer.fluids(level, stone, null) == null, "blocks without fluids give null");
        check(fa.insert(Fluids.WATER, 5000, false) == 4000 && tankA.amount() == 4000 && tankA.fluid() == Fluids.WATER,
            "fluid insert through the capability stops at the capacity");
        check(tankChanges[0] == 1, "a real fill runs the tank listener once (" + tankChanges[0] + ")");
        check(fa.insert(Fluids.LAVA, 100, false) == 0, "a tank holding water takes no lava");
        check(fa.extract(Fluids.WATER, 100, true) == 100 && tankA.amount() == 4000 && tankChanges[0] == 1,
            "a simulated drain changes nothing");
        check(fa.fluid(0) == Fluids.WATER && fa.amount(0) == 4000 && fa.capacity(0) == 4000, "tank contents read through the capability");
        int pf = Transfer.pushFluid(level, A, tankA, 1500);
        check(pf == 1500 && inB.amount() == 1500 && tankA.amount() == 2500, "pushFluid moves water into the neighbour's input tank (" + pf + ")");
        FluidStore fb = Transfer.fluids(level, B, Direction.WEST);
        check(fb != null && fb.tanks() == 2, "a multi-tank store shows every tank");
        check(fb.insert(Fluids.LAVA, 100, false) == 0, "filters and fill access keep lava out of both tanks");
        outB.fill(Fluids.LAVA, 1000, false);
        check(outB.amount() == 1000, "the owner fills an output tank that outsiders cannot fill");
        check(fb.extract(Fluids.LAVA, 300, false) == 300 && outB.amount() == 700, "outsiders drain the output tank");
        check(fb.extract(Fluids.WATER, 300, false) == 0 && inB.amount() == 1500, "outsiders cannot drain the input tank");
        check(Transfer.moveFluid(fb, fa, 500) == 0, "moving lava into a water tank moves nothing");
        tankA.set(Fluids.EMPTY, 0);
        check(Transfer.moveFluid(fb, fa, 500) == 500 && tankA.fluid() == Fluids.LAVA && outB.amount() == 200,
            "moveFluid picks the first fluid that fits");
        FluidTanks two = FluidTanks.of(new FluidTank(1000), new FluidTank(1000));
        check(two.insert(Fluids.WATER, 1500, true) == 1000 && two.isEmpty(), "a simulated insert into empty tanks counts one tank only");
        check(two.insert(Fluids.WATER, 1500, false) == 1000 && two.insert(Fluids.LAVA, 300, false) == 300
            && two.amountOf(Fluids.WATER) == 1000 && two.amountOf(Fluids.LAVA) == 300, "each fluid gets its own tank");

        // saving and sync
        FluidTank.Contents c = tankA.contents();
        Tag saved = FluidTank.Contents.CODEC.encodeStart(NbtOps.INSTANCE, c).result().orElseThrow();
        FluidTank.Contents back = FluidTank.Contents.CODEC.parse(NbtOps.INSTANCE, saved).result().orElseThrow();
        FluidTank copy = new FluidTank(4000);
        copy.load(back);
        check(back.equals(c) && copy.fluid() == Fluids.LAVA && copy.amount() == 500, "tank contents survive the save codec");
        copy.load(new FluidTank.Contents(FId.of("transfertest", "no_such_fluid"), 10));
        check(copy.empty(), "an unknown fluid loads as empty");
        BlockEntity beA = level.getBlockEntity(A);
        Transfer.track(beA, tankA);
        check(Transfer.tracked(beA), "a block entity's tanks can be tracked");
        List<Transfer.TankSync> msgs = Transfer.syncMessages(beA);
        check(msgs.size() == 1, "one sync message per tracked tank");
        Transfer.TankSync rt = FNet.roundTrip(msgs.get(0), server.registryAccess());
        check(rt.equals(msgs.get(0)), "the tank sync survives the network codec");
        Transfer.receive(rt);
        Transfer.TankView v = Transfer.clientTank(A, 0);
        check(v.fluid() == Fluids.LAVA && v.amount() == 500 && v.capacity() == 4000 && Math.abs(v.fraction() - 0.125f) < 1e-6,
            "the client tank map holds what was sent");
        check(Transfer.clientTank(A, 3).empty(), "an unknown client tank reads empty");
        check(Transfer.tankTooltip(Fluids.LAVA, 500, 4000).size() == 2, "the tank tooltip has a name and an amount line");
        Transfer.untrack(beA);
        check(!Transfer.tracked(beA), "untrack stops syncing");

        // item to fluid
        ItemFluids.ItemFluid lava = ItemFluids.find(new ItemStack(Items.LAVA_BUCKET));
        check(lava != null && lava.toFluid() == Fluids.LAVA && lava.amount() == 1000 && lava.remainderStack().is(Items.BUCKET),
            "a data file declares lava in a lava bucket");
        ItemFluids.ItemFluid water = ItemFluids.find(new ItemStack(Items.WATER_BUCKET));
        check(water != null && water.id().equals(FId.of("transfertest", "code_water")), "a code declaration is found");
        ItemFluids.ItemFluid honey = ItemFluids.find(new ItemStack(Items.HONEY_BOTTLE));
        check(honey != null && honey.amount() == 250 && honey.remainderStack().is(Items.GLASS_BOTTLE), "a data file replaces a code entry with the same id");
        check(ItemFluids.find(new ItemStack(Items.STONE)) == null, "a broken file is left out");
        check(ItemFluids.forFluid(Fluids.WATER).size() == 2, "declarations can be listed by fluid");
        ItemFluids.Sync sync = new ItemFluids.Sync(ItemFluids.all());
        check(FNet.roundTrip(sync, server.registryAccess()).equals(sync), "the declarations survive the network codec");
        ViewCategory<?> cat = RecipeViews.find(ItemFluids.CATEGORY);
        check(cat != null && cat.build().size() == 3, "the recipe view category lists the declarations");

        // command
        int r = server.getCommands().getDispatcher().execute("fundamentum transfer 0 200 0", server.createCommandSourceStack().withSuppressedOutput());
        check(r == 1, "the transfer command reads a block");
    }
}
