package com.fundamentum.attribute;

import com.fundamentum.port.FId;
import com.fundamentum.port.FLife;
import com.fundamentum.port.FVanilla;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Apothic Attributes test, only in {@code gradlew runServer -Papothictest} (Apothic and Placebo on the run class
 * path, world under run-apothictest). The same file on every NeoForge version. Checks that the stats Apothic shares with
 * us are Apothic's own, that our ids are aliases of them, that mobs and players carry them, and that our other stats
 * stay ours. Writes apothictest-result.txt and stops the server. Log lines start with APOTHICTEST.
 */
public final class ApothicSelfTest {
    private ApothicSelfTest() { }

    private static final Logger LOG = LoggerFactory.getLogger("Fundamentum");
    private static final List<String> LINES = new ArrayList<>();
    private static boolean failed;
    private static int ticks;

    public static void init() {
        if (System.getProperty("fundamentum.apothictest") == null) return;
        FLife.serverTick(ApothicSelfTest::tick);
    }

    private static void check(boolean ok, String what) {
        String line = (ok ? "PASS " : "FAIL ") + what;
        if (!ok) failed = true;
        LINES.add(line);
        if (ok) LOG.info("APOTHICTEST {}", line);
        else LOG.error("APOTHICTEST {}", line);
    }

    private static void tick(MinecraftServer server) {
        if (++ticks != 40) return;
        try {
            run(server);
        } catch (Throwable t) {
            LOG.error("APOTHICTEST threw", t);
            check(false, "threw " + t);
        }
        LINES.add(failed ? "RESULT FAIL" : "RESULT PASS");
        LOG.info("APOTHICTEST RESULT {}", failed ? "FAIL" : "PASS");
        try {
            Files.write(Path.of("apothictest-result.txt"), LINES);
        } catch (IOException e) {
            LOG.error("APOTHICTEST could not write the result file", e);
        }
        server.halt(false);
    }

    private static void run(MinecraftServer server) {
        check(ModList.get().isLoaded(StatAttributes.APOTHIC), "Apothic Attributes is loaded");
        Map<String, String> folded = StatAttributes.foldedIds();
        check(folded.size() == 5, "5 stats folded into Apothic's: " + folded);
        ServerPlayer p = FVanilla.fakePlayer(server.overworld(), "apothictest");

        Map<String, Holder<Attribute>> holders = Map.of("crit_chance", StatAttributes.CRIT_CHANCE, "crit_damage", StatAttributes.CRIT_DAMAGE,
            "ranged_damage", StatAttributes.RANGED_DAMAGE, "armor_pierce", StatAttributes.ARMOR_PIERCE, "projectile_speed", StatAttributes.PROJECTILE_SPEED);
        for (Map.Entry<String, String> e : folded.entrySet()) {
            Holder<Attribute> h = holders.get(e.getKey());
            check(h != null && h.isBound() && StatAttributes.apothicOwns(h), e.getKey() + " is Apothic's own");
            if (h == null || !h.isBound()) continue;
            AttributeInstance theirs = FVanilla.attribute(p, FId.of(StatAttributes.APOTHIC, e.getValue()));
            check(theirs != null && theirs.getAttribute().value() == h.value(), e.getKey() + " is " + StatAttributes.APOTHIC + ":" + e.getValue());
            AttributeInstance ours = FVanilla.attribute(p, FId.of("fundamentum", e.getKey()));
            check(ours != null && ours.getAttribute().value() == h.value(), "fundamentum:" + e.getKey() + " is an alias of it");
            check(DefaultAttributes.getSupplier(EntityType.ZOMBIE).hasAttribute(h), "zombies carry " + e.getKey());
        }

        AttributeInstance alias = FVanilla.attribute(p, FId.of("fundamentum", "crit_chance"));
        if (alias != null) {
            alias.setBaseValue(0.4);
            check(Math.abs(FVanilla.attributeValue(p, StatAttributes.CRIT_CHANCE) - 0.4) < 1.0E-9, "a value set through our id shows on Apothic's");
        }

        check(!StatAttributes.apothicOwns(StatAttributes.FIRE_RATE), "fire_rate stays ours");
        AttributeInstance fireRate = FVanilla.attribute(p, FId.of("fundamentum", "fire_rate"));
        check(fireRate != null && fireRate.getAttribute().value() == StatAttributes.FIRE_RATE.value(), "fundamentum:fire_rate is registered");
        check(DefaultAttributes.getSupplier(EntityType.ZOMBIE).hasAttribute(StatAttributes.FIRE_RATE), "zombies carry fire_rate");
    }
}
