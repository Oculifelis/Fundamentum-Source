package com.fundamentum.summon;

import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FNet;
import com.fundamentum.port.FVanilla;
import com.fundamentum.slots.Slots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The summon switch test, only in {@code gradlew runServer -Psummontoggletest} (world under run-summontoggletest).
 * Checks that the switch is on by default, saved and kept through death, that its packet is registered and survives
 * the network codec, that switching off sends minions and sentries away, keeps minions from coming back and refuses
 * new sentries, and that switching on brings the minions back. Writes summontoggletest-result.txt and stops the server.
 * Log lines start with SUMMONTOGGLETEST.
 */
public final class SummonToggleSelfTest {
    private SummonToggleSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static final BlockPos AT = new BlockPos(8, 200, 8);
    private static final Identifier SNOWBALL = Identifier.withDefaultNamespace("snowball");
    private static final Identifier SNOW_GOLEM = Identifier.withDefaultNamespace("snow_golem");
    private static boolean failed;
    private static int ticks;

    public static void init() {
        if (System.getProperty("fundamentum.summontoggletest") == null) return;
        FLife.serverTick(SummonToggleSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("SUMMONTOGGLETEST {}", line);
        else LOG.error("SUMMONTOGGLETEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks == 5) server.overworld().setChunkForced(0, 0, true);
        try {
            if (ticks == 40) run(server);
        } catch (Throwable t) {
            LOG.error("SUMMONTOGGLETEST threw", t);
            check(false, "threw " + t);
        }
        if (ticks != 60) return;
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("SUMMONTOGGLETEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("summontoggletest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("SUMMONTOGGLETEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static Sentries.Placed place(ServerPlayer p, int x) {
        Sentries.Placed placed = Sentries.place(p, SNOWBALL, Sentries.SentryDefinition.builder(SNOW_GOLEM).build(), AT.offset(x, -1, 3), Direction.UP);
        if (placed.sentry() != null) placed.sentry().setInvulnerable(true);
        return placed;
    }

    /** A fake player never joins the level or ticks, so Curios has not laid out its slots; this does what joining would. */
    private static void settle(ServerPlayer p) {
        top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(p).ifPresent(inv -> {
            if (inv.getCurios().isEmpty()) inv.reset();
        });
        NeoForge.EVENT_BUS.post(new EntityTickEvent.Post(p));
    }

    private static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                level.setBlockAndUpdate(AT.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 4; y++) level.setBlockAndUpdate(AT.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }
        ServerPlayer p = FVanilla.fakePlayer(level, "summontoggletest");
        p.setPos(AT.getX() + 0.5, AT.getY(), AT.getZ() + 0.5);
        p.getAttribute(Sentries.SENTRY_SLOTS).setBaseValue(4);
        settle(p);
        ItemStack stick = new ItemStack(FVanilla.item(FId.of("minecraft:stick")));

        check(SummonToggle.enabled(p), "summons are on by default");
        check(SummonToggle.ENABLED.saved() && SummonToggle.ENABLED.copiesOnDeath(), "the switch is saved with the player and kept through death");
        check(FNet.registered(SummonToggle.REQUEST), "the toggle packet is registered");
        check(FNet.roundTrip(SummonToggle.Request.INSTANCE, server.registryAccess()) != null, "the toggle packet survives its codec");
        check(Summons.def(stick) != null, "the test summon file loads");

        // on: a minion and a sentry come out
        check(Slots.set(p, Slots.SUMMON, 0, stick.copy()), "the summon item goes in the summon slot");
        settle(p);
        Summons.update(p);
        List<Mob> minions = new ArrayList<>(Summons.minions(p));
        check(minions.size() == 1, "on: the worn summon item calls its minion (" + minions.size() + ")");
        Sentries.Placed first = place(p, -2);
        check(first.ok() && Sentries.sentries(p).size() == 1, "on: a sentry is placed (" + first.failure() + ")");

        // off: everything goes and stays gone
        boolean now = SummonToggle.toggle(p);
        check(!now && !SummonToggle.enabled(p), "the toggle switches summons off");
        check(Summons.minions(p).isEmpty() && minions.stream().allMatch(Mob::isRemoved), "off: the minion is sent away");
        check(Sentries.sentries(p).isEmpty() && first.sentry() != null && first.sentry().isRemoved(), "off: the sentry is sent away");
        Summons.update(p);
        check(Summons.minions(p).isEmpty(), "off: the minion is not called back while the item stays worn");
        Sentries.Placed refused = place(p, 0);
        check(!refused.ok() && "summons_off".equals(refused.failure()), "off: a new sentry is refused (" + refused.failure() + ")");

        // on again: the minion comes back and sentries can be placed
        now = SummonToggle.toggle(p);
        check(now && SummonToggle.enabled(p), "the toggle switches summons back on");
        Summons.update(p);
        check(Summons.minions(p).size() == 1, "on again: the minion comes back (" + Summons.minions(p).size() + ")");
        Sentries.Placed again = place(p, 2);
        check(again.ok(), "on again: a sentry is placed (" + again.failure() + ")");

        Summons.dismiss(p);
        Sentries.dismiss(p);
        Slots.set(p, Slots.SUMMON, 0, ItemStack.EMPTY);
    }
}
