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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Apothic Attributes test, only in {@code gradlew runServer -Papothictest} (Apothic and Placebo on the run class
 * path, world under run-apothictest). Checks that the stats Apothic shares with us are Apothic's own, that our ids are
 * aliases of them, that mobs and players carry them, and that our other stats stay ours. Apothic has no projectile
 * damage on 1.20.1, so ranged_damage stays ours here. Writes apothictest-result.txt and stops the server.
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
        check(folded.size() == 4 && !folded.containsKey("ranged_damage"), "4 stats folded into Apothic's: " + folded);
        ServerPlayer p = FVanilla.fakePlayer(server.overworld(), "apothictest");

        Map<String, RegistryObject<Attribute>> holders = Map.of("crit_chance", StatAttributes.CRIT_CHANCE, "crit_damage", StatAttributes.CRIT_DAMAGE,
            "armor_pierce", StatAttributes.ARMOR_PIERCE, "projectile_speed", StatAttributes.PROJECTILE_SPEED);
        for (Map.Entry<String, String> e : folded.entrySet()) {
            RegistryObject<Attribute> h = holders.get(e.getKey());
            check(h != null && h.isPresent() && StatAttributes.apothicOwns(h), e.getKey() + " is Apothic's own");
            if (h == null || !h.isPresent()) continue;
            AttributeInstance theirs = FVanilla.attribute(p, FId.of(StatAttributes.APOTHIC, e.getValue()));
            check(theirs != null && theirs.getAttribute() == h.get(), e.getKey() + " is " + StatAttributes.APOTHIC + ":" + e.getValue());
            AttributeInstance ours = FVanilla.attribute(p, FId.of("fundamentum", e.getKey()));
            check(ours != null && ours.getAttribute() == h.get(), "fundamentum:" + e.getKey() + " is an alias of it");
            check(DefaultAttributes.getSupplier(EntityType.ZOMBIE).hasAttribute(h.get()), "zombies carry " + e.getKey());
        }

        AttributeInstance alias = FVanilla.attribute(p, FId.of("fundamentum", "crit_chance"));
        if (alias != null) {
            alias.setBaseValue(0.4);
            check(Math.abs(p.getAttributeValue(StatAttributes.CRIT_CHANCE.get()) - 0.4) < 1.0E-9, "a value set through our id shows on Apothic's");
        }

        check(!StatAttributes.apothicOwns(StatAttributes.RANGED_DAMAGE), "ranged_damage stays ours (no projectile damage here)");
        check(!StatAttributes.apothicOwns(StatAttributes.FIRE_RATE), "fire_rate stays ours");
        AttributeInstance fireRate = FVanilla.attribute(p, FId.of("fundamentum", "fire_rate"));
        check(fireRate != null && fireRate.getAttribute() == StatAttributes.FIRE_RATE.get(), "fundamentum:fire_rate is registered");
        check(DefaultAttributes.getSupplier(EntityType.ZOMBIE).hasAttribute(StatAttributes.FIRE_RATE.get()), "zombies carry fire_rate");
    }
}
